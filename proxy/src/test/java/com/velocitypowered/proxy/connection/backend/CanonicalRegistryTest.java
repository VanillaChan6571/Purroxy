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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The canonical registry fingerprint must ignore compound-key order and absolutely nothing else.
 * Every other property here is one a client could observe or one whose loss would let a malformed
 * payload pass as a well-formed one.
 */
class CanonicalRegistryTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_2;

  // --- payload construction ------------------------------------------------------------------

  private static byte[] registry(String id, Consumer<ByteBuf> entries, int count) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ProtocolUtils.writeString(buffer, id);
      ProtocolUtils.writeVarInt(buffer, count);
      entries.accept(buffer);
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  private static void entry(ByteBuf buffer, String id, Consumer<ByteBuf> tag) {
    ProtocolUtils.writeString(buffer, id);
    buffer.writeBoolean(true);
    tag.accept(buffer);
  }

  private static void name(ByteBuf buffer, String value) {
    byte[] raw = value.getBytes(StandardCharsets.UTF_8);
    buffer.writeShort(raw.length);
    buffer.writeBytes(raw);
  }

  /** A compound of int-valued keys, written in exactly the order given. */
  private static Consumer<ByteBuf> compound(String... pairs) {
    return buffer -> {
      buffer.writeByte(10);
      for (int index = 0; index < pairs.length; index += 2) {
        buffer.writeByte(3);
        name(buffer, pairs[index]);
        buffer.writeInt(Integer.parseInt(pairs[index + 1]));
      }
      buffer.writeByte(0);
    };
  }

  private static Optional<byte[]> digest(byte[] payload) {
    return CanonicalRegistry.digest(payload, VERSION);
  }

  private static byte[] single(Consumer<ByteBuf> tag) {
    return registry("minecraft:worldgen/biome",
        buffer -> entry(buffer, "minecraft:plains", tag), 1);
  }

  // --- what must be ignored ------------------------------------------------------------------

  @Test
  void compoundKeyOrderIsIgnored() {
    // The one difference a client cannot observe: none of this is forwarded on a seamless switch.
    byte[] one = single(compound("a", "1", "b", "2"));
    byte[] two = single(compound("b", "2", "a", "1"));
    assertFalse(java.util.Arrays.equals(one, two), "payloads should differ on the wire");
    assertArrayEquals(digest(one).orElseThrow(), digest(two).orElseThrow());
  }

  @Test
  void nestedCompoundKeyOrderIsIgnored() {
    Consumer<ByteBuf> first = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(10);
      name(buffer, "effects");
      buffer.writeByte(3);
      name(buffer, "fog");
      buffer.writeInt(7);
      buffer.writeByte(3);
      name(buffer, "sky");
      buffer.writeInt(8);
      buffer.writeByte(0);
      buffer.writeByte(0);
    };
    Consumer<ByteBuf> second = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(10);
      name(buffer, "effects");
      buffer.writeByte(3);
      name(buffer, "sky");
      buffer.writeInt(8);
      buffer.writeByte(3);
      name(buffer, "fog");
      buffer.writeInt(7);
      buffer.writeByte(0);
      buffer.writeByte(0);
    };
    assertArrayEquals(digest(single(first)).orElseThrow(), digest(single(second)).orElseThrow());
  }

  // --- what must be preserved ----------------------------------------------------------------

  @Test
  void changedValuesAreNotIgnored() {
    assertFalse(java.util.Arrays.equals(digest(single(compound("a", "1"))).orElseThrow(),
        digest(single(compound("a", "2"))).orElseThrow()));
  }

  @Test
  void changedTypesAreNotIgnored() {
    Consumer<ByteBuf> asInt = compound("a", "1");
    Consumer<ByteBuf> asLong = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(4);
      name(buffer, "a");
      buffer.writeLong(1L);
      buffer.writeByte(0);
    };
    assertFalse(java.util.Arrays.equals(digest(single(asInt)).orElseThrow(),
        digest(single(asLong)).orElseThrow()));
  }

  @Test
  void reorderedRegistryEntriesAreNotIgnored() {
    // Entry order assigns the numeric ids the client reads chunk data with.
    byte[] one = registry("minecraft:worldgen/biome", buffer -> {
      entry(buffer, "minecraft:plains", compound("a", "1"));
      entry(buffer, "minecraft:desert", compound("a", "1"));
    }, 2);
    byte[] two = registry("minecraft:worldgen/biome", buffer -> {
      entry(buffer, "minecraft:desert", compound("a", "1"));
      entry(buffer, "minecraft:plains", compound("a", "1"));
    }, 2);
    assertFalse(java.util.Arrays.equals(digest(one).orElseThrow(), digest(two).orElseThrow()));
  }

  @Test
  void reorderedListElementsAreNotIgnored() {
    Consumer<ByteBuf> forward = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(9);
      name(buffer, "items");
      buffer.writeByte(3);
      buffer.writeInt(2);
      buffer.writeInt(1);
      buffer.writeInt(2);
      buffer.writeByte(0);
    };
    Consumer<ByteBuf> reversed = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(9);
      name(buffer, "items");
      buffer.writeByte(3);
      buffer.writeInt(2);
      buffer.writeInt(2);
      buffer.writeInt(1);
      buffer.writeByte(0);
    };
    assertFalse(java.util.Arrays.equals(digest(single(forward)).orElseThrow(),
        digest(single(reversed)).orElseThrow()));
  }

  @Test
  void reorderedArrayContentsAreNotIgnored() {
    Consumer<ByteBuf> forward = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(11);
      name(buffer, "ints");
      buffer.writeInt(2);
      buffer.writeInt(1);
      buffer.writeInt(2);
      buffer.writeByte(0);
    };
    Consumer<ByteBuf> reversed = buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(11);
      name(buffer, "ints");
      buffer.writeInt(2);
      buffer.writeInt(2);
      buffer.writeInt(1);
      buffer.writeByte(0);
    };
    assertFalse(java.util.Arrays.equals(digest(single(forward)).orElseThrow(),
        digest(single(reversed)).orElseThrow()));
  }

  @Test
  void dataPresenceFlagIsNotIgnored() {
    byte[] withData = single(compound("a", "1"));
    byte[] without = registry("minecraft:worldgen/biome", buffer -> {
      ProtocolUtils.writeString(buffer, "minecraft:plains");
      buffer.writeBoolean(false);
    }, 1);
    assertFalse(java.util.Arrays.equals(digest(withData).orElseThrow(),
        digest(without).orElseThrow()));
  }

  @Test
  void theRegistryIdentifierIsNotIgnored() {
    byte[] biome = registry("minecraft:worldgen/biome",
        buffer -> entry(buffer, "minecraft:plains", compound("a", "1")), 1);
    byte[] other = registry("minecraft:dimension_type",
        buffer -> entry(buffer, "minecraft:plains", compound("a", "1")), 1);
    assertFalse(java.util.Arrays.equals(digest(biome).orElseThrow(), digest(other).orElseThrow()));
  }

  // --- what must be refused ------------------------------------------------------------------

  @Test
  void duplicateCompoundKeysAreRefusedRatherThanCollapsed() {
    // A tag reader keeps one of them; two payloads differing in which survived would then look
    // identical. Refusing leaves the caller comparing raw bytes, which cannot be fooled.
    assertTrue(digest(single(compound("a", "1", "a", "2"))).isEmpty());
  }

  @Test
  void unknownTagTypeIsRefused() {
    assertTrue(digest(single(buffer -> {
      buffer.writeByte(10);
      buffer.writeByte(99); // No such tag type.
      name(buffer, "a");
      buffer.writeInt(1);
      buffer.writeByte(0);
    })).isEmpty());
  }

  @Test
  void truncatedPayloadIsRefused() {
    byte[] good = single(compound("a", "1"));
    assertTrue(digest(java.util.Arrays.copyOf(good, good.length - 3)).isEmpty());
  }

  @Test
  void trailingBytesAreRefused() {
    byte[] good = single(compound("a", "1"));
    byte[] extended = java.util.Arrays.copyOf(good, good.length + 2);
    assertTrue(digest(extended).isEmpty());
  }

  @ParameterizedTest
  @EnumSource(value = ProtocolVersion.class,
      names = {"MINECRAFT_1_19_4", "MINECRAFT_1_20", "MINECRAFT_1_20_2", "MINECRAFT_1_20_3"})
  void protocolsBeforeThePerRegistrySyncAreRefused(ProtocolVersion protocol) {
    // 1.20.2 and 1.20.3 do have a configuration phase, but their registry sync is one packet
    // carrying every registry as a named root tag, not the identifier-and-entries shape walked
    // here. Reading one as the other would produce a digest that means nothing, so refuse and
    // leave the caller comparing raw bytes.
    assertTrue(CanonicalRegistry.digest(single(compound("a", "1")), protocol).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("com.velocitypowered.proxy.connection.backend.SeamlessProtocolsTest#band")
  void everyEligibleProtocolCanonicalisesTheSamePayloadTheSameWay(ProtocolVersion protocol) {
    // The shape is identical from 1.20.5 up, so a payload's canonical digest must not depend on
    // which protocol in the band it was read at - otherwise two hubs could never match.
    byte[] payload = single(compound("b", "2", "a", "1"));
    assertArrayEquals(digest(payload).orElseThrow(),
        CanonicalRegistry.digest(payload, protocol).orElseThrow());
  }

  @Test
  void theOriginalPayloadIsNeverModified() {
    byte[] payload = single(compound("b", "2", "a", "1"));
    byte[] copy = payload.clone();
    digest(payload);
    assertArrayEquals(copy, payload);
  }
}
