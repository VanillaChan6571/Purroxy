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

package com.velocitypowered.proxy.network;

import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.proxy.network.Connections.READ_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.velocitypowered.proxy.network.discovery.DiscoveryHandshakeDecoder;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ServerChannelInitializerTest {
  @Test
  void controlConnectionDiscardsMinecraftAndPluginInjectedHandlers() {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.pipeline().addLast(READ_TIMEOUT, new ReadTimeoutHandler(30000, TimeUnit.MILLISECONDS));
    channel.pipeline().addLast("purroxy-discriminator",
        new DiscoveryHandshakeDecoder(ch -> {
        }, ch -> {
        }));
    channel.pipeline().addLast(MINECRAFT_ENCODER, new ChannelInboundHandlerAdapter());
    channel.pipeline().addLast(Connections.HANDLER, new ChannelInboundHandlerAdapter());
    // ViaVersion and friends inject relative to the Minecraft handlers; they must go too.
    channel.pipeline().addLast("via-encoder", new ChannelInboundHandlerAdapter());

    ServerChannelInitializer.discardMinecraftPipeline(channel);

    assertEquals(List.of(READ_TIMEOUT, "purroxy-discriminator"),
        List.copyOf(channel.pipeline().toMap().keySet()));
    assertNotNull(channel.pipeline().get(READ_TIMEOUT));
    assertNull(channel.pipeline().get(MINECRAFT_ENCODER));
    channel.finishAndReleaseAll();
  }
}
