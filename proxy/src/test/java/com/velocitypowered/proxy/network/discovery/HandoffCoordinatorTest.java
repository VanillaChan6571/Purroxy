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

package com.velocitypowered.proxy.network.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandoffCoordinatorTest {
  @TempDir Path directory;
  private final UUID player = UUID.randomUUID();

  @Test
  void commitsBeforeSwitchAndReleasesOnlyAfterConnectionSuccess() throws Exception {
    FakeBackends backends = new FakeBackends();
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      HandoffCoordinator.Ticket ticket = coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> true, Runnable::run)
          .get(5, TimeUnit.SECONDS);
      assertNotNull(ticket);
      assertEquals(List.of("hub-1:export", "hub-2:stage", "hub-1:fence", "hub-2:commit"), backends.mutations);
      assertEquals("FENCED", backends.phase("hub-1"));
      assertEquals(Optional.of("hub-2"), coordinator.recoveryOwner(player));
      assertEquals(HandoffStore.Phase.COMMITTED, new HandoffStore(directory).get(player).phase());
      coordinator.finish(ticket, true).get(5, TimeUnit.SECONDS);
      assertEquals("RELEASED", backends.phase("hub-1"));
      assertTrue(coordinator.recoveryOwner(player).isEmpty());
      assertEquals(HandoffStore.Phase.COMPLETE, new HandoffStore(directory).get(player).phase());
    }
  }

  @Test
  void corruptProxyJournalFailsClosed() throws Exception {
    java.nio.file.Files.writeString(directory.resolve(player + ".json"), "{broken");
    assertThrows(IOException.class, () -> new HandoffStore(directory));
  }

  @Test
  void committedJournalRejectsRollbackAndReplacement() throws Exception {
    HandoffStore journal = new HandoffStore(directory);
    HandoffStore.Transfer preparing = journal.begin(player, "hub-1", "hub-2", System.currentTimeMillis() + 25000);
    HandoffStore.Transfer staged = journal.update(preparing.withPosition(new HubPosition("hub", "v1", 1, 80, 2, 0, 0, 0, 0, 0)));
    HandoffStore.Transfer committed = journal.update(staged.withPhase(HandoffStore.Phase.COMMITTED));
    assertThrows(IllegalStateException.class, () -> journal.update(committed.withPhase(HandoffStore.Phase.ABORTED)));
    assertThrows(IllegalStateException.class, () -> journal.begin(player, "hub-2", "hub-1", System.currentTimeMillis() + 25000));
    assertEquals(committed, new HandoffStore(directory).get(player));
  }

  @Test
  void failedDestinationConnectionKeepsSourceFencedAndDoesNotScheduleRelease() throws Exception {
    FakeBackends backends = new FakeBackends();
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      HandoffCoordinator.Ticket ticket = coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> true, Runnable::run)
          .get(5, TimeUnit.SECONDS);
      coordinator.finish(ticket, false).get(5, TimeUnit.SECONDS);
      coordinator.retryReleases().get(5, TimeUnit.SECONDS);
      assertEquals("FENCED", backends.phase("hub-1"));
      assertTrue(coordinator.requiresRecovery(player));
      assertFalse(backends.mutations.contains("hub-1:release"));
    }
  }

  @Test
  void sourceSessionReplacementBeforeFenceAbortsTheTransaction() throws Exception {
    FakeBackends backends = new FakeBackends();
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> {
            HandoffCoordinator.Peer old = backends.peers.get("hub-1");
            backends.peers.put("hub-1", new HandoffCoordinator.Peer(old.name(), UUID.randomUUID(), old.capabilities()));
            return true;
          }, Runnable::run).get(5, TimeUnit.SECONDS));
      assertEquals("ABORTED", backends.phase("hub-1"));
      assertFalse(backends.mutations.contains("hub-1:fence"));
      assertFalse(backends.mutations.contains("hub-2:commit"));
    }
  }

  @Test
  void lostReleaseAcknowledgmentIsRetriedWithoutReconnectingThePlayer() throws Exception {
    FakeBackends backends = new FakeBackends();
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      HandoffCoordinator.Ticket ticket = coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> true, Runnable::run)
          .get(5, TimeUnit.SECONDS);
      backends.loseAck = "release";
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.finish(ticket, true).get(5, TimeUnit.SECONDS));
      assertTrue(coordinator.requiresRecovery(player));
      coordinator.retryReleases().get(5, TimeUnit.SECONDS);
      assertFalse(coordinator.requiresRecovery(player));
      assertEquals(HandoffStore.Phase.COMPLETE, new HandoffStore(directory).get(player).phase());
    }
  }

  @Test
  void disabledHandoffStillResolvesAnOldAbortBeforeAllowingOrdinaryRouting() throws Exception {
    HandoffStore journal = new HandoffStore(directory);
    journal.begin(player, "hub-1", "hub-2", System.currentTimeMillis() + 25000);
    FakeBackends backends = new FakeBackends();
    backends.peers.remove("hub-1");
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.prepare(player, "hub-1", "hub-2", "off", () -> true, Runnable::run).get(5, TimeUnit.SECONDS));
      assertTrue(coordinator.requiresRecovery(player));
      assertTrue(backends.mutations.isEmpty());
    }
  }

  @Test
  void lostFenceAcknowledgmentRollsBackBothBackendsBeforeAllowingSource() throws Exception {
    FakeBackends backends = new FakeBackends();
    backends.loseAck = "fence";
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> true, Runnable::run).get(5, TimeUnit.SECONDS));
      assertEquals("ABORTED", backends.phase("hub-1"));
      assertEquals("ABORTED", backends.phase("hub-2"));
      assertFalse(backends.mutations.contains("hub-2:commit"));
      assertFalse(coordinator.requiresRecovery(player));
      assertEquals(HandoffStore.Phase.ABORTED, new HandoffStore(directory).get(player).phase());
    }
  }

  @Test
  void lostCommitAcknowledgmentNeverUnfencesSourceAndRecoversAfterRestart() throws Exception {
    FakeBackends backends = new FakeBackends();
    backends.loseAck = "commit";
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> true, Runnable::run).get(5, TimeUnit.SECONDS));
      assertEquals("FENCED", backends.phase("hub-1"));
      assertEquals("COMMITTED", backends.phase("hub-2"));
      assertFalse(backends.mutations.stream().anyMatch(action -> action.endsWith(":abort")));
    }
    try (HandoffCoordinator recovered = new HandoffCoordinator(directory, backends)) {
      assertEquals(Optional.of("hub-2"), recovered.recoveryOwner(player));
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> recovered.prepare(player, null, "hub-1", "normal", () -> true, Runnable::run).get(5, TimeUnit.SECONDS));
      HandoffCoordinator.Ticket ticket = recovered.prepare(player, null, "hub-2", "normal", () -> true, Runnable::run)
          .get(5, TimeUnit.SECONDS);
      recovered.finish(ticket, true).get(5, TimeUnit.SECONDS);
      assertFalse(recovered.requiresRecovery(player));
    }
  }

  @Test
  void cancellationAfterStagingAbortsBeforeFenceOrCommit() throws Exception {
    FakeBackends backends = new FakeBackends();
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.prepare(player, "hub-1", "hub-2", "normal", () -> false, Runnable::run).get(5, TimeUnit.SECONDS));
      assertEquals("ABORTED", backends.phase("hub-1"));
      assertFalse(backends.mutations.contains("hub-1:fence"));
      assertFalse(backends.mutations.contains("hub-2:commit"));
    }
  }

  @Test
  void mismatchedReplicasFallBackAndRequiredSeamlessRejectsBeforeExport() throws Exception {
    FakeBackends backends = new FakeBackends();
    backends.peers.put("hub-2", new HandoffCoordinator.Peer("hub-2", UUID.randomUUID(),
        new HandoffCapabilities(1, "hub-position", "hub", "other-revision", false)));
    try (HandoffCoordinator coordinator = new HandoffCoordinator(directory, backends)) {
      assertNull(coordinator.prepare(player, "hub-1", "hub-2", "seamless-preferred", () -> true, Runnable::run).get());
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> coordinator.prepare(player, "hub-1", "hub-2", "seamless-required", () -> true, Runnable::run).get());
      assertTrue(backends.mutations.isEmpty());
    }
  }

  private static final class FakeBackends implements HandoffCoordinator.Transport {
    private final Map<String, HandoffCoordinator.Peer> peers = new HashMap<>();
    private final Map<String, JsonObject> entries = new HashMap<>();
    private final List<String> mutations = new ArrayList<>();
    private String loseAck = "";

    private FakeBackends() {
      HandoffCapabilities capabilities = new HandoffCapabilities(1, "hub-position", "hub", "v1", false);
      peers.put("hub-1", new HandoffCoordinator.Peer("hub-1", UUID.randomUUID(), capabilities));
      peers.put("hub-2", new HandoffCoordinator.Peer("hub-2", UUID.randomUUID(), capabilities));
    }

    String phase(String backend) {
      return entries.get(backend).get("phase").getAsString();
    }

    @Override
    public Optional<HandoffCoordinator.Peer> current(String name) {
      return Optional.ofNullable(peers.get(name));
    }

    @Override
    public CompletableFuture<JsonObject> request(HandoffCoordinator.Peer peer, JsonObject request) {
      String action = request.get("action").getAsString();
      if (!action.equals("status")) {
        mutations.add(peer.name() + ":" + action);
      }
      JsonObject entry = entries.get(peer.name());
      if (action.equals("export") || action.equals("stage")) {
        entry = request.deepCopy();
        entry.addProperty("role", action.equals("export") ? "SOURCE" : "DESTINATION");
        entry.addProperty("phase", action.equals("export") ? "EXPORTED" : "STAGED");
        if (action.equals("export")) {
          entry.add("snapshot", new Gson().toJsonTree(new HubPosition("hub", "v1", 12, 80, -4, 90, 0, 0, 0, 0)));
        }
        entries.put(peer.name(), entry);
      } else if (entry != null) {
        switch (action) {
          case "fence" -> entry.addProperty("phase", "FENCED");
          case "commit" -> entry.addProperty("phase", "COMMITTED");
          case "abort" -> entry.addProperty("phase", "ABORTED");
          case "release" -> entry.addProperty("phase", "RELEASED");
          default -> { }
        }
      }
      if (loseAck.equals(action)) {
        loseAck = "";
        return CompletableFuture.failedFuture(new IOException("Injected lost acknowledgment"));
      }
      JsonObject reply = new JsonObject();
      reply.addProperty("status", entry == null ? "NOT_FOUND" : "OK");
      if (entry != null) {
        reply.add("entry", entry.deepCopy());
      }
      return CompletableFuture.completedFuture(reply);
    }
  }
}
