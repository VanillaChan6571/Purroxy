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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SeamlessConfigurationTest {
  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_2;

  @Test
  void matchingConfigurationReplaysClientSelectionAndFinishes() {
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(), VERSION);
    KnownPacksPacket selected = (KnownPacksPacket) negotiation.accept(packs()).orElseThrow();
    assertArrayEquals(new byte[] {0}, SeamlessConfiguration.encode(selected, ProtocolUtils.Direction.SERVERBOUND));
    feed(negotiation);
    assertFalse(negotiation.complete());
    assertTrue(negotiation.accept(FinishedUpdatePacket.INSTANCE).isPresent());
    assertTrue(negotiation.complete());
  }

  @Test
  void registryDifferencePermanentlyRejectsAttemptWithoutConsumingPacket() {
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(), VERSION);
    negotiation.accept(packs());
    RegistrySyncPacket different = registry(2);
    try {
      int reader = different.content().readerIndex();
      assertThrows(IllegalStateException.class, () -> negotiation.accept(different));
      assertTrue(different.content().readerIndex() == reader);
      assertThrows(IllegalStateException.class, () -> negotiation.accept(FinishedUpdatePacket.INSTANCE));
      assertFalse(negotiation.complete());
    } finally {
      different.release();
    }
  }

  @Test
  void tagsDifferenceAndEarlyFinishAreRejected() {
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(), VERSION);
    assertThrows(IllegalStateException.class, () -> negotiation.accept(FinishedUpdatePacket.INSTANCE));
    SeamlessConfiguration changed = new SeamlessConfiguration(baseline(), VERSION);
    changed.accept(packs());
    RegistrySyncPacket registry = registry(1);
    try {
      changed.accept(registry);
    } finally {
      registry.release();
    }
    changed.accept(new ActiveFeaturesPacket());
    assertThrows(IllegalStateException.class, () -> changed.accept(
        new TagsUpdatePacket(Map.of("minecraft:block", Map.of("example", new int[] {1})))));
  }

  @Test
  void unsupportedPluginExchangeAndTranslatedProtocolsCannotCreateBaseline() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION);
    PluginMessagePacket custom = new PluginMessagePacket("example:handshake", Unpooled.buffer(0));
    try {
      capture.observe(custom);
    } finally {
      custom.release();
    }
    capture.observe(FinishedUpdatePacket.INSTANCE);
    assertTrue(capture.baseline().isEmpty());
    assertTrue(new SeamlessConfiguration.Capture(ProtocolVersion.MINECRAFT_26_1).baseline().isEmpty());
    assertThrows(IllegalArgumentException.class,
        () -> new SeamlessConfiguration(baseline(), ProtocolVersion.MINECRAFT_26_1));
  }

  @Test
  void incompleteOrRepeatedSelectionCannotCreateBaseline() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION);
    capture.observe(packs());
    capture.select(packs());
    capture.select(packs());
    capture.observe(FinishedUpdatePacket.INSTANCE);
    assertTrue(capture.baseline().isEmpty());
  }

  @Test
  void handlerAcknowledgesFinishBeforeChangingBackendProtocol() {
    MinecraftConnection backend = mock(MinecraftConnection.class);
    when(backend.getProtocolVersion()).thenReturn(VERSION);
    MinecraftSessionHandler next = mock(MinecraftSessionHandler.class);
    CompletableFuture<Void> result = new CompletableFuture<>();
    SeamlessConfigSessionHandler handler = new SeamlessConfigSessionHandler(backend, next, baseline(), result);
    handler.handleGeneric(packs());
    RegistrySyncPacket registry = registry(1);
    try {
      handler.handleGeneric(registry);
    } finally {
      registry.release();
    }
    handler.handleGeneric(new ActiveFeaturesPacket());
    handler.handleGeneric(new TagsUpdatePacket());
    handler.handleGeneric(FinishedUpdatePacket.INSTANCE);
    assertTrue(result.isDone() && !result.isCompletedExceptionally());
    InOrder order = inOrder(backend);
    order.verify(backend).write(any(KnownPacksPacket.class));
    order.verify(backend).write(FinishedUpdatePacket.INSTANCE);
    order.verify(backend).setActiveSessionHandler(StateRegistry.PLAY, next);
    verify(backend, never()).close();
  }

  @Test
  void handlerTimeoutFailsWithoutSwitchingToPlay() {
    MinecraftConnection backend = mock(MinecraftConnection.class);
    when(backend.getProtocolVersion()).thenReturn(VERSION);
    EmbeddedChannel channel = new EmbeddedChannel();
    when(backend.eventLoop()).thenReturn(channel.eventLoop());
    CompletableFuture<Void> result = new CompletableFuture<>();
    SeamlessConfigSessionHandler handler = new SeamlessConfigSessionHandler(backend,
        mock(MinecraftSessionHandler.class), baseline(), result);
    try {
      handler.activated();
      channel.advanceTimeBy(11, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      assertTrue(result.isCompletedExceptionally());
      verify(backend).close();
      verify(backend, never()).setActiveSessionHandler(any(StateRegistry.class), any(MinecraftSessionHandler.class));
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void cancellationClosesTheBackendWithoutWaitingForTimeout() {
    MinecraftConnection backend = mock(MinecraftConnection.class);
    when(backend.getProtocolVersion()).thenReturn(VERSION);
    EmbeddedChannel channel = new EmbeddedChannel();
    when(backend.eventLoop()).thenReturn(channel.eventLoop());
    CompletableFuture<Void> result = new CompletableFuture<>();
    SeamlessConfigSessionHandler handler = new SeamlessConfigSessionHandler(backend,
        mock(MinecraftSessionHandler.class), baseline(), result);
    try {
      handler.activated();
      result.cancel(false);
      channel.runPendingTasks();
      verify(backend).close();
      verify(backend, never()).write(any());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void unknownPacketClosesOnlyTheDetachedBackend() {
    MinecraftConnection backend = mock(MinecraftConnection.class);
    when(backend.getProtocolVersion()).thenReturn(VERSION);
    CompletableFuture<Void> result = new CompletableFuture<>();
    SeamlessConfigSessionHandler handler = new SeamlessConfigSessionHandler(backend,
        mock(MinecraftSessionHandler.class), baseline(), result);
    ByteBuf unknown = Unpooled.wrappedBuffer(new byte[] {127});
    try {
      handler.handleUnknown(unknown);
      assertTrue(result.isCompletedExceptionally());
      assertTrue(unknown.readerIndex() == 0 && unknown.refCnt() == 1);
      verify(backend).close();
      verify(backend, never()).write(any());
    } finally {
      unknown.release();
    }
  }

  @Test
  void oversizedRegistryInvalidatesCaptureWithoutReadingItsContents() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION);
    capture.observe(packs());
    capture.select(packs());
    RegistrySyncPacket packet = new RegistrySyncPacket();
    ByteBuf bytes = Unpooled.buffer(8 * 1024 * 1024 + 1).writeZero(8 * 1024 * 1024 + 1);
    try {
      packet.decode(bytes, ProtocolUtils.Direction.CLIENTBOUND, VERSION);
      capture.observe(packet);
      capture.observe(FinishedUpdatePacket.INSTANCE);
      assertTrue(capture.baseline().isEmpty());
      assertTrue(packet.content().readerIndex() == 0);
    } finally {
      packet.release();
      bytes.release();
    }
  }

  @Test
  void observerPreservesNormalPacketDispatchEvenWhenCaptureRejectsIt() {
    VelocityServerConnection connection = mock(VelocityServerConnection.class);
    MinecraftConnection backend = mock(MinecraftConnection.class);
    when(connection.ensureConnected()).thenReturn(backend);
    when(backend.getProtocolVersion()).thenReturn(VERSION);
    MinecraftSessionHandler delegate = mock(MinecraftSessionHandler.class);
    ObservedConfigSessionHandler observer = new ObservedConfigSessionHandler(delegate, connection);
    observer.activated();
    PluginMessagePacket custom = new PluginMessagePacket("example:handshake", Unpooled.buffer(0));
    try {
      observer.handleGeneric(custom);
      verify(delegate).handle(custom);
      verify(delegate).handleGeneric(custom);
      assertTrue(connection.configurationCapture.baseline().isEmpty());
      assertTrue(custom.refCnt() == 1);
    } finally {
      custom.release();
    }
  }

  private static SeamlessConfiguration.Baseline baseline() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION);
    capture.observe(packs());
    capture.select(packs());
    RegistrySyncPacket packet = registry(1);
    try {
      capture.observe(packet);
    } finally {
      packet.release();
    }
    capture.observe(new ActiveFeaturesPacket());
    capture.observe(new TagsUpdatePacket());
    capture.observe(FinishedUpdatePacket.INSTANCE);
    return capture.baseline().orElseThrow();
  }

  private static void feed(SeamlessConfiguration negotiation) {
    RegistrySyncPacket packet = registry(1);
    try {
      negotiation.accept(packet);
    } finally {
      packet.release();
    }
    negotiation.accept(new ActiveFeaturesPacket());
    negotiation.accept(new TagsUpdatePacket());
  }

  private static KnownPacksPacket packs() {
    KnownPacksPacket packet = new KnownPacksPacket();
    ByteBuf bytes = Unpooled.wrappedBuffer(new byte[] {0});
    try {
      packet.decode(bytes, ProtocolUtils.Direction.SERVERBOUND, VERSION);
      return packet;
    } finally {
      bytes.release();
    }
  }

  private static RegistrySyncPacket registry(int value) {
    RegistrySyncPacket packet = new RegistrySyncPacket();
    ByteBuf bytes = Unpooled.wrappedBuffer(new byte[] {(byte) value});
    try {
      packet.decode(bytes, ProtocolUtils.Direction.CLIENTBOUND, VERSION);
      return packet;
    } finally {
      bytes.release();
    }
  }
}
