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
 * Removes a scoreboard objective from a client. Registered encode-only: the proxy writes the removal form during a seamless handoff, when no JoinGame rebuilds the client's scoreboard, and never decodes a backend's own copy.
 */
public class SetObjectivePacket implements MinecraftPacket {

  /** The only method this proxy ever writes; add and change carry payloads it has no business in. */
  private static final int METHOD_REMOVE = 1;

  private String objective = "";

  public SetObjectivePacket() {}

  public SetObjectivePacket(String objective) {
    this.objective = objective;
  }

  @Override
  public void decode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion version) {
    throw new UnsupportedOperationException("SetObjectivePacket is registered encode-only");
  }

  @Override
  public void encode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion version) {
    ProtocolUtils.writeString(buf, objective);
    buf.writeByte(METHOD_REMOVE);
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    // Unreachable: an encode-only mapping means this is never decoded from a connection.
    return false;
  }

  @Override
  public String toString() {
    return "SetObjectivePacket{objective=" + objective + ", method=remove}";
  }
}
