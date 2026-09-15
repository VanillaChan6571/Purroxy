/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

package com.velocitypowered.proxy.protocol;

import static com.google.common.collect.Iterables.getLast;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_11;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_12;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_12_1;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_12_2;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_13;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_14;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_14_2;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_15;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_16;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_16_2;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.backend.SeamlessProtocols;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.RemoveEntitiesPacket;
import com.velocitypowered.proxy.protocol.packet.SetObjectivePacket;
import com.velocitypowered.proxy.protocol.packet.SetPlayerTeamPacket;
import com.velocitypowered.proxy.protocol.packet.StatusPingPacket;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacketRegistryTest {

  private StateRegistry.PacketRegistry setupRegistry() {
    StateRegistry.PacketRegistry registry = new StateRegistry.PacketRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, StateRegistry.PLAY);
    registry.register(HandshakePacket.class, HandshakePacket::new,
        new StateRegistry.PacketMapping(0x01, MINECRAFT_1_8, null, false),
        new StateRegistry.PacketMapping(0x00, MINECRAFT_1_12, null, false),
        new StateRegistry.PacketMapping(0x00, MINECRAFT_1_15, MINECRAFT_1_16, false));
    return registry;
  }

  @Test
  void protocol764UsesItsOwnTeardownPacketIds() {
    StateRegistry.PacketRegistry.ProtocolRegistry registry = StateRegistry.PLAY
        .getProtocolRegistry(ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_20_2);
    assertEquals(0x40, registry.getPacketId(new RemoveEntitiesPacket(new int[] {1})));
    assertEquals(0x5A, registry.getPacketId(new SetObjectivePacket("objective")));
    assertEquals(0x5C, registry.getPacketId(new SetPlayerTeamPacket("team")));
  }

  @Test
  void protocol765UsesItsOwnTeardownPacketIds() {
    StateRegistry.PacketRegistry.ProtocolRegistry registry = StateRegistry.PLAY
        .getProtocolRegistry(ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_20_3);
    assertEquals(0x40, registry.getPacketId(new RemoveEntitiesPacket(new int[] {1})));
    assertEquals(0x5C, registry.getPacketId(new SetObjectivePacket("objective")));
    assertEquals(0x5E, registry.getPacketId(new SetPlayerTeamPacket("team")));
  }

  @Test
  void packetRegistryWorks() {
    StateRegistry.PacketRegistry registry = setupRegistry();
    MinecraftPacket packet = registry.getProtocolRegistry(MINECRAFT_1_12).createPacket(0);
    assertNotNull(packet, "Packet was not found in registry");
    assertEquals(HandshakePacket.class, packet.getClass(), "Registry returned wrong class");

    assertEquals(0, registry.getProtocolRegistry(MINECRAFT_1_12).getPacketId(packet),
        "Registry did not return the correct packet ID");
  }

  @Test
  void packetRegistryLinkingWorks() {
    StateRegistry.PacketRegistry registry = setupRegistry();
    MinecraftPacket packet = registry.getProtocolRegistry(MINECRAFT_1_12_1).createPacket(0);
    assertNotNull(packet, "Packet was not found in registry");
    assertEquals(HandshakePacket.class, packet.getClass(), "Registry returned wrong class");
    assertEquals(0, registry.getProtocolRegistry(MINECRAFT_1_12_1).getPacketId(packet),
        "Registry did not return the correct packet ID");
    assertEquals(0, registry.getProtocolRegistry(MINECRAFT_1_14_2).getPacketId(packet),
        "Registry did not return the correct packet ID");
    assertEquals(1, registry.getProtocolRegistry(MINECRAFT_1_11).getPacketId(packet),
        "Registry did not return the correct packet ID");
    assertNull(registry.getProtocolRegistry(MINECRAFT_1_14_2).createPacket(0x01),
        "Registry should return a null");
    assertNull(registry.getProtocolRegistry(MINECRAFT_1_16_2).createPacket(0),
        "Registry should return null");
  }

  @Test
  void failOnNoMappings() {
    StateRegistry.PacketRegistry registry = new StateRegistry.PacketRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, StateRegistry.PLAY);
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(HandshakePacket.class, HandshakePacket::new));
    assertThrows(IllegalArgumentException.class,
        () -> registry.getProtocolRegistry(ProtocolVersion.UNKNOWN)
                .getPacketId(new HandshakePacket()));
  }

  @Test
  void failOnWrongOrder() {
    StateRegistry.PacketRegistry registry = new StateRegistry.PacketRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, StateRegistry.PLAY);
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(HandshakePacket.class, HandshakePacket::new,
            new StateRegistry.PacketMapping(0x01, MINECRAFT_1_13, null, false),
            new StateRegistry.PacketMapping(0x00, MINECRAFT_1_8, null, false)));
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(HandshakePacket.class, HandshakePacket::new,
            new StateRegistry.PacketMapping(0x01, MINECRAFT_1_13, null, false),
            new StateRegistry.PacketMapping(0x01, MINECRAFT_1_13, null, false)));
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(HandshakePacket.class, HandshakePacket::new,
            new StateRegistry.PacketMapping(0x01, MINECRAFT_1_13, MINECRAFT_1_8, false)));
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(HandshakePacket.class, HandshakePacket::new,
            new StateRegistry.PacketMapping(0x01, MINECRAFT_1_8, MINECRAFT_1_14, false),
            new StateRegistry.PacketMapping(0x00, MINECRAFT_1_16, null, false)));
  }

  @Test
  void failOnDuplicate() {
    StateRegistry.PacketRegistry registry = new StateRegistry.PacketRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, StateRegistry.PLAY);
    registry.register(HandshakePacket.class, HandshakePacket::new,
        new StateRegistry.PacketMapping(0x00, MINECRAFT_1_8, null, false));
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(HandshakePacket.class, HandshakePacket::new,
            new StateRegistry.PacketMapping(0x01, MINECRAFT_1_12, null, false)));
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(StatusPingPacket.class, StatusPingPacket::new,
            new StateRegistry.PacketMapping(0x00, MINECRAFT_1_13, null, false)));
  }

  @Test
  void shouldNotFailWhenRegisterLatestProtocolVersion() {
    StateRegistry.PacketRegistry registry = new StateRegistry.PacketRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, StateRegistry.PLAY);
    assertDoesNotThrow(() -> registry.register(HandshakePacket.class, HandshakePacket::new,
        new StateRegistry.PacketMapping(0x00, MINECRAFT_1_8, null, false),
        new StateRegistry.PacketMapping(0x01, getLast(ProtocolVersion.SUPPORTED_VERSIONS),
            null, false)));
  }

  @Test
  void registrySuppliesCorrectPacketsByProtocol() {
    StateRegistry.PacketRegistry registry = new StateRegistry.PacketRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, StateRegistry.PLAY);
    registry.register(HandshakePacket.class, HandshakePacket::new,
        new StateRegistry.PacketMapping(0x00, MINECRAFT_1_12, null, false),
        new StateRegistry.PacketMapping(0x01, MINECRAFT_1_12_1, null, false),
        new StateRegistry.PacketMapping(0x02, MINECRAFT_1_13, null, false));
    assertEquals(HandshakePacket.class,
        registry.getProtocolRegistry(MINECRAFT_1_12).createPacket(0x00).getClass());
    assertEquals(HandshakePacket.class,
        registry.getProtocolRegistry(MINECRAFT_1_12_1).createPacket(0x01).getClass());
    assertEquals(HandshakePacket.class,
        registry.getProtocolRegistry(MINECRAFT_1_12_2).createPacket(0x01).getClass());
    assertEquals(HandshakePacket.class,
        registry.getProtocolRegistry(MINECRAFT_1_13).createPacket(0x02).getClass());
    assertEquals(HandshakePacket.class,
        registry.getProtocolRegistry(MINECRAFT_1_14_2).createPacket(0x02).getClass());
  }

  @Test
  void everySeamlessProtocolCanEncodeTheSourceStateTeardown() {
    // ClientPlaySessionHandler.clearSourceState writes these three with the client still in PLAY,
    // because a seamless switch sends no JoinGame or Respawn to rebuild the entity table and
    // scoreboard. A protocol that SeamlessProtocols admits but that has no id here does not
    // degrade quietly - it throws an encoder exception and drops the player mid-switch. So the
    // eligible band and these registrations have to be kept in step, and this is what says so.
    List<MinecraftPacket> teardown = List.of(
        new RemoveEntitiesPacket(new int[] {1}),
        new SetObjectivePacket("objective"),
        new SetPlayerTeamPacket("team"));
    for (ProtocolVersion version : ProtocolVersion.SUPPORTED_VERSIONS) {
      if (!SeamlessProtocols.canaryable(version)) {
        continue;
      }
      for (MinecraftPacket packet : teardown) {
        assertDoesNotThrow(
            () -> StateRegistry.PLAY
                .getProtocolRegistry(ProtocolUtils.Direction.CLIENTBOUND, version)
                .getPacketId(packet),
            packet.getClass().getSimpleName() + " has no clientbound PLAY id at " + version
                + " (protocol " + version.getProtocol() + ")");
      }
    }
  }
}
