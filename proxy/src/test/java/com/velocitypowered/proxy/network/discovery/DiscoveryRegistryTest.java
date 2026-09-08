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

package com.velocitypowered.proxy.network.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DiscoveryRegistryTest {
  private final AtomicLong clock = new AtomicLong();
  private final DiscoveryRegistry registry = new DiscoveryRegistry(Set.of("hub"),
      Duration.ofSeconds(20), clock::get);

  private BackendResume resume(String group) {
    return new BackendResume("hub-1", UUID.randomUUID(), "localhost", 25565,
        group, "none", 50, 80, "us-west");
  }

  @Test
  void unknownGroupsAndDuplicateIdentitiesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> registry.register(resume("missing")));
    registry.register(resume("hub"));
    assertThrows(IllegalStateException.class, () -> registry.register(resume("hub")));
  }

  @Test
  void slotsPreventOverbookingAndAreReconciledWithoutDoubleCounting() {
    var session = registry.register(resume("hub"));
    assertTrue(registry.heartbeat(session, 0, DiscoveryRegistry.State.READY, 79, Set.of()));
    var slot = registry.reserve("hub", "us-west").orElseThrow();
    assertTrue(registry.canCommit(slot));
    assertTrue(registry.reserve("hub", "").isEmpty());
    assertTrue(registry.heartbeat(session, 1, DiscoveryRegistry.State.READY, 80,
        Set.of(slot.token())));
    assertFalse(registry.canCommit(slot));
    assertTrue(registry.reserve("hub", "").isEmpty());
  }

  @Test
  void expiredSessionsCannotReviveAndOldDisconnectsCannotInvalidateReplacement() {
    var session = registry.register(resume("hub"));
    registry.heartbeat(session, 0, DiscoveryRegistry.State.READY, 0, Set.of());
    clock.set(Duration.ofSeconds(20).toNanos());
    assertTrue(registry.reserve("hub", "").isEmpty());
    assertFalse(registry.heartbeat(session, 1, DiscoveryRegistry.State.READY, 0, Set.of()));
    assertTrue(registry.retire(session, true));
    var replacement = registry.register(resume("hub"));
    registry.heartbeat(replacement, 0, DiscoveryRegistry.State.READY, 0, Set.of());
    registry.disconnected(session);
    assertTrue(registry.reserve("hub", "").isPresent());
  }

  @Test
  void backendMayCountAnAdmittedPlayerBeforeProxyCompletesTheJoin() {
    var session = registry.register(resume("hub"));
    registry.heartbeat(session, 0, DiscoveryRegistry.State.READY, 79, Set.of());
    var slot = registry.reservePhysical("hub-1").orElseThrow();
    registry.heartbeat(session, 1, DiscoveryRegistry.State.READY, 80, Set.of());
    assertTrue(registry.canCommit(slot));
    assertTrue(registry.reservePhysical("hub-1").isEmpty());
  }

  @Test
  void observedConnectionsPreventStaleHeartbeatsFromReopeningFullCapacity() {
    var session = registry.register(resume("hub"));
    registry.heartbeat(session, 0, DiscoveryRegistry.State.READY, 0, Set.of());
    registry.observeConnections(session, 80);
    assertTrue(registry.reservePhysical("hub-1").isEmpty());
    registry.heartbeat(session, 1, DiscoveryRegistry.State.READY, 0, Set.of());
    assertTrue(registry.reserve("hub", "").isEmpty());
    registry.observeConnections(session, 79);
    assertTrue(registry.reservePhysical("hub-1").isPresent());
    assertTrue(registry.reservePhysical("hub-1").isEmpty());
  }

  @Test
  void concurrentReservationsNeverExceedBackendHardCapacity() {
    var session = registry.register(resume("hub"));
    registry.heartbeat(session, 0, DiscoveryRegistry.State.READY, 0, Set.of());
    var requests = java.util.stream.IntStream.range(0, 200)
        .mapToObj(i -> java.util.concurrent.CompletableFuture.supplyAsync(
            () -> registry.reserve("hub", ""))).toList();
    long accepted = requests.stream().map(java.util.concurrent.CompletableFuture::join)
        .filter(java.util.Optional::isPresent).count();
    assertEquals(80, accepted);
  }

  @Test
  void sameProcessReauthenticatesWithoutEvacuatingExistingPlayers() {
    BackendResume resume = resume("hub");
    var original = registry.register(resume);
    registry.heartbeat(original, 0, DiscoveryRegistry.State.READY, 12, Set.of());
    registry.disconnected(original);
    var replacement = registry.register(resume);
    registry.disconnected(original);
    assertTrue(registry.heartbeat(replacement, 0, DiscoveryRegistry.State.READY, 12, Set.of()));
    assertTrue(registry.reserve("hub", "").isPresent());
    assertFalse(registry.heartbeat(original, 1, DiscoveryRegistry.State.DRAINING, 0, Set.of()));
  }

  @Test
  void drainInvalidatesSlotsAndStaleHeartbeatsCannotUndoIt() {
    var session = registry.register(resume("hub"));
    registry.heartbeat(session, 0, DiscoveryRegistry.State.READY, 0, Set.of());
    var slot = registry.reserve("hub", "").orElseThrow();
    registry.heartbeat(session, 2, DiscoveryRegistry.State.DRAINING, 0, Set.of());
    assertFalse(registry.heartbeat(session, 1, DiscoveryRegistry.State.READY, 0, Set.of()));
    assertFalse(registry.canCommit(slot));
    assertFalse(registry.retire(session, true));
    registry.release(slot);
    assertTrue(registry.retire(session, true));
  }
}
