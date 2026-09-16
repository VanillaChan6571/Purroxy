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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.backend.SeamlessProtocols;
import com.velocitypowered.proxy.protocol.packet.ClientboundPostEffectsPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

class Protocol263Test {

  @Test
  void protocol777RegistersWithoutChangingThePreviousVersion() {
    assertEquals(ProtocolVersion.MINECRAFT_26_3, ProtocolVersion.getProtocolVersion(777));
    var current = StateRegistry.PLAY.getProtocolRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_26_3);
    var previous = StateRegistry.PLAY.getProtocolRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_26_2);
    assertEquals(0x32, current.getPacketId(new JoinGamePacket()));
    assertEquals(0x54, current.getPacketId(new RespawnPacket()));
    assertEquals(0x31, previous.getPacketId(new JoinGamePacket()));
    assertEquals(0x52, previous.getPacketId(new RespawnPacket()));
    assertInstanceOf(ClientboundPostEffectsPacket.class, current.createPacket(0x53));
    assertInstanceOf(ClientboundPostEffectsPacket.class, StateRegistry.CONFIG.getProtocolRegistry(
        ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_26_3).createPacket(0x0A));
    assertFalse(SeamlessProtocols.canaryable(ProtocolVersion.MINECRAFT_26_3));
    assertFalse(SeamlessProtocols.eligible(null, ProtocolVersion.MINECRAFT_26_3));
  }

  @Test
  void oversizedPostEffectsAreRejectedBeforeReadingTheirContents() {
    ByteBuf data = Unpooled.buffer();
    try {
      ProtocolUtils.writeVarInt(data, 1025);
      data.writeZero(1025);
      assertThrows(DecoderException.class,
          () -> new ClientboundPostEffectsPacket().decode(data,
              ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_26_3));
    } finally {
      data.release();
    }
  }
}
