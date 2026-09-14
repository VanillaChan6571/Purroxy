/*
 * Copyright (C) 2026 Velocity Contributors x Neko Network
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

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Reaches the destination a committed transfer already handed a player to.
 *
 * <p>Ownership stops being negotiable at the commit: the proxy's store moves a committed transfer
 * only to COMPLETE and the destination's journal only to ACTIVATED. A failed arrival is therefore
 * never grounds to pick a different server, only grounds to try the recorded one again. Everything
 * here is injected so the retry can be driven without a live proxy.
 */
final class CommittedOwnerRetry {

  static final int MAX_ATTEMPTS = 3;
  static final long BACKOFF_MILLIS = 2000;

  /** Reports each decision, so a retry chain leaves a trail rather than a single terminal line. */
  interface Outcome {
    void retrying(int attempt, String cause);

    void succeeded(int attempt);

    /** The request no longer owns this player, so the retry stops without claiming the session. */
    void abandoned(String why);

    void exhausted(String cause);
  }

  private final ScheduledExecutorService scheduler;
  private final IntFunction<CompletableFuture<Impl>> connector;
  private final BooleanSupplier live;
  private final BooleanSupplier owned;
  private final Outcome outcome;
  private final CompletableFuture<Impl> result = new CompletableFuture<>();

  CommittedOwnerRetry(ScheduledExecutorService scheduler,
      IntFunction<CompletableFuture<Impl>> connector, BooleanSupplier live, BooleanSupplier owned,
      Outcome outcome) {
    this.scheduler = scheduler;
    this.connector = connector;
    this.live = live;
    this.owned = owned;
    this.outcome = outcome;
  }

  /**
   * Whether a failure is worth another attempt. A destination that is restarting, still preparing or
   * that dropped the login answers differently each time; a cancelled or redundant request answers
   * the same way forever and retrying it only delays the player.
   */
  static boolean recoverable(@Nullable Impl result, @Nullable Throwable exception) {
    if (exception != null || result == null) {
      return true;
    }
    return result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED;
  }

  /** Renders a failure by stage, so a report names what happened rather than that something did. */
  static String describe(@Nullable Impl result, @Nullable Throwable exception) {
    if (exception != null) {
      Throwable cause = exception instanceof CompletionException && exception.getCause() != null
          ? exception.getCause() : exception;
      return cause.getClass().getSimpleName()
          + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
    if (result == null) {
      return "no connection result was produced";
    }
    return result.getStatus() + result.getReasonComponent()
        .map(reason -> ": " + ConnectedPlayer.PASS_THRU_TRANSLATE.serialize(reason)).orElse("");
  }

  /**
   * Runs the chain, starting from the failure that triggered it, and completes with whatever the
   * player ends up connected to. The caller composes this into the original connection future so a
   * request does not report an outcome while its own recovery is still running.
   */
  CompletableFuture<Impl> start(@Nullable Impl lastResult, @Nullable Throwable lastException) {
    if (!recoverable(lastResult, lastException)) {
      // A terminal refusal answers identically every time; spending attempts on it helps nobody.
      outcome.exhausted(describe(lastResult, lastException));
      complete(lastResult, lastException);
      return result;
    }
    run(1, describe(lastResult, lastException), lastResult, lastException);
    return result;
  }

  private void run(int attempt, String cause, @Nullable Impl lastResult,
      @Nullable Throwable lastException) {
    // Checked before every attempt rather than once: a newer connection request, or a transfer that
    // has been superseded, must not have a stale retry connect underneath it.
    if (!live.getAsBoolean()) {
      outcome.abandoned("the player or this connection request was superseded");
      complete(lastResult, lastException);
      return;
    }
    if (!owned.getAsBoolean()) {
      outcome.abandoned("the durable record no longer names this destination for this transfer");
      complete(lastResult, lastException);
      return;
    }
    if (attempt > MAX_ATTEMPTS) {
      outcome.exhausted(cause);
      complete(lastResult, lastException);
      return;
    }
    outcome.retrying(attempt, cause);
    CompletableFuture<Impl> connecting;
    try {
      connecting = connector.apply(attempt);
    } catch (RuntimeException failure) {
      schedule(attempt, describe(null, failure), null, failure);
      return;
    }
    connecting.whenComplete((attemptResult, attemptFailure) -> {
      if (attemptResult != null && attemptResult.isSuccessful()) {
        outcome.succeeded(attempt);
        result.complete(attemptResult);
        return;
      }
      String why = describe(attemptResult, attemptFailure);
      if (!recoverable(attemptResult, attemptFailure)) {
        outcome.exhausted(why);
        complete(attemptResult, attemptFailure);
        return;
      }
      schedule(attempt, why, attemptResult, attemptFailure);
    });
  }

  private void schedule(int attempt, String cause, @Nullable Impl lastResult,
      @Nullable Throwable lastException) {
    // Widening delay: the common recoverable case is a destination that has not finished starting.
    scheduler.schedule(() -> run(attempt + 1, cause, lastResult, lastException),
        attempt * BACKOFF_MILLIS, TimeUnit.MILLISECONDS);
  }

  private void complete(@Nullable Impl lastResult, @Nullable Throwable lastException) {
    if (lastException != null) {
      result.completeExceptionally(lastException);
    } else {
      result.complete(lastResult);
    }
  }
}
