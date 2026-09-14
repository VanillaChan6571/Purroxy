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

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Captures a normal backend negotiation without changing its existing packet handling. */
final class ObservedConfigSessionHandler implements MinecraftSessionHandler {
  private static final Logger logger = LogManager.getLogger(ObservedConfigSessionHandler.class);
  private final MinecraftSessionHandler delegate;
  private final VelocityServerConnection connection;
  // Shadow comparison only: this never writes to either connection and never changes the switch.
  private SeamlessConfiguration shadow;
  private String shadowFailure;
  private int shadowIndex;

  ObservedConfigSessionHandler(MinecraftSessionHandler delegate, VelocityServerConnection connection) {
    this.delegate = delegate;
    this.connection = connection;
  }

  @Override
  public void activated() {
    ProtocolVersion negotiated = connection.ensureConnected().getProtocolVersion();
    connection.configurationCapture = new SeamlessConfiguration.Capture(negotiated,
        SeamlessProtocols.eligible(connection.server, negotiated));
    if (connection.getPlayer() != null && connection.server.getDiscovery() != null
        && connection.server.getDiscovery().captures() != null
        && connection.server.getDiscovery().captures()
            .selects(connection.getPlayer().getUniqueId())) {
      final java.util.UUID player = connection.getPlayer().getUniqueId();
      final String backend = connection.getServerInfo().getName();
      connection.configurationCapture.sink((registry, payload, baseline) ->
          connection.server.getDiscovery().captures().baseline(player, registry, payload,
              backend, negotiated, "baseline", 0));
    }
    SeamlessConfiguration.Baseline previous =
        connection.getPlayer() == null ? null : connection.getPlayer().seamlessBaseline();
    if (previous != null) {
      try {
        shadow = new SeamlessConfiguration(previous, connection.ensureConnected().getProtocolVersion());
      } catch (RuntimeException unusable) {
        shadowFailure = "baseline unusable: " + unusable.getMessage();
      }
    }
    delegate.activated();
  }

  @Override
  public boolean beforeHandle() {
    return delegate.beforeHandle();
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    connection.configurationCapture.observe(packet);
    compare(packet);
    if (packet instanceof FinishedUpdatePacket && connection.getPlayer() != null) {
      SeamlessConfiguration.Baseline captured = connection.configurationCapture.baseline().orElse(null);
      if (shadow != null && shadowFailure == null) {
        if (captured == null || !shadow.complete()) {
          shadowFailure = "negotiation could not produce a complete supported baseline";
        } else if (!shadow.matchesSelection(captured)) {
          shadowFailure = "client known-packs selection changed between negotiations";
        }
      }
      report();
      // Publish only when this backend actually completes the client switch.
    }
    if (!packet.handle(delegate)) {
      delegate.handleGeneric(packet);
    }
  }

  /** Replays this negotiation against the client's existing baseline without acting on the result. */
  private void compare(MinecraftPacket packet) {
    if (shadow == null || shadowFailure != null) {
      return;
    }
    try {
      shadow.accept(packet);
      shadowIndex++;
    } catch (RuntimeException mismatch) {
      // Name the offending packet: the reason alone cannot tell a brand difference from a registry one.
      shadowFailure = mismatch.getMessage() + " at packet #" + shadowIndex + " ("
          + packet.getClass().getSimpleName() + describeSize(packet) + ")";
    }
  }

  private static String describeSize(MinecraftPacket packet) {
    return packet instanceof io.netty.buffer.ByteBufHolder holder
        ? ", " + holder.content().readableBytes() + " bytes" : "";
  }

  private void report() {
    if (shadow == null) {
      return;
    }
    String server = connection.getServerInfo() == null ? "backend" : connection.getServerInfo().getName();
    if (shadowFailure == null && shadow.reorderedTags()) {
      logger.info("Seamless check: {} matched this client's configuration after normalizing tag-map order.", server);
    } else if (shadowFailure == null) {
      logger.info("Seamless check: {} negotiated identically to this client's existing configuration.", server);
    } else {
      logger.info("Seamless check: {} diverged from this client's existing configuration ({}).",
          server, shadowFailure);
    }
  }

  @Override
  public void handleUnknown(ByteBuf packet) {
    connection.configurationCapture.invalidate();
    if (shadowFailure == null) {
      shadowFailure = "unrecognized configuration packet (" + packet.readableBytes() + " bytes)";
    }
    delegate.handleUnknown(packet);
  }

  @Override
  public void connected() {
    delegate.connected();
  }

  @Override
  public void deactivated() {
    delegate.deactivated();
  }

  @Override
  public void disconnected() {
    delegate.disconnected();
  }

  @Override
  public void exception(Throwable failure) {
    connection.configurationCapture.invalidate();
    delegate.exception(failure);
  }

  @Override
  public void writabilityChanged() {
    delegate.writabilityChanged();
  }

  @Override
  public void readCompleted() {
    delegate.readCompleted();
  }
}
