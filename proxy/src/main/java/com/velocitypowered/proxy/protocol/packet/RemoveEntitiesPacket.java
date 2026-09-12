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

package com.velocitypowered.proxy.protocol.packet;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;

/**
 * Removes entities from a client's own table. Registered encode-only: the proxy writes this during
 * a seamless handoff, when the client keeps its world and nothing else would clear the entities the
 * source had shown it, and never decodes a backend's own copy.
 */
public class RemoveEntitiesPacket implements MinecraftPacket {

  private int[] entityIds = new int[0];

  public RemoveEntitiesPacket() {}

  public RemoveEntitiesPacket(int[] entityIds) {
    this.entityIds = entityIds;
  }

  @Override
  public void decode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion version) {
    throw new UnsupportedOperationException("RemoveEntitiesPacket is registered encode-only");
  }

  @Override
  public void encode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion version) {
    ProtocolUtils.writeVarInt(buf, entityIds.length);
    for (int entityId : entityIds) {
      ProtocolUtils.writeVarInt(buf, entityId);
    }
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    // Unreachable: an encode-only mapping means this is never decoded from a connection.
    return false;
  }

  public int[] getEntityIds() {
    return entityIds;
  }

  @Override
  public String toString() {
    return "RemoveEntitiesPacket{entities=" + entityIds.length + '}';
  }
}
