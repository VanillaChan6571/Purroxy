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

package com.velocitypowered.proxy.network;

import static com.velocitypowered.proxy.network.Connections.FRAME_DECODER;
import static com.velocitypowered.proxy.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.proxy.network.Connections.LEGACY_PING_DECODER;
import static com.velocitypowered.proxy.network.Connections.LEGACY_PING_ENCODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.proxy.network.Connections.READ_TIMEOUT;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.network.limiter.SimpleBytesPerSecondLimiter;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.LegacyPingDecoder;
import com.velocitypowered.proxy.protocol.netty.LegacyPingEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.TimeUnit;

/**
 * Server channel initializer.
 */
@SuppressWarnings("WeakerAccess")
public class ServerChannelInitializer extends ChannelInitializer<Channel> {

  private final VelocityServer server;

  public ServerChannelInitializer(final VelocityServer server) {
    this.server = server;
  }

  @Override
  protected void initChannel(final Channel ch) {
    ch.pipeline().addLast(READ_TIMEOUT,
        new ReadTimeoutHandler(this.server.getConfiguration().getReadTimeout(), TimeUnit.MILLISECONDS));
    if (this.server.getConfiguration().isProxyProtocol()) {
      ch.pipeline().addFirst(new HAProxyMessageDecoder());
    }
    com.velocitypowered.proxy.network.discovery.DiscoveryService discovery = server.getDiscovery();
    if (discovery != null) {
      // The Minecraft pipeline is built up front even though control connections share this
      // listener: plugins that wrap this initializer inject relative to the Minecraft handlers
      // as soon as it returns (ViaVersion adds before minecraft-encoder), so deferring them
      // fails every player connection. A control connection discards them below instead.
      ch.pipeline().addLast("purroxy-discriminator",
          new com.velocitypowered.proxy.network.discovery.DiscoveryHandshakeDecoder(
              channel -> {
                discardMinecraftPipeline(channel);
                discovery.initialize(channel);
              }, channel -> {
              }));
    }
    initializeMinecraft(ch);
  }

  /**
   * Strips the Minecraft pipeline, and any plugin handlers injected around it, once a connection
   * turns out to speak the discovery control protocol rather than Minecraft.
   */
  static void discardMinecraftPipeline(final Channel ch) {
    ch.pipeline().toMap().forEach((name, handler) -> {
      if (!READ_TIMEOUT.equals(name)
          && !(handler instanceof HAProxyMessageDecoder)
          && !(handler instanceof com.velocitypowered.proxy.network.discovery.DiscoveryHandshakeDecoder)
          && ch.pipeline().context(handler) != null) {
        ch.pipeline().remove(handler);
      }
    });
  }

  private void initializeMinecraft(final Channel ch) {
    ch.pipeline()
        .addLast(LEGACY_PING_DECODER, new LegacyPingDecoder())
        .addLast(FRAME_DECODER, new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(LEGACY_PING_ENCODER, LegacyPingEncoder.INSTANCE)
        .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
        .addLast(MINECRAFT_DECODER, new MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(MINECRAFT_ENCODER, new MinecraftEncoder(ProtocolUtils.Direction.CLIENTBOUND));

    final MinecraftConnection connection = new MinecraftConnection(ch, this.server);
    connection.setActiveSessionHandler(StateRegistry.HANDSHAKE,
        new HandshakeSessionHandler(connection, this.server));
    ch.pipeline().addLast(Connections.HANDLER, connection);

    VelocityConfiguration.PacketLimiterConfig packetLimiterConfig =
        server.getConfiguration().getPacketLimiterConfig();
    int configuredInterval = packetLimiterConfig.interval();
    int configuredPacketsPerSecond = packetLimiterConfig.pps();
    int configuredBytes = packetLimiterConfig.bytes();

    if (configuredInterval > 0 && (configuredBytes > 0 || configuredPacketsPerSecond > 0)) {
      ch.pipeline().get(MinecraftVarintFrameDecoder.class).setPacketLimiter(
          new SimpleBytesPerSecondLimiter(configuredPacketsPerSecond, configuredBytes, configuredInterval)
      );
    }
  }
}
