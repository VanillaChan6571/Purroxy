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

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.event.player.CookieRequestEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.PlayerDataForwarding;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket;
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket;
import com.velocitypowered.proxy.protocol.packet.SetCompressionPacket;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles a player trying to log into the proxy.
 */
public class LoginSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(LoginSessionHandler.class);
  private static final AtomicBoolean VIA_INSPECTION_WARNING = new AtomicBoolean();
  private static final AtomicBoolean SEAMLESS_APPROVAL_WARNING = new AtomicBoolean();

  private static final Component MODERN_IP_FORWARDING_FAILURE =
      Component.translatable("velocity.error.modern-forwarding-failed");

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<Impl> resultFuture;
  private boolean informationForwarded;

  LoginSessionHandler(VelocityServer server, VelocityServerConnection serverConn,
      CompletableFuture<Impl> resultFuture) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
  }

  @Override
  public boolean handle(EncryptionRequestPacket packet) {
    throw new IllegalStateException("Backend server is online-mode!");
  }

  @Override
  public boolean handle(LoginPluginMessagePacket packet) {
    MinecraftConnection mc = serverConn.ensureConnected();
    VelocityConfiguration configuration = server.getConfiguration();
    if (configuration.getPlayerInfoForwardingMode() == PlayerInfoForwarding.MODERN
        && packet.getChannel().equals(PlayerDataForwarding.CHANNEL)) {

      int requestedForwardingVersion = PlayerDataForwarding.MODERN_DEFAULT;
      // Check version
      if (packet.content().readableBytes() == 1) {
        requestedForwardingVersion = packet.content().readByte();
      }
      ConnectedPlayer player = serverConn.getPlayer();
      ByteBuf forwardingData = PlayerDataForwarding.createForwardingData(
          configuration.getForwardingSecret(),
          serverConn.getPlayerRemoteAddressAsString(),
          player.getProtocolVersion(),
          player.getGameProfile(),
          player.getIdentifiedKey(),
          requestedForwardingVersion);

      LoginPluginResponsePacket response = new LoginPluginResponsePacket(
              packet.getId(), true, forwardingData);
      mc.write(response);
      informationForwarded = true;
    } else {
      // Don't understand, fire event if we have subscribers
      if (!this.server.getEventManager().hasSubscribers(ServerLoginPluginMessageEvent.class)) {
        mc.write(new LoginPluginResponsePacket(packet.getId(), false, Unpooled.EMPTY_BUFFER));
        return true;
      }

      final byte[] contents = ByteBufUtil.getBytes(packet.content());
      final MinecraftChannelIdentifier identifier = MinecraftChannelIdentifier
          .from(packet.getChannel());
      this.server.getEventManager().fire(new ServerLoginPluginMessageEvent(serverConn, identifier,
              contents, packet.getId()))
          .thenAcceptAsync(event -> {
            if (event.getResult().isAllowed()) {
              mc.write(new LoginPluginResponsePacket(packet.getId(), true, Unpooled
                  .wrappedBuffer(event.getResult().getResponse())));
            } else {
              mc.write(new LoginPluginResponsePacket(packet.getId(), false, Unpooled.EMPTY_BUFFER));
            }
          }, mc.eventLoop());
    }
    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    resultFuture.complete(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()));
    serverConn.disconnect();
    return true;
  }

  @Override
  public boolean handle(SetCompressionPacket packet) {
    serverConn.ensureConnected().setCompressionThreshold(packet.getThreshold());
    return true;
  }

  @Override
  public boolean handle(ServerLoginSuccessPacket packet) {
    if (server.getConfiguration().getPlayerInfoForwardingMode() == PlayerInfoForwarding.MODERN && !informationForwarded) {
      resultFuture.complete(ConnectionRequestResults.forDisconnect(MODERN_IP_FORWARDING_FAILURE, serverConn.getServer()));
      serverConn.disconnect();
      return true;
    }

    // The player has been logged on to the backend server, but we're not done yet. There could be
    // other problems that could arise before we get a JoinGame packet from the server.

    // Move into the PLAY phase.
    MinecraftConnection smc = serverConn.ensureConnected();
    if (smc.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      markLegacySeamless(smc);
      smc.setActiveSessionHandler(StateRegistry.PLAY, new TransitionSessionHandler(server, serverConn, resultFuture));
    } else {
      if (tryDetachedConfiguration(smc)) {
        return true;
      }
      smc.write(new LoginAcknowledgedPacket());
      ConfigSessionHandler configurationHandler = new ConfigSessionHandler(server, serverConn, resultFuture);
      smc.setActiveSessionHandler(StateRegistry.CONFIG, server.getDiscovery() == null
          || !server.getDiscovery().captureSeamlessBaseline(serverConn.getServerInfo().getName()) ? configurationHandler
          : new ObservedConfigSessionHandler(configurationHandler, serverConn));
      ConnectedPlayer player = serverConn.getPlayer();
      if (player.getClientSettingsPacket() != null) {
        smc.write(player.getClientSettingsPacket());
      }
      if (player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler clientPlaySessionHandler) {
        smc.setAutoReading(false);
        clientPlaySessionHandler.doSwitch().thenRunAsync(() -> smc.setAutoReading(true), smc.eventLoop());
      } else {
        // Initial login - the player is already in configuration state.
        server.getEventManager().fireAndForget(new PlayerEnteredConfigurationEvent(player, serverConn));
      }
    }

    return true;
  }

  @Override
  public boolean handle(ClientboundStoreCookiePacket packet) {
    throw new IllegalStateException("Can only store cookie in CONFIGURATION or PLAY protocol");
  }

  @Override
  public boolean handle(ClientboundCookieRequestPacket packet) {
    server.getEventManager().fire(new CookieRequestEvent(serverConn.getPlayer(), packet.getKey()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();

            serverConn.getPlayer().getConnection().write(new ClientboundCookieRequestPacket(resultedKey));
          }
        }, serverConn.ensureConnected().eventLoop());

    return true;
  }

  /**
   * Marks a pre-1.20.2 arrival as a candidate for keeping its client in PLAY.
   *
   * <p>These protocols never reach {@link #tryDetachedConfiguration}: they have no configuration
   * phase, so this handler sends them straight to PLAY. Everything the detached path refuses for
   * still applies, except the captured configuration baseline, which cannot exist here. Whether
   * the client is actually spared its reset is decided later, from the destination's JoinGame.
   */
  private void markLegacySeamless(MinecraftConnection backend) {
    ConnectedPlayer player = serverConn.getPlayer();
    VelocityServerConnection source = player.getConnectedServer();
    String refusal = detachedRefusal(player, source, null, backend, true);
    if (!refusal.isEmpty()) {
      // Same reporting rule as the detached path: only real switches, never an initial login.
      if (source != null && source.isActive()
          && player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler) {
        logger.info("Legacy seamless switch to {} not attempted: {}. Client protocol {}, proxy"
            + " negotiated {}, destination link {}.", serverConn.getServerInfo().getName(),
            refusal, originalProtocol(player), player.getProtocolVersion().getProtocol(),
            backend.getProtocolVersion().getProtocol());
      }
      return;
    }
    serverConn.legacySeamlessArrival = true;
  }

  private boolean tryDetachedConfiguration(MinecraftConnection backend) {
    ConnectedPlayer player = serverConn.getPlayer();
    VelocityServerConnection source = player.getConnectedServer();
    SeamlessConfiguration.Baseline baseline = player.seamlessBaseline();
    if (serverConn.detachedAttempted) {
      return false; // The visible retry after a failed probe, which already reported itself.
    }
    String refusal = detachedRefusal(player, source, baseline, backend, false);
    if (!refusal.isEmpty()) {
      // A switch that could have been seamless and was not is otherwise invisible: the client just
      // reconfigures and reloads terrain with nothing recording why. Only report real switches - an
      // initial login has no source to hand over from and would say so on every single join.
      if (source != null && source.isActive()
          && player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler) {
        // Three protocols, because they are not always the same number and the difference is the
        // whole question: what the client speaks, what this proxy negotiated with it, and what the
        // destination link negotiated. Where a translator sits is visible in which pair diverges,
        // rather than assumed.
        logger.info("Seamless switch to {} not attempted: {}. Client protocol {}, proxy negotiated"
            + " {}, destination link {}.", serverConn.getServerInfo().getName(), refusal,
            originalProtocol(player), player.getProtocolVersion().getProtocol(),
            backend.getProtocolVersion().getProtocol());
      }
      return false;
    }
    serverConn.detachedAttempted = true;
    serverConn.detachedConfiguration = true;
    serverConn.detachedSource = source;
    serverConn.detachedBaseline = baseline;
    CompletableFuture<Void> negotiation = new CompletableFuture<>();
    resultFuture.whenComplete((result, failure) -> {
      if (resultFuture.isCancelled()) {
        negotiation.cancel(false);
      }
    });
    negotiation.whenComplete((ignored, failure) -> {
      if (failure == null) {
        // Whether the client is reset is not known yet: it depends on the entity id the
        // destination sends in JoinGame. ClientPlaySessionHandler logs the outcome.
        logger.info("Detached configuration: {} negotiated with the client remaining in PLAY.",
            serverConn.getServerInfo().getName());
        return;
      }
      // Retry login once through the ordinary configuration path. The committed handoff is
      // retained: a failed probe must never restore ownership to the source after commit.
      backend.close();
      backend.eventLoop().execute(() -> {
        serverConn.detachedConfiguration = false;
        serverConn.detachedSource = null;
        if (!player.isActive() || player.getConnectionInFlight() != serverConn || resultFuture.isDone()) {
          logger.info("Detached configuration: {} cannot fall back to normal configuration ({}).",
              serverConn.getServerInfo().getName(), failure.getMessage());
          resultFuture.completeExceptionally(failure);
          return;
        }
        // The backend normally suppresses its arrival teleport for a seamless candidate. Persistently
        // turn that off before reconnecting through visible CONFIG, otherwise the fallback could reset
        // the client without ever synchronizing its destination position.
        server.getDiscovery().requireVisibleArrival(player.getUniqueId(), serverConn.getServerInfo().getName())
            .whenCompleteAsync((marked, markFailure) -> {
              // The mark is only load-bearing once a suppression was actually approved. Without one
              // the destination still sends its ordinary arrival sync, so failing to mark it must not
              // cost the player a committed transfer: the probe is the optimization, this is the switch.
              if (markFailure != null
                  && server.getDiscovery().seamlessArrivalApproved(player.getUniqueId())) {
                logger.error("Detached configuration: {} approved a seamless arrival and could not be"
                    + " returned to a visible one; the transfer has to be recovered.",
                    serverConn.getServerInfo().getName(), markFailure);
                resultFuture.completeExceptionally(markFailure);
                return;
              }
              if (markFailure != null) {
                logger.warn("Detached configuration: {} could not be marked for a visible arrival;"
                    + " falling back anyway, since its arrival sync was never suppressed.",
                    serverConn.getServerInfo().getName(), markFailure);
              }
              logger.info("Detached configuration: {} fell back to normal configuration ({}).",
                  serverConn.getServerInfo().getName(), failure.getMessage());
              try {
                serverConn.connect().whenComplete((result, retryFailure) -> {
                  if (retryFailure != null) {
                    resultFuture.completeExceptionally(retryFailure);
                  } else {
                    resultFuture.complete(result);
                  }
                });
              } catch (RuntimeException retryFailure) {
                resultFuture.completeExceptionally(retryFailure);
              }
            }, player.getConnection().eventLoop());
      });
    });
    backend.write(new LoginAcknowledgedPacket());
    SeamlessConfigSessionHandler detached = new SeamlessConfigSessionHandler(backend,
        new TransitionSessionHandler(server, serverConn, resultFuture), baseline, negotiation,
        () -> !resultFuture.isDone() && player.isActive() && source.isActive()
            && player.getConnectionInFlight() == serverConn && player.getConnectedServer() == source
            && player.seamlessBaseline() == baseline
            && player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler,
        () -> approveSeamlessArrival(player, serverConn));
    if (server.getDiscovery().captures() != null
        && server.getDiscovery().captures().selects(player.getUniqueId())) {
      // Narrow by construction: one selected account, and the store ignores every registry
      // except the one whose ordering assigns chunk-visible ids.
      final java.util.UUID captured = player.getUniqueId();
      final String destination = serverConn.getServerInfo().getName();
      final ProtocolVersion negotiated = backend.getProtocolVersion();
      final String instance = server.getDiscovery().backendInstance(destination);
      detached.sink((registry, payload, baseline1) -> server.getDiscovery().captures()
          .mismatch(captured, registry, payload, destination, instance, negotiated,
              "detached", 1));
    }
    backend.setActiveSessionHandler(StateRegistry.CONFIG, detached);
    if (player.getClientSettingsPacket() != null) {
      backend.write(player.getClientSettingsPacket());
    }
    return true;
  }

  /**
   * Names the first unmet precondition for a client-invisible switch, or an empty string when every
   * one is met. Split out from the attempt so a refusal can say which condition actually failed.
   */
  private String detachedRefusal(ConnectedPlayer player, VelocityServerConnection source,
      SeamlessConfiguration.Baseline baseline, MinecraftConnection backend, boolean legacy) {
    if (source == null || !source.isActive()) {
      return "there is no live source server to hand over from";
    }
    if (!(player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler)) {
      return "the client is not in play";
    }
    // Protocol first: configuration capture is gated on the same policy, so a client at an
    // unenabled protocol always lacks a baseline too. Reporting that would name the symptom and
    // hide the cause.
    if (!SeamlessProtocols.eligible(server, player.getProtocolVersion())) {
      return "protocol " + player.getProtocolVersion().getProtocol()
          + " is not enabled for seamless switching";
    }
    int original = originalProtocol(player);
    if (original < 0) {
      return "the client's original protocol could not be verified";
    }
    if (original != player.getProtocolVersion().getProtocol()) {
      // A translated connection is a different thing from a native one at the same number,
      // and only the native form has been qualified.
      return "the client is translated from protocol " + original + " rather than native";
    }
    // Below 1.20.2 there is no configuration phase to capture, so demanding a baseline would
    // refuse every legacy arrival for lacking something it can never have. Those protocols are
    // judged on the destination JoinGame instead, in ClientPlaySessionHandler.
    if (!legacy && baseline == null) {
      return "this client has no captured configuration to compare the destination against";
    }
    if (!SeamlessProtocols.eligible(server, backend.getProtocolVersion())) {
      return "the destination link negotiated protocol "
          + backend.getProtocolVersion().getProtocol() + ", which is not enabled";
    }
    if (player.getConnection().getType()
        != com.velocitypowered.proxy.connection.ConnectionTypes.VANILLA) {
      return "the client connection is not vanilla";
    }
    if (server.getDiscovery() == null) {
      return "coordinated handoff is unavailable";
    }
    if (!server.getDiscovery().allowsDetachedConfiguration(player.getUniqueId(),
        source.getServerInfo().getName(), serverConn.getServerInfo().getName())) {
      return "the committed handoff did not qualify for a seamless arrival";
    }
    return "";
  }

  /**
   * The protocol the client itself speaks. Without a translator that is what this proxy negotiated;
   * with ViaVersion the proxy sees the backend's protocol instead and only Via knows the original.
   * Negative when a translator is present but cannot be questioned, so callers fail closed rather
   * than mistaking a translated client for a native one.
   */
  private int originalProtocol(ConnectedPlayer player) {
    // Unit-test server doubles do not install a plugin manager. A real VelocityServer always does.
    if (server.getPluginManager() == null
        || server.getPluginManager().getPlugin("viaversion").isEmpty()) {
      return player.getProtocolVersion().getProtocol();
    }
    try {
      Class<?> via = Class.forName("com.viaversion.viaversion.api.Via", false,
          server.getPluginManager().getPlugin("viaversion").orElseThrow().getInstance()
              .orElseThrow().getClass().getClassLoader());
      Object api = via.getMethod("getAPI").invoke(null);
      Object original = api.getClass().getMethod("getPlayerVersion", java.util.UUID.class)
          .invoke(api, player.getUniqueId());
      return original instanceof Number number ? number.intValue() : -1;
    } catch (ReflectiveOperationException | RuntimeException failure) {
      if (VIA_INSPECTION_WARNING.compareAndSet(false, true)) {
        logger.warn("Seamless transfers are disabled while ViaVersion's original client protocol "
            + "cannot be verified.", failure);
      }
      return -1;
    }
  }

  private CompletableFuture<Void> approveSeamlessArrival(ConnectedPlayer player,
      VelocityServerConnection destination) {
    return server.getDiscovery().approveSeamlessArrival(
        player.getUniqueId(), destination.getServerInfo().getName()).handle((ignored, failure) -> {
          if (failure != null && SEAMLESS_APPROVAL_WARNING.compareAndSet(false, true)) {
            logger.warn("The destination did not acknowledge seamless-arrival approval. Continuing"
                + " through the backward-compatible path; deploy the matching Nekopur build to"
                + " enable durable arrival-sync coordination.", failure);
          }
          // This signal hardens coordination with a matching Nekopur, but seamless-preferred must
          // remain compatible with older peers. A rejected extension must never fail a transfer
          // after ownership has already committed.
          return null;
        });
  }

  @Override
  public void exception(Throwable throwable) {
    resultFuture.completeExceptionally(throwable);
  }

  @Override
  public void disconnected() {
    if (server.getConfiguration().getPlayerInfoForwardingMode() == PlayerInfoForwarding.LEGACY) {
      resultFuture.completeExceptionally(new QuietRuntimeException(
              """
              The connection to the remote server was unexpectedly closed.
              This is usually because the remote server does not have \
              BungeeCord IP forwarding correctly enabled.
              See https://docs.papermc.io/velocity/player-information-forwarding for instructions \
              on how to configure player info forwarding correctly."""));
    } else {
      resultFuture.completeExceptionally(
          new QuietRuntimeException("The connection to the remote server was unexpectedly closed.")
      );
    }
  }
}
