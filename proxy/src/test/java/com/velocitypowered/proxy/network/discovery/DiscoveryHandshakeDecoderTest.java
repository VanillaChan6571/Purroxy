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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DiscoveryHandshakeDecoderTest {
  @Test
  void fragmentedMagicSelectsControlAndPreservesFollowingBytes() {
    AtomicInteger control = new AtomicInteger();
    AtomicInteger minecraft = new AtomicInteger();
    EmbeddedChannel channel = new EmbeddedChannel(new DiscoveryHandshakeDecoder(
        ch -> control.incrementAndGet(), ch -> minecraft.incrementAndGet()));
    assertFalse(channel.writeInbound(Unpooled.copiedBuffer("PUR", StandardCharsets.US_ASCII)));
    assertNull(channel.readInbound());
    channel.writeInbound(Unpooled.copiedBuffer("ROXY\nTLS", StandardCharsets.US_ASCII));
    ByteBuf remaining = channel.readInbound();
    assertEquals("TLS", remaining.toString(StandardCharsets.US_ASCII));
    remaining.release();
    assertEquals(1, control.get());
    assertEquals(0, minecraft.get());
    channel.finishAndReleaseAll();
  }

  @Test
  void ordinaryHandshakeIsNotConsumedEvenWhenFirstByteMatches() {
    AtomicInteger minecraft = new AtomicInteger();
    EmbeddedChannel channel = new EmbeddedChannel(new DiscoveryHandshakeDecoder(
        ch -> {
          throw new AssertionError("Not a control connection");
        },
        ch -> minecraft.incrementAndGet()));
    byte[] packet = new byte[] {80, 0, 1, 2, 3};
    channel.writeInbound(Unpooled.wrappedBuffer(packet));
    ByteBuf remaining = channel.readInbound();
    assertArrayEquals(packet, ByteBufUtil.getBytes(remaining));
    remaining.release();
    assertEquals(1, minecraft.get());
    channel.finishAndReleaseAll();
  }
}
