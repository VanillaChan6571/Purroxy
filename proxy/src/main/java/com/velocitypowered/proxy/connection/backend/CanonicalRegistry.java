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
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A fingerprint of a registry payload that ignores NBT compound-key order and nothing else.
 *
 * <p>Two servers can describe the same registry with the same values written in a different key
 * order. That difference is invisible to a client, because a seamless switch forwards none of it:
 * the client keeps the configuration it already holds, and a registry's numeric ids come from entry
 * order, which this preserves exactly. Every other property is load-bearing and is kept: the
 * registry identifier, the entry count and order, each entry's data-presence flag, every tag's type
 * and value, list order, and array contents.
 *
 * <p>Parsing is done over the raw bytes rather than through a tag reader, because a reader collapses
 * duplicate keys into one and would let a malformed payload be declared equivalent to a well-formed
 * one. Anything malformed, truncated, duplicated or of an unrecognised type yields no fingerprint at
 * all, which leaves the caller comparing raw bytes and therefore refusing. The input is only ever
 * read: the original packet bytes are never touched.
 */
final class CanonicalRegistry {

  private static final int MAX_ENTRIES = 8192;

  private CanonicalRegistry() {
  }

  /**
   * The canonical digest of a registry payload, or empty when one cannot be produced safely.
   *
   * <p>Empty is not a failure to report: it means this payload can only be compared byte for byte.
   */
  static Optional<byte[]> digest(byte[] payload, ProtocolVersion protocol) {
    if (protocol.lessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      // The shape walked below - identifier, entry count, then a flag and NBT per entry - is the
      // per-registry sync introduced in 1.20.5. Before it a single packet carried the whole
      // registry set as one named-root tag, which this walk would either fail on or, worse, read
      // far enough into to produce a digest that means nothing. Refusing leaves those protocols
      // compared byte for byte, which is the honest answer for a shape this cannot describe.
      return Optional.empty();
    }
    ByteBuf in = Unpooled.wrappedBuffer(payload);
    ByteBuf out = Unpooled.buffer(payload.length + 64);
    try {
      writeBytes(out, ProtocolUtils.readString(in, 256).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      int count = ProtocolUtils.readVarInt(in);
      if (count < 0 || count > MAX_ENTRIES) {
        return Optional.empty();
      }
      out.writeInt(count);
      for (int index = 0; index < count; index++) {
        writeBytes(out,
            ProtocolUtils.readString(in, 256).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean hasData = in.readBoolean();
        out.writeBoolean(hasData);
        if (hasData && !tag(in, out)) {
          return Optional.empty();
        }
      }
      if (in.isReadable()) {
        // Trailing bytes mean the walk drifted; nothing read before that can be trusted as whole.
        return Optional.empty();
      }
      return Optional.of(MessageDigest.getInstance("SHA-256").digest(ByteBufUtil.getBytes(out)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    } catch (RuntimeException malformed) {
      return Optional.empty();
    } finally {
      in.release();
      out.release();
    }
  }

  /** Reads one typed tag and writes its canonical form. False means the payload is unusable. */
  private static boolean tag(ByteBuf in, ByteBuf out) {
    int type = in.readUnsignedByte();
    out.writeByte(type);
    return payload(type, in, out);
  }

  private static boolean payload(int type, ByteBuf in, ByteBuf out) {
    switch (type) {
      // Fixed-width values are copied rather than read and rewritten, so an exact bit pattern -
      // a NaN payload, say - survives instead of being normalised into a different one.
      case 1 -> out.writeBytes(in, 1);
      case 2 -> out.writeBytes(in, 2);
      case 3, 5 -> out.writeBytes(in, 4);
      case 4, 6 -> out.writeBytes(in, 8);
      case 7 -> {
        return array(in, out, 1);
      }
      case 8 -> writeBytes(out, nbtString(in));
      case 9 -> {
        return list(in, out);
      }
      case 10 -> {
        return compound(in, out);
      }
      case 11 -> {
        return array(in, out, 4);
      }
      case 12 -> {
        return array(in, out, 8);
      }
      // TAG_End as a value, or any type this build does not know, is refused rather than guessed.
      default -> {
        return false;
      }
    }
    return true;
  }

  private static boolean array(ByteBuf in, ByteBuf out, int width) {
    int length = in.readInt();
    if (length < 0 || (long) length * width > in.readableBytes()) {
      return false;
    }
    out.writeInt(length);
    // Contents are copied in order: an array's order is part of its value.
    out.writeBytes(in, length * width);
    return true;
  }

  private static boolean list(ByteBuf in, ByteBuf out) {
    int element = in.readUnsignedByte();
    int length = in.readInt();
    if (length < 0 || length > MAX_ENTRIES * 64) {
      return false;
    }
    out.writeByte(element);
    out.writeInt(length);
    for (int index = 0; index < length; index++) {
      // List order is significant and is preserved; only compound keys are reordered.
      if (!payload(element, in, out)) {
        return false;
      }
    }
    return true;
  }

  private static boolean compound(ByteBuf in, ByteBuf out) {
    List<byte[][]> children = new ArrayList<>();
    while (true) {
      int type = in.readUnsignedByte();
      if (type == 0) {
        break;
      }
      byte[] name = nbtString(in);
      ByteBuf child = Unpooled.buffer(64);
      try {
        child.writeByte(type);
        if (!payload(type, in, child)) {
          return false;
        }
        children.add(new byte[][] {name, ByteBufUtil.getBytes(child)});
      } finally {
        child.release();
      }
    }
    children.sort((one, two) -> Arrays.compareUnsigned(one[0], two[0]));
    for (int index = 1; index < children.size(); index++) {
      if (Arrays.equals(children.get(index - 1)[0], children.get(index)[0])) {
        // A reader would silently keep one of them; two payloads differing only in which survived
        // would then look equivalent. Refuse instead.
        return false;
      }
    }
    out.writeInt(children.size());
    for (byte[][] child : children) {
      writeBytes(out, child[0]);
      out.writeBytes(child[1]);
    }
    return true;
  }

  /** An NBT string: an unsigned short length and that many bytes, returned undecoded. */
  private static byte[] nbtString(ByteBuf in) {
    int length = in.readUnsignedShort();
    byte[] value = new byte[length];
    in.readBytes(value);
    return value;
  }

  /** Length-prefixed so no concatenation of fields can collide with a different one. */
  private static void writeBytes(ByteBuf out, byte[] value) {
    out.writeInt(value.length);
    out.writeBytes(value);
  }
}
