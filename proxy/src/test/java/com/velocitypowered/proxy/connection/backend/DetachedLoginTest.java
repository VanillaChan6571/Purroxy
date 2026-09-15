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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.network.discovery.DiscoveryService;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DetachedLoginTest {
  private final VelocityServer server = mock(VelocityServer.class);
  private final VelocityServerConnection target = mock(VelocityServerConnection.class);
  private final VelocityServerConnection source = mock(VelocityServerConnection.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection backend = mock(MinecraftConnection.class);
  private final MinecraftConnection client = mock(MinecraftConnection.class);
  private final ClientPlaySessionHandler play = mock(ClientPlaySessionHandler.class);
  private final DiscoveryService discovery = mock(DiscoveryService.class);
  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final CompletableFuture<ConnectionRequestResults.Impl> result = new CompletableFuture<>();
  private LoginSessionHandler login;

  @BeforeEach
  void setup() {
    VelocityConfiguration config = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(config);
    when(config.getPlayerInfoForwardingMode()).thenReturn(PlayerInfoForwarding.NONE);
    when(server.getDiscovery()).thenReturn(discovery);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(discovery.allowsDetachedConfiguration(id, "hub-1", "hub-2")).thenReturn(true);
    when(discovery.requireVisibleArrival(id, "hub-2"))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(discovery.approveSeamlessArrival(id, "hub-2"))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(source.getServerInfo()).thenReturn(new ServerInfo("hub-1", new InetSocketAddress("127.0.0.1", 25566)));
    when(target.getServerInfo()).thenReturn(new ServerInfo("hub-2", new InetSocketAddress("127.0.0.1", 25567)));
    when(source.isActive()).thenReturn(true);
    when(player.isActive()).thenReturn(true);
    when(player.getConnectedServer()).thenReturn(source);
    when(player.getConnectionInFlight()).thenReturn(target);
    when(player.getConnection()).thenReturn(client);
    when(client.getType()).thenReturn(ConnectionTypes.VANILLA);
    when(client.eventLoop()).thenReturn(channel.eventLoop());
    when(client.getActiveSessionHandler()).thenReturn(play);
    when(player.seamlessBaseline()).thenReturn(SeamlessConfigurationTest.baseline());
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_2);
    when(target.getPlayer()).thenReturn(player);
    when(target.ensureConnected()).thenReturn(backend);
    when(backend.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_2);
    when(backend.eventLoop()).thenReturn(channel.eventLoop());
    login = new LoginSessionHandler(server, target, result);
  }

  @AfterEach
  void close() {
    channel.finishAndReleaseAll();
  }

  private MinecraftSessionHandler start() {
    login.handle(mock(ServerLoginSuccessPacket.class));
    ArgumentCaptor<MinecraftSessionHandler> installed = ArgumentCaptor.forClass(MinecraftSessionHandler.class);
    verify(backend).setActiveSessionHandler(eq(StateRegistry.CONFIG), installed.capture());
    return installed.getValue();
  }

  @Test
  void eligibleLoginNegotiatesOnlyWithBackendWithoutSwitchingClient() {
    MinecraftSessionHandler handler = start();
    assertTrue(handler instanceof SeamlessConfigSessionHandler);
    handler.handleGeneric(SeamlessConfigurationTest.packs());
    verify(backend).write(any(LoginAcknowledgedPacket.class));
    verify(backend).write(any(KnownPacksPacket.class));
    verify(play, never()).doSwitch();
    verify(client, never()).write(any());
    verify(source, never()).disconnect();
    assertFalse(result.isDone());
  }

  @Test
  void matchingConfigurationInstallsJoinHandlerWithoutTouchingClient() {
    MinecraftSessionHandler handler = start();
    handler.handleGeneric(SeamlessConfigurationTest.packs());
    com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket registry =
        new com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket();
    ByteBuf encoded = Unpooled.buffer().writeByte(1);
    try {
      registry.decode(encoded, com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.CLIENTBOUND,
          ProtocolVersion.MINECRAFT_26_2);
      handler.handleGeneric(registry);
    } finally {
      registry.release();
      encoded.release();
    }
    handler.handleGeneric(new com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket());
    handler.handleGeneric(new com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket());
    handler.handleGeneric(com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket.INSTANCE);
    verify(backend).setActiveSessionHandler(eq(StateRegistry.PLAY), any(TransitionSessionHandler.class));
    verify(play, never()).doSwitch();
    verify(client, never()).write(any());
    verify(client, never()).delayedWrite(any());
    verify(source, never()).disconnect();
    assertFalse(result.isDone()); // Configuration success alone is not a successful player switch.
  }

  @Test
  void unsupportedSeamlessApprovalDoesNotFailCommittedPreferredTransfer() {
    when(discovery.approveSeamlessArrival(player.getUniqueId(), "hub-2"))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unknown action")));
    MinecraftSessionHandler handler = start();
    handler.handleGeneric(SeamlessConfigurationTest.packs());
    com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket registry =
        new com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket();
    ByteBuf encoded = Unpooled.buffer().writeByte(1);
    try {
      registry.decode(encoded, com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.CLIENTBOUND,
          ProtocolVersion.MINECRAFT_26_2);
      handler.handleGeneric(registry);
    } finally {
      registry.release();
      encoded.release();
    }
    handler.handleGeneric(new com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket());
    handler.handleGeneric(new com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket());
    handler.handleGeneric(com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket.INSTANCE);
    verify(backend).setActiveSessionHandler(eq(StateRegistry.PLAY), any(TransitionSessionHandler.class));
    verify(target, never()).connect();
    assertFalse(result.isDone());
  }

  @Test
  void cancelledAttemptDoesNotStartFallbackLogin() {
    start();
    result.cancel(false);
    channel.runPendingTasks();
    verify(target, never()).connect();
    verify(source, never()).disconnect();
    assertTrue(result.isCancelled());
  }

  @Test
  void rejectedProbeRetriesNormalLoginOnceWithoutReleasingSource() {
    CompletableFuture<ConnectionRequestResults.Impl> retry = new CompletableFuture<>();
    when(target.connect()).thenReturn(retry);
    MinecraftSessionHandler handler = start();
    ByteBuf unknown = Unpooled.buffer().writeByte(127);
    try {
      handler.handleUnknown(unknown);
      handler.handleUnknown(unknown);
      channel.runPendingTasks();
      verify(target).connect();
      assertTrue(target.detachedAttempted);
      assertFalse(target.detachedConfiguration);
      verify(source, never()).disconnect();
      assertFalse(result.isDone());
      retry.completeExceptionally(new IllegalStateException("normal retry failed"));
      assertTrue(result.isCompletedExceptionally());
    } finally {
      unknown.release();
    }
  }

  @Test
  void unmarkableVisibleArrivalStillFallsBackToNormalLogin() {
    // The destination never had its arrival sync suppressed, so the mark is advisory. Losing it
    // must not turn a failed probe into a kicked player on a committed transfer.
    when(discovery.requireVisibleArrival(player.getUniqueId(), "hub-2"))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("backend rejected it")));
    when(discovery.seamlessArrivalApproved(player.getUniqueId())).thenReturn(false);
    when(target.connect()).thenReturn(new CompletableFuture<>());
    MinecraftSessionHandler handler = start();
    ByteBuf unknown = Unpooled.buffer().writeByte(127);
    try {
      handler.handleUnknown(unknown);
      channel.runPendingTasks();
      verify(target).connect();
      assertFalse(result.isDone());
    } finally {
      unknown.release();
    }
  }

  @Test
  void approvedSeamlessArrivalThatCannotBeUnmarkedFailsTheConnection() {
    // Here the suppression is already durable at the destination, so retrying through visible
    // configuration would reset the client without ever synchronizing its position.
    when(discovery.requireVisibleArrival(player.getUniqueId(), "hub-2"))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("backend rejected it")));
    when(discovery.seamlessArrivalApproved(player.getUniqueId())).thenReturn(true);
    MinecraftSessionHandler handler = start();
    ByteBuf unknown = Unpooled.buffer().writeByte(127);
    try {
      handler.handleUnknown(unknown);
      channel.runPendingTasks();
      verify(target, never()).connect();
      assertTrue(result.isCompletedExceptionally());
    } finally {
      unknown.release();
    }
  }

  @Test
  void absentBaselineUsesNormalConfiguration() {
    when(player.seamlessBaseline()).thenReturn(null);
    when(play.doSwitch()).thenReturn(CompletableFuture.completedFuture(null));
    assertTrue(start() instanceof ConfigSessionHandler);
    verify(play).doSwitch();
    assertFalse(target.detachedAttempted);
  }

  @Test
  void unlistedProtocolUsesNormalConfiguration() {
    // 26.1 can be qualified, but nobody has listed it here, so it takes the visible path.
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_1);
    when(play.doSwitch()).thenReturn(CompletableFuture.completedFuture(null));
    assertTrue(start() instanceof ConfigSessionHandler);
    verify(play).doSwitch();
  }

  @Test
  void protocolBelowTheSeamlessFloorUsesNormalPlayLoginEvenWhenListed() {
    when(discovery.seamlessCanaryProtocols())
        .thenReturn(java.util.Set.of(ProtocolVersion.MINECRAFT_1_20));
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20);
    when(backend.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20);
    login.handle(mock(ServerLoginSuccessPacket.class));
    verify(backend).setActiveSessionHandler(eq(StateRegistry.PLAY), any(TransitionSessionHandler.class));
    verify(backend, never()).write(any(LoginAcknowledgedPacket.class));
    assertFalse(target.detachedAttempted);
  }

  @Test
  void listedProtocol764StartsDetachedConfigurationWithoutKnownPacks() {
    when(discovery.seamlessCanaryProtocols())
        .thenReturn(java.util.Set.of(ProtocolVersion.MINECRAFT_1_20_2));
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_2);
    when(backend.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_2);
    when(player.seamlessBaseline())
        .thenReturn(SeamlessConfigurationTest.baseline(ProtocolVersion.MINECRAFT_1_20_2));
    assertTrue(start() instanceof SeamlessConfigSessionHandler);
    verify(backend).write(any(LoginAcknowledgedPacket.class));
    verify(backend, never()).write(any(KnownPacksPacket.class));
    verify(play, never()).doSwitch();
  }

  @Test
  void listedProtocolTakesTheDetachedPath() {
    when(discovery.seamlessCanaryProtocols())
        .thenReturn(java.util.Set.of(ProtocolVersion.MINECRAFT_1_21_11));
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_11);
    when(backend.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_11);
    when(player.seamlessBaseline())
        .thenReturn(SeamlessConfigurationTest.baseline(ProtocolVersion.MINECRAFT_1_21_11));
    assertTrue(start() instanceof SeamlessConfigSessionHandler);
    verify(play, never()).doSwitch();
  }

  @Test
  void changedBaselineDuringNegotiationRetriesInsteadOfAcknowledgingMorePackets() {
    when(target.connect()).thenReturn(new CompletableFuture<>());
    MinecraftSessionHandler handler = start();
    when(player.seamlessBaseline()).thenReturn(null);
    handler.handleGeneric(SeamlessConfigurationTest.packs());
    channel.runPendingTasks();
    verify(backend, never()).write(any(KnownPacksPacket.class));
    verify(target).connect();
    verify(source, never()).disconnect();
  }

  @Test
  void nativePlayTagUpdateInvalidatesBaselineWithoutConsumingForwardedBuffer() {
    BackendPlaySessionHandler handler = new BackendPlaySessionHandler(server, target);
    ByteBuf packet = Unpooled.buffer();
    try {
      com.velocitypowered.proxy.protocol.ProtocolUtils.writeVarInt(packet, 0x86);
      packet.writeByte(0);
      handler.handleUnknown(packet);
      verify(player).setSeamlessBaseline(null);
      assertTrue(packet.readerIndex() == 0);
      verify(client).delayedWrite(packet);
    } finally {
      packet.release(packet.refCnt());
    }
  }

  @Test
  void playTagUpdateIsRecognisedAtTheCanaryProtocolsOwnPacketId() {
    // The id moves with the version. Watching for 26.2's would leave every older family with no
    // invalidation at all, which is how a stale baseline reaches a switch.
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_11);
    BackendPlaySessionHandler handler = new BackendPlaySessionHandler(server, target);
    ByteBuf packet = Unpooled.buffer();
    try {
      com.velocitypowered.proxy.protocol.ProtocolUtils.writeVarInt(packet, 0x84);
      packet.writeByte(0);
      handler.handleUnknown(packet);
      verify(player).setSeamlessBaseline(null);
      assertTrue(packet.readerIndex() == 0);
    } finally {
      packet.release(packet.refCnt());
    }
  }

  @Test
  void anotherVersionsTagUpdateIdIsNotMistakenForThisOnes() {
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_11);
    BackendPlaySessionHandler handler = new BackendPlaySessionHandler(server, target);
    ByteBuf packet = Unpooled.buffer();
    try {
      com.velocitypowered.proxy.protocol.ProtocolUtils.writeVarInt(packet, 0x86);
      packet.writeByte(0);
      handler.handleUnknown(packet);
      verify(player, never()).setSeamlessBaseline(null);
    } finally {
      packet.release(packet.refCnt());
    }
  }

  @Test
  void playServerLinksAndReportDetailsInvalidateByTypeRatherThanById() {
    // Both are registered in PLAY from 1.21, so they decode and never reach handleUnknown - the
    // id check that used to claim to cover them could not have fired.
    BackendPlaySessionHandler handler = new BackendPlaySessionHandler(server, target);
    assertFalse(handler.handle(
        mock(com.velocitypowered.proxy.protocol.packet.config.ClientboundServerLinksPacket.class)));
    assertFalse(handler.handle(mock(
        com.velocitypowered.proxy.protocol.packet.config.ClientboundCustomReportDetailsPacket.class)));
    verify(player, times(2)).setSeamlessBaseline(null);
  }
}
