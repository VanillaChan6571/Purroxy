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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscoveryLifecycleTest {
  @Test
  void wakesOneSpareAndFallbackExcludesPreviouslyAttemptedMembers() {
    VelocityServer proxy = mock(VelocityServer.class);
    Map<String, RegisteredServer> physical = new HashMap<>();
    when(proxy.getServer(anyString())).thenAnswer(call -> Optional.ofNullable(physical.get(call.getArgument(0))));
    when(proxy.registerServer(any(ServerInfo.class))).thenAnswer(call -> {
      ServerInfo info = call.getArgument(0);
      RegisteredServer backend = mock(RegisteredServer.class);
      when(backend.getServerInfo()).thenReturn(info);
      when(backend.getPlayersConnected()).thenReturn(List.of());
      physical.put(info.getName(), backend);
      return backend;
    });
    String fingerprint = "ab".repeat(32);
    var identity = new DiscoveryConfiguration.Identity(fingerprint, "localhost", 25565, Set.of("hub"));
    var config = new DiscoveryConfiguration(Set.of("hub"), Map.of("a", identity, "b", identity),
        null, List.of("hub"), Map.of("play.example.com", List.of("hub")));
    EmbeddedChannel first = new EmbeddedChannel();
    EmbeddedChannel second = new EmbeddedChannel();
    try (DiscoveryService service = new DiscoveryService(proxy, config)) {
      var a = service.register(new BackendResume("a", UUID.randomUUID(), "localhost", 25565,
          "hub", "none", 50, 80, "us-west"), fingerprint, first);
      var b = service.register(new BackendResume("b", UUID.randomUUID(), "localhost", 25565,
          "hub", "none", 50, 80, "us-west"), fingerprint, second);
      service.heartbeat(a, heartbeat(0, "READY", 35));
      service.heartbeat(b, heartbeat(0, "SLEEPING", 0));
      service.maintenance();
      assertNull(second.readOutbound());
      service.heartbeat(a, heartbeat(1, "READY", 36));
      service.maintenance();
      String wake = second.readOutbound();
      assertEquals("wake", JsonParser.parseString(wake).getAsJsonObject().get("type").getAsString());
      service.maintenance();
      assertNull(second.readOutbound());
      assertFalse(service.allows("b"));
      service.heartbeat(b, heartbeat(1, "READY", 0));
      Player player = mock(Player.class);
      UUID playerId = UUID.randomUUID();
      when(player.getUniqueId()).thenReturn(playerId);
      assertEquals(physical.get("b"), service.nextFallback(player, "PLAY.EXAMPLE.COM", Set.of("a")).orElseThrow());
      service.cancel(playerId);
      assertTrue(service.nextFallback(player, "", Set.of("a", "b")).isEmpty());
    } finally {
      first.finishAndReleaseAll();
      second.finishAndReleaseAll();
    }
  }

  private static JsonObject heartbeat(long sequence, String state, int players) {
    JsonObject message = new JsonObject();
    message.addProperty("sequence", sequence);
    message.addProperty("state", state);
    message.addProperty("players", players);
    return message;
  }
}
