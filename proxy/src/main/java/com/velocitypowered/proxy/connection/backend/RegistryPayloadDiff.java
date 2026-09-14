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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Explains why two registry payloads differ, rather than only that they do.
 *
 * <p>A whole-packet hash says a registry diverged and nothing more, which cannot separate the causes
 * that matter here. Entries in a different order are a correctness problem: a registry's order
 * assigns the numeric ids the client uses to read chunk data, so the same id would mean a different
 * biome on each side. Entries with different values are a content problem. Entries that are
 * semantically equal but serialize differently are the only case a comparison could ever safely
 * tolerate.
 *
 * <p>Every conclusion is qualified by how much was actually decoded. A partial parse can prove that
 * something differs but never that something matches, so an incomplete decode reports that it could
 * not verify order rather than implying order is fine. Everything is a pure function of two byte
 * arrays, and nothing here is allowed to throw: it runs only to explain a failure already decided.
 */
final class RegistryPayloadDiff {

  /** One decoded registry entry, with the span it occupied so byte ranges can be attributed. */
  record Entry(String id, boolean hasData, int start, int length, int dataHash, int dataStart) {
  }

  /**
   * The outcome of decoding one payload's entry list. {@code complete} is true only when the
   * declared count was read in full and nothing was left over, which is the precondition for saying
   * anything at all about ordering.
   */
  record Decode(List<Entry> entries, int declared, boolean complete, String problem) {
  }

  /** How two entries' data compared, kept distinct because each outcome means something different. */
  enum FieldResult {
    /** Decoded on both sides and every field matched: the bytes differ, the meaning does not. */
    SEMANTICALLY_EQUAL,
    /** Decoded on both sides and specific paths differ. */
    CHANGED,
    /** One or both sides could not be decoded, so nothing is known about the fields. */
    UNDECODABLE,
    /** Differences were found and the limit was reached, so more may exist beyond those listed. */
    TRUNCATED
  }

  record FieldDiff(FieldResult result, List<String> paths) {
    String describe() {
      return switch (result) {
        case SEMANTICALLY_EQUAL -> " (values semantically equal, serialization differs only)";
        case UNDECODABLE -> " (data could not be decoded, fields unknown)";
        case CHANGED -> " at " + paths;
        case TRUNCATED -> " at " + paths + " and more (comparison truncated)";
      };
    }
  }

  /** A contiguous run of differing bytes, half-open. */
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
   * Decodes a payload's entry list, reporting whether it got through all of it. Anything unreadable
   * ends the walk and is recorded rather than thrown, so a diagnosis never becomes a second fault.
   */
  static Decode decode(byte[] payload, ProtocolVersion protocol) {
    List<Entry> entries = new ArrayList<>();
    int declared = -1;
    ByteBuf buffer = Unpooled.wrappedBuffer(payload);
    try {
      ProtocolUtils.readString(buffer, 256);
      declared = ProtocolUtils.readVarInt(buffer);
      if (declared < 0 || declared > 8192) {
        return new Decode(List.of(), declared, false, "implausible entry count " + declared);
      }
      for (int index = 0; index < declared; index++) {
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
        entries.add(new Entry(id, hasData, start, buffer.readerIndex() - start, dataHash, dataStart));
      }
      if (buffer.isReadable()) {
        // Trailing bytes mean the walk drifted, so the entries read cannot be trusted as the whole.
        return new Decode(List.copyOf(entries), declared, false,
            buffer.readableBytes() + " trailing bytes");
      }
      return new Decode(List.copyOf(entries), declared, true, "");
    } catch (RuntimeException unreadable) {
      return new Decode(List.copyOf(entries), declared, false,
          "stopped after " + entries.size() + " of " + declared + " entries");
    } finally {
      buffer.release();
    }
  }

  private static int hash(byte[] payload, int from, int to) {
    int result = 1;
    for (int index = from; index < to && index < payload.length; index++) {
      result = 31 * result + payload[index];
    }
    return result;
  }

  /**
   * Names what differs between two payloads of the same registry, in the terms that decide whether a
   * seamless switch could ever be allowed, and states plainly what it could not check.
   */
  static String describe(byte[] expected, byte[] received, ProtocolVersion protocol, int limit) {
    StringBuilder report = new StringBuilder();
    report.append("byte ranges ").append(ranges(expected, received, limit));
    if (expected.length != received.length) {
      report.append(" (lengths ").append(expected.length).append(" vs ").append(received.length)
          .append(')');
    }
    Decode before = decode(expected, protocol);
    Decode after = decode(received, protocol);
    report.append("; ").append(before.entries().size()).append(" vs ").append(after.entries().size())
        .append(" entries read");
    if (!before.complete() || !after.complete()) {
      // A partial parse can prove a difference but never an absence of one. Say so rather than
      // letting silence about ordering read as confirmation that ordering is fine.
      return report.append("; INCOMPLETE decode (baseline: ")
          .append(before.complete() ? "ok" : before.problem()).append(", destination: ")
          .append(after.complete() ? "ok" : after.problem())
          .append("); entry order NOT verified").toString();
    }

    Map<String, Integer> beforePositions = positions(before.entries());
    Map<String, Integer> afterPositions = positions(after.entries());
    List<String> reordered = new ArrayList<>();
    List<String> changed = new ArrayList<>();
    List<String> presence = new ArrayList<>();
    List<String> serialization = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    for (Entry entry : before.entries()) {
      Integer moved = afterPositions.get(entry.id());
      if (moved == null) {
        reordered.add(entry.id() + " missing");
        continue;
      }
      if (!moved.equals(beforePositions.get(entry.id())) && reordered.size() < limit) {
        reordered.add(entry.id() + " " + beforePositions.get(entry.id()) + "->" + moved);
      }
      Entry other = after.entries().get(moved);
      if (entry.hasData() != other.hasData()) {
        presence.add(entry.id() + " data " + entry.hasData() + "->" + other.hasData());
      } else if (entry.hasData() && entry.dataHash() != other.dataHash()) {
        FieldDiff diff = fields(expected, entry, received, other, protocol, limit);
        switch (diff.result()) {
          case SEMANTICALLY_EQUAL -> serialization.add(entry.id());
          case UNDECODABLE -> unreadable.add(entry.id());
          default -> changed.add(entry.id() + diff.describe());
        }
      }
    }
    for (Entry entry : after.entries()) {
      if (!beforePositions.containsKey(entry.id())) {
        reordered.add(entry.id() + " added");
      }
    }

    if (!reordered.isEmpty()) {
      // Order is load-bearing: it assigns the ids the client reads chunks with.
      report.append("; ORDER differs: ").append(capped(reordered, limit));
    }
    if (!presence.isEmpty()) {
      report.append("; data-presence differs: ").append(capped(presence, limit));
    }
    if (!changed.isEmpty()) {
      report.append("; VALUES differ: ").append(capped(changed, limit));
    }
    if (!serialization.isEmpty()) {
      report.append("; serialization only (semantically equal): ").append(capped(serialization, limit));
    }
    if (!unreadable.isEmpty()) {
      report.append("; data undecodable, fields unknown: ").append(capped(unreadable, limit));
    }
    if (reordered.isEmpty() && presence.isEmpty() && changed.isEmpty() && serialization.isEmpty()
        && unreadable.isEmpty()) {
      report.append("; fully decoded on both sides and no entry difference found");
    }
    return report.toString();
  }

  private static List<String> capped(List<String> values, int limit) {
    return values.size() <= limit ? values
        : new ArrayList<>(values.subList(0, limit)) {
          @Override
          public String toString() {
            return super.toString() + " (+" + (values.size() - limit) + " more)";
          }
        };
  }

  /** The NBT paths on which one entry's data differs, and how much of that is actually known. */
  private static FieldDiff fields(byte[] expected, Entry before, byte[] received, Entry after,
      ProtocolVersion protocol, int limit) {
    if (before.dataStart() < 0 || after.dataStart() < 0) {
      return new FieldDiff(FieldResult.UNDECODABLE, List.of());
    }
    BinaryTag one = tagAt(expected, before.dataStart(), protocol);
    BinaryTag two = tagAt(received, after.dataStart(), protocol);
    if (one == null || two == null) {
      return new FieldDiff(FieldResult.UNDECODABLE, List.of());
    }
    List<String> paths = new ArrayList<>();
    boolean truncated = compare("", one, two, paths, limit);
    if (paths.isEmpty()) {
      // Decoded fully on both sides, every field equal: the difference is in serialization alone.
      return new FieldDiff(FieldResult.SEMANTICALLY_EQUAL, List.of());
    }
    return new FieldDiff(truncated ? FieldResult.TRUNCATED : FieldResult.CHANGED, List.copyOf(paths));
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

  /**
   * Walks two tags together, recording every path whose value differs.
   *
   * @return whether the walk stopped early, so the caller can say that more may differ
   */
  private static boolean compare(String path, BinaryTag one, BinaryTag two, List<String> paths,
      int limit) {
    if (paths.size() >= limit) {
      return true;
    }
    if (path.chars().filter(character -> character == '.').count() > 8) {
      paths.add(path + " (too deep to compare)");
      return true;
    }
    if (one instanceof CompoundBinaryTag first && two instanceof CompoundBinaryTag second) {
      Set<String> keys = new LinkedHashSet<>(first.keySet());
      keys.addAll(second.keySet());
      boolean truncated = false;
      for (String key : keys) {
        BinaryTag left = first.get(key);
        BinaryTag right = second.get(key);
        String child = path.isEmpty() ? key : path + '.' + key;
        if (left == null || right == null) {
          paths.add(child + (left == null ? " added" : " removed"));
          truncated |= paths.size() >= limit;
        } else {
          truncated |= compare(child, left, right, paths, limit);
        }
      }
      return truncated;
    }
    if (!one.equals(two)) {
      // Named, not printed: a biome's values can be long, and the path is what locates it.
      paths.add(path.isEmpty() ? "<root>" : path);
    }
    return false;
  }

  private static Map<String, Integer> positions(List<Entry> entries) {
    Map<String, Integer> positions = new LinkedHashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      positions.putIfAbsent(entries.get(index).id(), index);
    }
    return positions;
  }
}
