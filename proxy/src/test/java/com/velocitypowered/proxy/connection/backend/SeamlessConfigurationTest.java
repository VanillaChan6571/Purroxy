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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

class SeamlessConfigurationTest {
  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_2;

  @Test
  void legacyCaptureRejectsKnownPacksAndIncompleteExchanges() {
    ProtocolVersion legacy = ProtocolVersion.MINECRAFT_1_20_3;
    SeamlessConfiguration.Capture invalid = new SeamlessConfiguration.Capture(legacy, true);
    invalid.observe(packs());
    invalid.observe(FinishedUpdatePacket.INSTANCE);
    assertTrue(invalid.baseline().isEmpty());
    SeamlessConfiguration.Capture incomplete = new SeamlessConfiguration.Capture(legacy, true);
    incomplete.observe(new ActiveFeaturesPacket());
    incomplete.observe(new TagsUpdatePacket());
    incomplete.observe(FinishedUpdatePacket.INSTANCE);
    assertTrue(incomplete.baseline().isEmpty());
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(legacy), legacy);
    assertThrows(IllegalStateException.class, () -> negotiation.accept(packs()));
    assertFalse(negotiation.complete());
  }

  @Test
  void legacySelectionComparisonIsBoundToTheProtocol() {
    ProtocolVersion legacy = ProtocolVersion.MINECRAFT_1_20_3;
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(legacy), legacy);
    assertTrue(negotiation.matchesSelection(baseline(legacy)));
    assertFalse(negotiation.matchesSelection(baseline()));
    assertThrows(IllegalArgumentException.class,
        () -> new SeamlessConfiguration(baseline(legacy), VERSION));
  }

  @Test
  void matchingConfigurationReplaysClientSelectionAndFinishes() {
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(), VERSION);
    KnownPacksPacket selected = (KnownPacksPacket) negotiation.accept(packs()).orElseThrow();
    assertArrayEquals(new byte[] {0}, SeamlessConfiguration.encode(selected,
        ProtocolUtils.Direction.SERVERBOUND, ProtocolVersion.MINECRAFT_26_2));
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
  void mismatchIdentifiesBothRegistryNamesAndSizesWithoutConsumingBuffers() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION, true);
    capture.observe(packs());
    capture.select(packs());
    RegistrySyncPacket source = namedRegistry("minecraft:dimension_type");
    RegistrySyncPacket destination = namedRegistry("minecraft:worldgen/biome");
    try {
      capture.observe(source);
      capture.observe(new ActiveFeaturesPacket());
      capture.observe(new TagsUpdatePacket());
      capture.observe(FinishedUpdatePacket.INSTANCE);
      SeamlessConfiguration negotiation = new SeamlessConfiguration(capture.baseline().orElseThrow(), VERSION);
      negotiation.accept(packs());
      String reason = assertThrows(IllegalStateException.class, () -> negotiation.accept(destination)).getMessage();
      assertTrue(reason.contains("data packet #2"));
      assertTrue(reason.contains("registry=minecraft:dimension_type"));
      assertTrue(reason.contains("registry=minecraft:worldgen/biome"));
      assertTrue(reason.contains("bytes, sha256="));
      assertTrue(source.content().readerIndex() == 0 && destination.content().readerIndex() == 0);
      assertTrue(source.refCnt() == 1 && destination.refCnt() == 1);
    } finally {
      source.release();
      destination.release();
    }
  }

  @Test
  void differentClientSelectionCannotBeReportedAsIdentical() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION, true);
    capture.observe(packs());
    KnownPacksPacket reply = new KnownPacksPacket();
    ByteBuf encoded = Unpooled.buffer();
    try {
      ProtocolUtils.writeVarInt(encoded, 1);
      ProtocolUtils.writeString(encoded, "minecraft");
      ProtocolUtils.writeString(encoded, "core");
      ProtocolUtils.writeString(encoded, "26.2");
      reply.decode(encoded, ProtocolUtils.Direction.SERVERBOUND, VERSION);
      capture.select(reply);
    } finally {
      encoded.release();
    }
    RegistrySyncPacket packet = registry(1);
    try {
      capture.observe(packet);
    } finally {
      packet.release();
    }
    capture.observe(new ActiveFeaturesPacket());
    capture.observe(new TagsUpdatePacket());
    capture.observe(FinishedUpdatePacket.INSTANCE);
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(), VERSION);
    assertTrue(negotiation.matchesSelection(baseline()));
    assertFalse(negotiation.matchesSelection(capture.baseline().orElseThrow()));
  }

  @Test
  void joinStateIgnoresOnlyTheNegotiatedEntityId() {
    SeamlessConfiguration.JoinBaseline baseline = SeamlessConfiguration.captureJoin(join(41, 0, 0), VERSION);
    assertTrue(baseline.matches(join(42, 0, 0), VERSION));
    assertFalse(baseline.matches(join(42, 1, 0), VERSION));
    assertFalse(baseline.matches(join(42, 0, 1), VERSION));
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
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION, true);
    PluginMessagePacket custom = new PluginMessagePacket("example:handshake", Unpooled.buffer(0));
    try {
      capture.observe(custom);
    } finally {
      custom.release();
    }
    capture.observe(FinishedUpdatePacket.INSTANCE);
    assertTrue(capture.baseline().isEmpty());
    assertTrue(new SeamlessConfiguration.Capture(ProtocolVersion.MINECRAFT_26_1, false).baseline().isEmpty());
    assertThrows(IllegalArgumentException.class,
        () -> new SeamlessConfiguration(baseline(), ProtocolVersion.MINECRAFT_26_1));
  }

  @Test
  void incompleteOrRepeatedSelectionCannotCreateBaseline() {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION, true);
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
  void handlerDoesNotAcknowledgeFinishBeforeArrivalApproval() {
    MinecraftConnection backend = mock(MinecraftConnection.class);
    when(backend.getProtocolVersion()).thenReturn(VERSION);
    EmbeddedChannel channel = new EmbeddedChannel();
    when(backend.eventLoop()).thenReturn(channel.eventLoop());
    MinecraftSessionHandler next = mock(MinecraftSessionHandler.class);
    CompletableFuture<Void> result = new CompletableFuture<>();
    CompletableFuture<Void> approval = new CompletableFuture<>();
    SeamlessConfigSessionHandler handler = new SeamlessConfigSessionHandler(backend, next,
        baseline(), result, () -> true, () -> approval);
    try {
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
      verify(backend, never()).write(FinishedUpdatePacket.INSTANCE);
      verify(backend, never()).setActiveSessionHandler(any(StateRegistry.class), any(MinecraftSessionHandler.class));

      approval.complete(null);
      channel.runPendingTasks();
      verify(backend).write(FinishedUpdatePacket.INSTANCE);
      verify(backend).setActiveSessionHandler(StateRegistry.PLAY, next);
      assertTrue(result.isDone() && !result.isCompletedExceptionally());
    } finally {
      channel.finishAndReleaseAll();
    }
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
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(VERSION, true);
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

  @Test
  void baselineOnlyComparesAgainstTheProtocolItWasCapturedAt() {
    SeamlessConfiguration.Baseline captured = baseline();
    assertEquals(VERSION, captured.protocol());
    // Packet bytes only mean the same thing at one version, so a baseline taken before a client
    // changed protocol must not be replayed against the new one.
    assertThrows(IllegalArgumentException.class,
        () -> new SeamlessConfiguration(captured, ProtocolVersion.MINECRAFT_26_1));
    assertDoesNotThrow(() -> new SeamlessConfiguration(captured, VERSION));
  }

  static SeamlessConfiguration.Baseline baseline() {
    return baseline(new TagsUpdatePacket());
  }

  /** The same negotiation taken at some other protocol, for the version-spanning cases below. */
  static SeamlessConfiguration.Baseline baseline(ProtocolVersion version) {
    return baseline(new TagsUpdatePacket(), version);
  }

  private static SeamlessConfiguration.Baseline baseline(TagsUpdatePacket tags) {
    return baseline(tags, VERSION);
  }

  private static SeamlessConfiguration.Baseline baseline(TagsUpdatePacket tags,
      ProtocolVersion version) {
    SeamlessConfiguration.Capture capture = new SeamlessConfiguration.Capture(version, true);
    if (!version.lessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      capture.observe(packs(version));
      capture.select(packs(version));
    }
    RegistrySyncPacket packet = registry(1, version);
    try {
      capture.observe(packet);
    } finally {
      packet.release();
    }
    capture.observe(new ActiveFeaturesPacket());
    capture.observe(tags);
    capture.observe(FinishedUpdatePacket.INSTANCE);
    return capture.baseline().orElseThrow();
  }

  private static void feed(SeamlessConfiguration negotiation) {
    feed(negotiation, new TagsUpdatePacket());
  }

  private static void feed(SeamlessConfiguration negotiation, TagsUpdatePacket tags) {
    RegistrySyncPacket packet = registry(1);
    try {
      negotiation.accept(packet);
    } finally {
      packet.release();
    }
    negotiation.accept(new ActiveFeaturesPacket());
    negotiation.accept(tags);
  }

  @Test
  void tagMapOrderingMatchesWithoutMutatingTheWirePacket() {
    Map<String, int[]> firstTags = new LinkedHashMap<>();
    firstTags.put("minecraft:first", new int[] {3, 1, 3});
    firstTags.put("minecraft:empty", new int[0]);
    Map<String, int[]> reversedTags = new LinkedHashMap<>();
    reversedTags.put("minecraft:empty", new int[0]);
    reversedTags.put("minecraft:first", new int[] {3, 1, 3});
    Map<String, Map<String, int[]>> first = new LinkedHashMap<>();
    first.put("minecraft:block", firstTags);
    first.put("minecraft:item", Map.of());
    Map<String, Map<String, int[]>> reversed = new LinkedHashMap<>();
    reversed.put("minecraft:item", Map.of());
    reversed.put("minecraft:block", reversedTags);
    TagsUpdatePacket source = new TagsUpdatePacket(first);
    TagsUpdatePacket destination = new TagsUpdatePacket(reversed);
    byte[] original = SeamlessConfiguration.encode(destination,
        ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_26_2);
    assertFalse(java.util.Arrays.equals(original,
        SeamlessConfiguration.encode(source, ProtocolUtils.Direction.CLIENTBOUND,
            ProtocolVersion.MINECRAFT_26_2)));
    SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(source), VERSION);
    negotiation.accept(packs());
    feed(negotiation, destination);
    negotiation.accept(FinishedUpdatePacket.INSTANCE);
    assertTrue(negotiation.complete());
    assertTrue(negotiation.reorderedTags());
    assertArrayEquals(original, SeamlessConfiguration.encode(destination,
        ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_26_2));
  }

  @Test
  void canonicalTagsStillRejectChangedIdsOrderMultiplicityNamesAndMissingTags() {
    TagsUpdatePacket source = new TagsUpdatePacket(Map.of("minecraft:block",
        Map.of("minecraft:test", new int[] {1, 2, 1}, "minecraft:empty", new int[0])));
    List<TagsUpdatePacket> changes = List.of(
        new TagsUpdatePacket(Map.of("minecraft:block",
            Map.of("minecraft:test", new int[] {1, 3, 1}, "minecraft:empty", new int[0]))),
        new TagsUpdatePacket(Map.of("minecraft:block",
            Map.of("minecraft:test", new int[] {2, 1, 1}, "minecraft:empty", new int[0]))),
        new TagsUpdatePacket(Map.of("minecraft:block",
            Map.of("minecraft:test", new int[] {1, 2}, "minecraft:empty", new int[0]))),
        new TagsUpdatePacket(Map.of("minecraft:block",
            Map.of("minecraft:other", new int[] {1, 2, 1}, "minecraft:empty", new int[0]))),
        new TagsUpdatePacket(Map.of("minecraft:item",
            Map.of("minecraft:test", new int[] {1, 2, 1}, "minecraft:empty", new int[0]))),
        new TagsUpdatePacket(Map.of("minecraft:block", Map.of("minecraft:test", new int[] {1, 2, 1}))));
    for (TagsUpdatePacket changed : changes) {
      SeamlessConfiguration negotiation = new SeamlessConfiguration(baseline(source), VERSION);
      negotiation.accept(packs());
      assertThrows(IllegalStateException.class, () -> feed(negotiation, changed));
      assertFalse(negotiation.complete());
    }
  }

  static KnownPacksPacket packs() {
    return packs(VERSION);
  }

  static KnownPacksPacket packs(ProtocolVersion version) {
    KnownPacksPacket packet = new KnownPacksPacket();
    ByteBuf bytes = Unpooled.wrappedBuffer(new byte[] {0});
    try {
      packet.decode(bytes, ProtocolUtils.Direction.SERVERBOUND, version);
      return packet;
    } finally {
      bytes.release();
    }
  }

  private static RegistrySyncPacket registry(int value) {
    return registry(value, VERSION);
  }

  private static RegistrySyncPacket registry(int value, ProtocolVersion version) {
    RegistrySyncPacket packet = new RegistrySyncPacket();
    // Legacy registry data is one unnamed NBT compound; use a valid compound with a byte value.
    byte[] payload = version.lessThan(ProtocolVersion.MINECRAFT_1_20_5)
        ? new byte[] {10, 1, 0, 1, 'v', (byte) value, 0} : new byte[] {(byte) value};
    ByteBuf bytes = Unpooled.wrappedBuffer(payload);
    try {
      packet.decode(bytes, ProtocolUtils.Direction.CLIENTBOUND, version);
      return packet;
    } finally {
      bytes.release();
    }
  }

  private static RegistrySyncPacket namedRegistry(String name) {
    RegistrySyncPacket packet = new RegistrySyncPacket();
    ByteBuf bytes = Unpooled.buffer();
    try {
      ProtocolUtils.writeString(bytes, name);
      ProtocolUtils.writeVarInt(bytes, 0);
      packet.decode(bytes, ProtocolUtils.Direction.CLIENTBOUND, VERSION);
      return packet;
    } finally {
      bytes.release();
    }
  }

  private static JoinGamePacket join(int entityId, int dimension, int gamemode) {
    JoinGamePacket packet = new JoinGamePacket();
    ByteBuf bytes = Unpooled.buffer();
    try {
      bytes.writeInt(entityId);
      bytes.writeBoolean(false); // hardcore
      ProtocolUtils.writeStringArray(bytes, new String[] {"minecraft:overworld"});
      ProtocolUtils.writeVarInt(bytes, 100); // max players
      ProtocolUtils.writeVarInt(bytes, 12); // view distance
      ProtocolUtils.writeVarInt(bytes, 8); // simulation distance
      bytes.writeBoolean(false); // reduced debug info
      bytes.writeBoolean(true); // show respawn screen
      bytes.writeBoolean(false); // limited crafting
      ProtocolUtils.writeVarInt(bytes, dimension);
      ProtocolUtils.writeString(bytes, "minecraft:overworld");
      bytes.writeLong(1234L);
      bytes.writeByte(gamemode);
      bytes.writeByte(-1);
      bytes.writeBoolean(false); // debug world
      bytes.writeBoolean(false); // flat world
      bytes.writeBoolean(false); // last death position
      ProtocolUtils.writeVarInt(bytes, 0); // portal cooldown
      ProtocolUtils.writeVarInt(bytes, 63); // sea level
      bytes.writeBoolean(true); // online mode
      bytes.writeBoolean(true); // enforce secure chat
      packet.decode(bytes, ProtocolUtils.Direction.CLIENTBOUND, VERSION);
      return packet;
    } finally {
      bytes.release();
    }
  }

  @ParameterizedTest
  @MethodSource("com.velocitypowered.proxy.connection.backend.SeamlessProtocolsTest#band")
  void negotiationCapturedAtAnyEligibleProtocolReplaysAgainstItself(ProtocolVersion version) {
    // The comparison is meant to be version-neutral within the band it admits. This is the whole
    // of that claim: capture at the protocol, replay the same exchange, and expect a match.
    SeamlessConfiguration negotiation =
        new SeamlessConfiguration(baseline(version), version);
    if (!version.lessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      KnownPacksPacket selected =
          (KnownPacksPacket) negotiation.accept(packs(version)).orElseThrow();
      assertArrayEquals(new byte[] {0},
          SeamlessConfiguration.encode(selected, ProtocolUtils.Direction.SERVERBOUND, version));
    }
    RegistrySyncPacket sync = registry(1, version);
    try {
      assertTrue(negotiation.accept(sync).isEmpty());
    } finally {
      sync.release();
    }
    assertTrue(negotiation.accept(new ActiveFeaturesPacket()).isEmpty());
    assertTrue(negotiation.accept(new TagsUpdatePacket()).isEmpty());
    negotiation.accept(FinishedUpdatePacket.INSTANCE);
    assertTrue(negotiation.complete());
  }

  @ParameterizedTest
  @MethodSource("com.velocitypowered.proxy.connection.backend.SeamlessProtocolsTest#band")
  void differentRegistryIsRefusedAtEveryEligibleProtocol(ProtocolVersion version) {
    SeamlessConfiguration negotiation =
        new SeamlessConfiguration(baseline(version), version);
    if (!version.lessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      negotiation.accept(packs(version));
    }
    RegistrySyncPacket sync = registry(2, version);
    try {
      assertThrows(IllegalStateException.class, () -> negotiation.accept(sync));
    } finally {
      sync.release();
    }
    assertFalse(negotiation.complete());
  }
}
