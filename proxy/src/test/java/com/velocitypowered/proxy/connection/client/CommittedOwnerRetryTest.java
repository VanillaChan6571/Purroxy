/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The retry a committed transfer gets at its recorded owner. Ownership is settled by the time this
 * runs, so every case here is about reaching that one destination or giving up honestly.
 */
class CommittedOwnerRetryTest {

  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final RegisteredServer destination = mock(RegisteredServer.class);
  private final AtomicBoolean live = new AtomicBoolean(true);
  private final AtomicBoolean owned = new AtomicBoolean(true);
  private final List<String> events = new ArrayList<>();

  @AfterEach
  void close() {
    channel.finishAndReleaseAll();
  }

  private Impl success() {
    return ConnectionRequestResults.successful(destination);
  }

  private Impl refused() {
    return ConnectionRequestResults.forDisconnect(Component.text("still starting"), destination);
  }

  private CommittedOwnerRetry retry(java.util.function.IntFunction<CompletableFuture<Impl>> connector) {
    return new CommittedOwnerRetry(channel.eventLoop(), connector, live::get, owned::get,
        new CommittedOwnerRetry.Outcome() {
          @Override
          public void retrying(int attempt, String cause) {
            events.add("retrying:" + attempt);
          }

          @Override
          public void succeeded(int attempt) {
            events.add("succeeded:" + attempt);
          }

          @Override
          public void abandoned(String why) {
            events.add("abandoned");
          }

          @Override
          public void exhausted(String cause) {
            events.add("exhausted");
          }
        });
  }

  /** Advances the loop far enough to run every scheduled backoff. */
  private void settle() {
    for (int tick = 0; tick < CommittedOwnerRetry.MAX_ATTEMPTS + 2; tick++) {
      channel.runPendingTasks();
      channel.advanceTimeBy(CommittedOwnerRetry.BACKOFF_MILLIS * (tick + 1), TimeUnit.MILLISECONDS);
      channel.runScheduledPendingTasks();
    }
    channel.runPendingTasks();
  }

  @Test
  void destinationStillStartingIsReachedOnLaterAttempt() {
    AtomicInteger attempts = new AtomicInteger();
    Impl arrived = success();
    final CompletableFuture<Impl> result = retry(attempt -> CompletableFuture.completedFuture(
        attempts.incrementAndGet() < 3 ? refused() : arrived)).start(refused(), null);
    settle();
    assertTrue(result.isDone());
    // The transfer completes with the connection that finally worked, not with the first failure.
    assertSame(arrived, result.join());
    assertEquals(List.of("retrying:1", "retrying:2", "retrying:3", "succeeded:3"), events);
  }

  @Test
  void destinationThatNeverAnswersExhaustsAttempts() {
    AtomicInteger attempts = new AtomicInteger();
    final CompletableFuture<Impl> result = retry(attempt -> {
      attempts.incrementAndGet();
      return CompletableFuture.completedFuture(refused());
    }).start(refused(), null);
    settle();
    assertTrue(result.isDone());
    assertEquals(CommittedOwnerRetry.MAX_ATTEMPTS, attempts.get());
    assertEquals("exhausted", events.get(events.size() - 1));
    // The last outcome is still reported, so the caller can act on the real failure.
    assertFalse(result.join().isSuccessful());
  }

  @Test
  void supersededRequestStopsWithoutConnectingAgain() {
    AtomicInteger attempts = new AtomicInteger();
    final CompletableFuture<Impl> result = retry(attempt -> {
      attempts.incrementAndGet();
      live.set(false); // Another connection request took this player while the attempt was running.
      return CompletableFuture.completedFuture(refused());
    }).start(refused(), null);
    settle();
    assertEquals(1, attempts.get());
    assertTrue(events.contains("abandoned"));
    assertFalse(events.contains("exhausted"));
    assertTrue(result.isDone());
  }

  @Test
  void transferNoLongerNamedByDurableRecordIsNotRetried() {
    AtomicInteger attempts = new AtomicInteger();
    owned.set(false); // A newer transfer superseded this one before the retry started.
    final CompletableFuture<Impl> result = retry(attempt -> {
      attempts.incrementAndGet();
      return CompletableFuture.completedFuture(success());
    }).start(refused(), null);
    settle();
    assertEquals(0, attempts.get());
    assertTrue(events.contains("abandoned"));
    assertTrue(result.isDone());
  }

  @Test
  void terminalRefusalIsNotWorthRetrying() {
    AtomicInteger attempts = new AtomicInteger();
    Impl cancelled = mock(Impl.class);
    when(cancelled.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED);
    when(cancelled.isSuccessful()).thenReturn(false);
    when(cancelled.getReasonComponent()).thenReturn(java.util.Optional.empty());
    final CompletableFuture<Impl> result = retry(attempt -> {
      attempts.incrementAndGet();
      return CompletableFuture.completedFuture(success());
    }).start(cancelled, null);
    settle();
    // A cancelled request answers the same way every time; spending attempts on it helps nobody.
    assertEquals(0, attempts.get());
    assertEquals(List.of("exhausted"), events);
    assertSame(cancelled, result.join());
  }

  @Test
  void droppedConnectionIsRecoverableButCancelledIsNot() {
    assertTrue(CommittedOwnerRetry.recoverable(null, new java.io.IOException("reset")));
    assertTrue(CommittedOwnerRetry.recoverable(null, null));
    assertTrue(CommittedOwnerRetry.recoverable(refused(), null));
    Impl cancelled = mock(Impl.class);
    when(cancelled.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED);
    assertFalse(CommittedOwnerRetry.recoverable(cancelled, null));
  }
}
