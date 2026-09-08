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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DiscoveryAdmissionTest {
  private static JsonObject heartbeat(long sequence, String state, int players) {
    JsonObject message = new JsonObject();
    message.addProperty("sequence", sequence);
    message.addProperty("state", state);
    message.addProperty("players", players);
    return message;
  }

  @Test
  void reservationsCoverDirectConnectionsAndDrainInvalidatesTheirCommit() {
    VelocityServer proxy = mock(VelocityServer.class);
    RegisteredServer backend = mock(RegisteredServer.class);
    AtomicReference<RegisteredServer> current = new AtomicReference<>();
    when(proxy.getServer("hub-1")).thenAnswer(call -> Optional.ofNullable(current.get()));
    when(proxy.registerServer(any(ServerInfo.class))).thenAnswer(call -> {
      when(backend.getServerInfo()).thenReturn(call.getArgument(0));
      current.set(backend);
      return backend;
    });
    Player existingPlayer = mock(Player.class);
    when(backend.getPlayersConnected()).thenReturn(List.of(existingPlayer));
    String fingerprint = "ab".repeat(32);
    DiscoveryConfiguration configuration = new DiscoveryConfiguration(Set.of("hub"),
        Map.of("hub-1", new DiscoveryConfiguration.Identity(fingerprint, "localhost", 25565,
            Set.of("hub"))), null);
    EmbeddedChannel channel = new EmbeddedChannel();
    try (DiscoveryService service = new DiscoveryService(proxy, configuration)) {
      var session = service.register(new BackendResume("hub-1", UUID.randomUUID(), "localhost",
          25565, "hub", "none", 50, 80, "us-west"), fingerprint, channel);
      service.heartbeat(session, heartbeat(0, "READY", 79));
      UUID first = UUID.randomUUID();
      final var ticket = service.begin(first, backend).orElseThrow();
      assertTrue(service.canComplete(first, "hub-1"));
      assertTrue(service.begin(UUID.randomUUID(), backend).isEmpty());
      service.heartbeat(session, heartbeat(1, "DRAINING", 79));
      assertFalse(service.canComplete(first, "hub-1"));
      service.finish(ticket);
      assertFalse(service.canComplete(first, "hub-1"));

      service.heartbeat(session, heartbeat(2, "READY", 79));
      Player player = mock(Player.class);
      when(player.getUniqueId()).thenReturn(UUID.randomUUID());
      when(player.getCurrentServer()).thenReturn(Optional.empty());
      ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
      ConnectionRequestBuilder.Result cancelled = mock(ConnectionRequestBuilder.Result.class);
      when(cancelled.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED);
      when(request.connect()).thenReturn(CompletableFuture.completedFuture(cancelled));
      when(player.createConnectionRequest(backend)).thenAnswer(call -> {
        assertTrue(service.begin(UUID.randomUUID(), backend).isEmpty());
        return request;
      });
      assertFalse(service.connectGroup(player, "Hub", "US-West").join());
      assertTrue(service.begin(UUID.randomUUID(), backend).isPresent());

      RegisteredServer stale = mock(RegisteredServer.class);
      ServerInfo staleInfo = backend.getServerInfo();
      when(stale.getServerInfo()).thenReturn(staleInfo);
      assertTrue(service.begin(UUID.randomUUID(), stale).isEmpty());
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
