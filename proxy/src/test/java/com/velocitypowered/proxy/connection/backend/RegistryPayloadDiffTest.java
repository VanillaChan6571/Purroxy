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

package com.velocitypowered.proxy.connection.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

/**
 * Telling apart the three reasons two registry payloads can differ. Only one of them could ever be
 * safe to tolerate, so the report has to name which it is rather than that something changed.
 */
class RegistryPayloadDiffTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_2;

  /** Builds a registry payload the way the wire carries one: id, count, then id/flag/NBT entries. */
  private static byte[] payload(List<String> ids, int temperature) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ProtocolUtils.writeString(buffer, SeamlessCaptures.REGISTRY);
      ProtocolUtils.writeVarInt(buffer, ids.size());
      for (String id : ids) {
        ProtocolUtils.writeString(buffer, id);
        buffer.writeBoolean(true);
        ProtocolUtils.writeBinaryTag(buffer, VERSION,
            CompoundBinaryTag.builder().putInt("temperature", temperature).build());
      }
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  @Test
  void identicalPayloadsProduceNoRanges() {
    byte[] one = payload(List.of("minecraft:plains", "minecraft:desert"), 1);
    assertEquals(List.of(), RegistryPayloadDiff.ranges(one, one.clone(), 16));
  }

  @Test
  void decodesEveryEntryWithItsIdentifierAndPresenceFlag() {
    List<RegistryPayloadDiff.Entry> entries =
        RegistryPayloadDiff.entries(payload(List.of("minecraft:plains", "minecraft:desert"), 1),
            VERSION);
    assertEquals(2, entries.size());
    assertEquals("minecraft:plains", entries.get(0).id());
    assertEquals("minecraft:desert", entries.get(1).id());
    assertTrue(entries.get(0).hasData());
    // Spans are contiguous, which is what lets a byte range be attributed to an entry.
    assertEquals(entries.get(0).start() + entries.get(0).length(), entries.get(1).start());
  }

  @Test
  void reorderedEntriesAreReportedAsAnOrderDifference() {
    byte[] expected = payload(List.of("minecraft:plains", "minecraft:desert"), 1);
    byte[] received = payload(List.of("minecraft:desert", "minecraft:plains"), 1);
    String report = RegistryPayloadDiff.describe(expected, received, VERSION, 16);
    // Order assigns the numeric ids the client reads chunks with, so this can never be tolerated.
    assertTrue(report.contains("ORDER differs"), report);
    assertTrue(report.contains("minecraft:plains 0->1"), report);
  }

  @Test
  void changedEntryDataIsReportedAsValueDifference() {
    byte[] expected = payload(List.of("minecraft:plains", "minecraft:desert"), 1);
    byte[] received = payload(List.of("minecraft:plains", "minecraft:desert"), 2);
    String report = RegistryPayloadDiff.describe(expected, received, VERSION, 16);
    assertTrue(report.contains("entry data differs"), report);
    assertTrue(report.contains("minecraft:plains"), report);
    assertTrue(!report.contains("ORDER differs"), report);
  }

  @Test
  void differentEntryCountIsReportedWithBothLengths() {
    byte[] expected = payload(List.of("minecraft:plains", "minecraft:desert"), 1);
    byte[] received = payload(List.of("minecraft:plains"), 1);
    String report = RegistryPayloadDiff.describe(expected, received, VERSION, 16);
    assertTrue(report.contains("lengths"), report);
    assertTrue(report.contains("2 vs 1 entries"), report);
  }

  @Test
  void unreadablePayloadsDegradeToByteRangesRatherThanThrowing() {
    byte[] expected = {1, 2, 3, 4};
    byte[] received = {1, 9, 3, 4};
    String report = RegistryPayloadDiff.describe(expected, received, VERSION, 16);
    // A diagnosis must never become a second fault on a path that is already failing.
    assertTrue(report.contains("[1]"), report);
    assertTrue(report.contains("could not be decoded"), report);
  }

  @Test
  void rangesAreCappedSoWildPayloadCannotFloodTheLog() {
    byte[] expected = new byte[64];
    byte[] received = new byte[64];
    for (int index = 0; index < 64; index += 2) {
      received[index] = 1;
    }
    assertEquals(4, RegistryPayloadDiff.ranges(expected, received, 4).size());
  }
}
