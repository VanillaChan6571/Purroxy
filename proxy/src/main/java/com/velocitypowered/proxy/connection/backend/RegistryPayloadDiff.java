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

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Explains why two registry payloads differ, rather than only that they do.
 *
 * <p>A whole-packet hash says a registry diverged and nothing more, which cannot separate the three
 * causes that matter here. Entries in a different order are a correctness problem: a registry's
 * order assigns the numeric ids the client uses to read chunk data, so the same id would mean a
 * different biome on each side. Entries with different values are a content problem. Identical
 * entries whose bytes differ are a serialization problem, and the only one of the three that a
 * comparison could ever safely tolerate.
 *
 * <p>Everything here is a pure function of two byte arrays so it can be exercised without a
 * connection, and it never mutates or retains what it is given.
 */
final class RegistryPayloadDiff {

  /** One decoded registry entry, with the span it occupied so byte ranges can be attributed. */
  record Entry(String id, boolean hasData, int start, int length, int dataHash, int dataStart) {
  }

  /** A contiguous run of differing bytes, half-open, with the value on each side. */
  record Range(int start, int end) {
    @Override
    public String toString() {
      return end - start == 1 ? "[" + start + "]" : "[" + start + ".." + (end - 1) + "]";
    }
  }

  private RegistryPayloadDiff() {
  }

  /**
   * Contiguous runs where the two payloads differ. Trailing bytes of a longer payload count as one
   * final run, so a truncated or extended payload is reported rather than silently ignored.
   */
  static List<Range> ranges(byte[] expected, byte[] received, int limit) {
    List<Range> ranges = new ArrayList<>();
    int shared = Math.min(expected.length, received.length);
    int index = 0;
    while (index < shared && ranges.size() < limit) {
      if (expected[index] == received[index]) {
        index++;
        continue;
      }
      int start = index;
      while (index < shared && expected[index] != received[index]) {
        index++;
      }
      ranges.add(new Range(start, index));
    }
    if (expected.length != received.length && ranges.size() < limit) {
      ranges.add(new Range(shared, Math.max(expected.length, received.length)));
    }
    return ranges;
  }

  /**
   * Decodes the entry list of a registry payload. Returns an empty list rather than throwing on
   * anything unreadable: this runs only to explain a failure that has already been decided, and must
   * never turn a diagnosis into a second fault.
   */
  static List<Entry> entries(byte[] payload, ProtocolVersion protocol) {
    List<Entry> entries = new ArrayList<>();
    ByteBuf buffer = Unpooled.wrappedBuffer(payload);
    try {
      ProtocolUtils.readString(buffer, 256);
      int count = ProtocolUtils.readVarInt(buffer);
      if (count < 0 || count > 8192) {
        return List.of();
      }
      for (int index = 0; index < count && buffer.isReadable(); index++) {
        int start = buffer.readerIndex();
        String id = ProtocolUtils.readString(buffer, 256);
        boolean hasData = buffer.readBoolean();
        int dataHash = 0;
        int dataStart = -1;
        if (hasData) {
          dataStart = buffer.readerIndex();
          ProtocolUtils.readBinaryTag(buffer, protocol, null);
          dataHash = hash(payload, dataStart, buffer.readerIndex());
        }
        entries.add(new Entry(id, hasData, start, buffer.readerIndex() - start, dataHash,
            dataStart));
      }
    } catch (RuntimeException unreadable) {
      return List.copyOf(entries);
    } finally {
      buffer.release();
    }
    return List.copyOf(entries);
  }

  private static int hash(byte[] payload, int from, int to) {
    int result = 1;
    for (int index = from; index < to && index < payload.length; index++) {
      result = 31 * result + payload[index];
    }
    return result;
  }

  /**
   * Names what actually differs between two payloads of the same registry, in the terms that decide
   * whether a seamless switch could ever be allowed.
   */
  static String describe(byte[] expected, byte[] received, ProtocolVersion protocol, int limit) {
    StringBuilder report = new StringBuilder();
    report.append("byte ranges ").append(ranges(expected, received, limit));
    if (expected.length != received.length) {
      report.append(" (lengths ").append(expected.length).append(" vs ").append(received.length)
          .append(')');
    }
    List<Entry> before = entries(expected, protocol);
    List<Entry> after = entries(received, protocol);
    if (before.isEmpty() || after.isEmpty()) {
      return report.append("; entries could not be decoded on both sides").toString();
    }
    report.append("; ").append(before.size()).append(" vs ").append(after.size()).append(" entries");

    Map<String, Integer> beforePositions = positions(before);
    Map<String, Integer> afterPositions = positions(after);
    List<String> reordered = new ArrayList<>();
    List<String> changed = new ArrayList<>();
    List<String> presence = new ArrayList<>();
    for (Entry entry : before) {
      Integer moved = afterPositions.get(entry.id());
      if (moved == null) {
        reordered.add(entry.id() + " missing");
        continue;
      }
      if (!moved.equals(beforePositions.get(entry.id())) && reordered.size() < limit) {
        reordered.add(entry.id() + " " + beforePositions.get(entry.id()) + "->" + moved);
      }
      Entry other = after.get(moved);
      if (entry.hasData() != other.hasData() && presence.size() < limit) {
        presence.add(entry.id() + " data " + entry.hasData() + "->" + other.hasData());
      } else if (entry.hasData() && entry.dataHash() != other.dataHash() && changed.size() < limit) {
        // Entry level is not actionable on its own: a biome that differs somewhere is a lead,
        // the field that differs is a cause. Decode both sides and name the path.
        changed.add(entry.id() + fields(expected, entry, received, other, protocol, limit));
      }
    }
    for (Entry entry : after) {
      if (!beforePositions.containsKey(entry.id()) && reordered.size() < limit) {
        reordered.add(entry.id() + " added");
      }
    }

    if (!reordered.isEmpty()) {
      // Order is load-bearing: it assigns the ids the client reads chunks with.
      report.append("; ORDER differs: ").append(reordered);
    }
    if (!presence.isEmpty()) {
      report.append("; data-presence differs: ").append(presence);
    }
    if (!changed.isEmpty()) {
      report.append("; entry data differs: ").append(changed);
    }
    if (reordered.isEmpty() && presence.isEmpty() && changed.isEmpty()) {
      // Same entries, same order, same values, different bytes. Only this is serialization alone.
      report.append("; entries identical in order and value - serialization only");
    }
    return report.toString();
  }

  /**
   * The NBT paths on which one entry's data differs, decoded from both payloads. Returns an
   * empty suffix rather than throwing: this already runs on a failing path and must not add a
   * second fault to the first.
   */
  private static String fields(byte[] expected, Entry before, byte[] received, Entry after,
      ProtocolVersion protocol, int limit) {
    if (before.dataStart() < 0 || after.dataStart() < 0) {
      return "";
    }
    BinaryTag one = tagAt(expected, before.dataStart(), protocol);
    BinaryTag two = tagAt(received, after.dataStart(), protocol);
    if (one == null || two == null) {
      return "";
    }
    List<String> paths = new ArrayList<>();
    compare("", one, two, paths, limit);
    return paths.isEmpty() ? "" : " at " + paths;
  }

  private static @Nullable BinaryTag tagAt(byte[] payload, int offset, ProtocolVersion protocol) {
    ByteBuf buffer = Unpooled.wrappedBuffer(payload, offset, payload.length - offset);
    try {
      return ProtocolUtils.readBinaryTag(buffer, protocol, null);
    } catch (RuntimeException unreadable) {
      return null;
    } finally {
      buffer.release();
    }
  }

  /** Walks two tags together, recording every path whose value differs. Bounded on both axes. */
  private static void compare(String path, BinaryTag one, BinaryTag two, List<String> paths,
      int limit) {
    if (paths.size() >= limit || path.chars().filter(c -> c == '.').count() > 6) {
      return;
    }
    if (one instanceof CompoundBinaryTag first && two instanceof CompoundBinaryTag second) {
      java.util.Set<String> keys = new java.util.LinkedHashSet<>(first.keySet());
      keys.addAll(second.keySet());
      for (String key : keys) {
        BinaryTag left = first.get(key);
        BinaryTag right = second.get(key);
        String child = path.isEmpty() ? key : path + '.' + key;
        if (left == null || right == null) {
          paths.add(child + (left == null ? " added" : " removed"));
        } else {
          compare(child, left, right, paths, limit);
        }
      }
      return;
    }
    if (!one.equals(two)) {
      // Named, not printed: a biome's values can be long, and the path is what locates it.
      paths.add(path.isEmpty() ? "<root>" : path);
    }
  }

  private static Map<String, Integer> positions(List<Entry> entries) {
    Map<String, Integer> positions = new LinkedHashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      positions.putIfAbsent(entries.get(index).id(), index);
    }
    return positions;
  }
}
