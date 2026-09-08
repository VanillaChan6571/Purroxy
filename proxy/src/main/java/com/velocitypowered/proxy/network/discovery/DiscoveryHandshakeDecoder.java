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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/** Selects authenticated control transport without consuming ordinary Minecraft bytes. */
public final class DiscoveryHandshakeDecoder extends ByteToMessageDecoder {
  private static final byte[] MAGIC = "PURROXY\n".getBytes(StandardCharsets.US_ASCII);
  private final Consumer<Channel> control;
  private final Consumer<Channel> minecraft;

  public DiscoveryHandshakeDecoder(Consumer<Channel> control, Consumer<Channel> minecraft) {
    this.control = control;
    this.minecraft = minecraft;
  }

  @Override
  protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
    int available = Math.min(input.readableBytes(), MAGIC.length);
    for (int i = 0; i < available; i++) {
      if (input.getByte(input.readerIndex() + i) != MAGIC[i]) {
        minecraft.accept(context.channel());
        context.pipeline().remove(this); // Forward all buffered Minecraft bytes unchanged.
        return;
      }
    }
    if (available == MAGIC.length) {
      input.skipBytes(MAGIC.length);
      control.accept(context.channel());
      context.pipeline().remove(this); // Forward any coalesced TLS ClientHello bytes.
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
    context.close();
  }
}
