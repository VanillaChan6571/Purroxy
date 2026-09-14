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
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PingIdentifyPacket;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Strict native configuration negotiation without sending configuration packets to a PLAY client. */
public final class SeamlessConfiguration {
  private static final int MAX_BYTES = 8 * 1024 * 1024;
  private static final int MAX_PACKETS = 1024;

  private record Fingerprint(Class<?> type, byte[] digest, byte[] wireDigest, int size, String detail) {
    boolean matches(Fingerprint other) {
      return type == other.type && MessageDigest.isEqual(digest, other.digest);
    }

    String describe() {
      return type.getSimpleName() + detail + ", " + size + " bytes, sha256="
          + java.util.HexFormat.of().formatHex(digest, 0, 8);
    }
  }

  /** Immutable backend baseline from this client's normal negotiation, pending live-state qualification. */
  public static final class Baseline {
    private final List<Fingerprint> packets;
    private final byte[] knownPacksReply;
    private final ProtocolVersion protocol;

    private Baseline(List<Fingerprint> packets, byte[] reply, ProtocolVersion protocol) {
      this.packets = List.copyOf(packets);
      this.knownPacksReply = reply.clone();
      this.protocol = protocol;
    }

    /** The protocol this capture was taken at. Bytes only mean the same thing at that version. */
    public ProtocolVersion protocol() {
      return protocol;
    }
  }

  /**
   * Where a retained payload goes when an operator has asked for one. Absent by default: the
   * comparison itself only ever needs hashes, and payloads are large enough that holding them
   * has to be a deliberate, narrow choice rather than a side effect of capturing.
   */
  @FunctionalInterface
  interface PayloadSink {
    PayloadSink NONE = (registry, payload, baseline) -> { };

    void payload(String registry, byte[] payload, boolean baseline);
  }

  /** Reads the registry identifier a payload starts with, or empty when it is not a registry. */
  static String registryOf(MinecraftPacket packet, byte[] payload) {
    String described = describePayload(packet, payload);
    int marker = described.indexOf("registry=");
    return marker < 0 ? "" : described.substring(marker + "registry=".length());
  }

  /** Client-significant JoinGame state, excluding only the player entity id. */
  public static final class JoinBaseline {
    private final byte[] digest;

    private JoinBaseline(byte[] digest) {
      this.digest = digest;
    }

    public boolean matches(JoinGamePacket packet, ProtocolVersion version) {
      return MessageDigest.isEqual(digest, joinDigest(packet, version));
    }
  }

  /** Captures the PLAY state that must remain unchanged when JoinGame and Respawn are suppressed. */
  public static JoinBaseline captureJoin(JoinGamePacket packet, ProtocolVersion version) {
    return new JoinBaseline(joinDigest(packet, version));
  }

  /** Records only supported exchanges; an unsupported exchange invalidates the entire capture. */
  static final class Capture {
    private final List<Fingerprint> packets = new ArrayList<>();
    private int bytes;
    private boolean invalid;
    private boolean finished;
    private boolean offered;
    private boolean features;
    private boolean tags;
    private int registries;
    private byte[] knownPacksReply;

    private final ProtocolVersion protocol;
    private PayloadSink sink = PayloadSink.NONE;

    /** Arms retention for this capture. Only ever called for an operator-selected player. */
    void sink(PayloadSink sink) {
      this.sink = sink;
    }

    Capture(ProtocolVersion version, boolean eligible) {
      // Eligibility is decided once, by the seamless policy, and handed in. This only records
      // the protocol the bytes were produced at, so a later comparison can insist on the same.
      this.protocol = version;
      invalid = !eligible;
    }

    void observe(MinecraftPacket packet) {
      if (invalid) {
        return;
      }
      try {
        if (finished) {
          throw new IllegalStateException("Configuration continued after finish");
        }
        if (packet instanceof KeepAlivePacket || packet instanceof PingIdentifyPacket) {
          return;
        }
        if (packet instanceof FinishedUpdatePacket) {
          finished = true;
          return;
        }
        if (!supported(packet)) {
          throw new IllegalStateException("Unsupported configuration exchange");
        }
        if (packet instanceof KnownPacksPacket) {
          if (offered) {
            throw new IllegalStateException("Repeated known-packs negotiation");
          }
          offered = true;
        } else if (packet instanceof ActiveFeaturesPacket) {
          if (features) {
            throw new IllegalStateException("Repeated active features");
          }
          features = true;
        } else if (packet instanceof TagsUpdatePacket) {
          if (tags) {
            throw new IllegalStateException("Repeated tags");
          }
          tags = true;
        } else if (packet instanceof RegistrySyncPacket) {
          if (knownPacksReply == null) {
            throw new IllegalStateException("Registries arrived before known-packs selection");
          }
          registries++;
        }
        byte[] payload = encode(packet, ProtocolUtils.Direction.CLIENTBOUND, protocol);
        bytes = Math.addExact(bytes, payload.length);
        if (bytes > MAX_BYTES || packets.size() >= MAX_PACKETS) {
          throw new IllegalStateException("Configuration capture limit exceeded");
        }
        packets.add(fingerprint(packet, payload));
        sink.payload(registryOf(packet, payload), payload, true);
      } catch (RuntimeException failure) {
        invalidate();
      }
    }

    void select(KnownPacksPacket reply) {
      if (invalid) {
        return;
      }
      if (!offered || finished || knownPacksReply != null) {
        invalidate();
        return;
      }
      try {
        knownPacksReply = encode(reply, ProtocolUtils.Direction.SERVERBOUND, protocol);
      } catch (RuntimeException failure) {
        invalidate();
      }
    }

    void invalidate() {
      invalid = true;
      packets.clear();
      knownPacksReply = null;
    }

    public Optional<Baseline> baseline() {
      return !invalid && finished && features && tags && registries > 0 && knownPacksReply != null
          ? Optional.of(new Baseline(packets, knownPacksReply, protocol)) : Optional.empty();
    }
  }

  private final Baseline baseline;
  private int cursor;
  private int bytes;
  private boolean finished;
  private boolean failed;
  private boolean reorderedTags;

  private final ProtocolVersion protocol;
  private PayloadSink sink = PayloadSink.NONE;

  /** Arms reporting for this comparison. Only ever called for an operator-selected player. */
  void sink(PayloadSink sink) {
    this.sink = sink;
  }

  SeamlessConfiguration(Baseline baseline, ProtocolVersion version) {
    if (baseline.protocol() != version) {
      // Packet bytes are only comparable within one protocol. Refusing here is what stops a
      // baseline captured before a client changed version from being compared against a newer one.
      throw new IllegalArgumentException("Seamless baseline was captured at "
          + baseline.protocol() + ", not " + version);
    }
    this.baseline = baseline;
    this.protocol = version;
  }

  /** Returns backend acknowledgments only. Any mismatch permanently invalidates this attempt. */
  Optional<MinecraftPacket> accept(MinecraftPacket packet) {
    try {
      if (failed || finished) {
        throw new IllegalStateException("Detached configuration is already terminal");
      }
      if (packet instanceof KeepAlivePacket || packet instanceof PingIdentifyPacket) {
        return Optional.of(packet);
      }
      if (packet instanceof FinishedUpdatePacket) {
        if (cursor != baseline.packets.size()) {
          throw new IllegalStateException("Destination omitted configuration data");
        }
        finished = true;
        return Optional.of(FinishedUpdatePacket.INSTANCE);
      }
      if (!supported(packet) || cursor >= baseline.packets.size()) {
        throw new IllegalStateException("Unsupported destination configuration exchange");
      }
      byte[] payload = encode(packet, ProtocolUtils.Direction.CLIENTBOUND, protocol);
      bytes = Math.addExact(bytes, payload.length);
      if (bytes > MAX_BYTES) {
        throw new IllegalStateException("Destination configuration exceeds capture limit");
      }
      Fingerprint expected = baseline.packets.get(cursor);
      Fingerprint actual = fingerprint(packet, payload);
      if (!expected.matches(actual)) {
        // Handed over before the throw, so the side that diverged is available to explain it.
        sink.payload(registryOf(packet, payload), payload, false);
        throw new IllegalStateException("Configuration mismatch at data packet #" + (cursor + 1)
            + ": expected [" + expected.describe() + "], received [" + actual.describe() + "]");
      }
      if (packet instanceof TagsUpdatePacket && !MessageDigest.isEqual(expected.wireDigest, actual.wireDigest)) {
        reorderedTags = true;
      }
      cursor++;
      if (packet instanceof KnownPacksPacket) {
        KnownPacksPacket reply = new KnownPacksPacket();
        ByteBuf buffer = Unpooled.wrappedBuffer(baseline.knownPacksReply);
        try {
          reply.decode(buffer, ProtocolUtils.Direction.SERVERBOUND, protocol);
        } finally {
          buffer.release();
        }
        return Optional.of(reply);
      }
      return Optional.empty();
    } catch (RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  boolean complete() {
    return finished && !failed;
  }

  boolean matchesSelection(Baseline observed) {
    return MessageDigest.isEqual(baseline.knownPacksReply, observed.knownPacksReply);
  }

  boolean reorderedTags() {
    return reorderedTags;
  }

  private static boolean supported(MinecraftPacket packet) {
    return packet instanceof KnownPacksPacket || packet instanceof RegistrySyncPacket
        || packet instanceof ActiveFeaturesPacket || packet instanceof TagsUpdatePacket
        || packet instanceof com.velocitypowered.proxy.protocol.packet.PluginMessagePacket message
            && com.velocitypowered.proxy.protocol.util.PluginMessageUtil.isMcBrand(message)
        || packet instanceof com.velocitypowered.proxy.protocol.packet.config.ClientboundCustomReportDetailsPacket
        || packet instanceof com.velocitypowered.proxy.protocol.packet.config.ClientboundServerLinksPacket;
  }

  private static Fingerprint fingerprint(MinecraftPacket packet, byte[] payload) {
    try {
      byte[] wireDigest = MessageDigest.getInstance("SHA-256").digest(payload);
      byte[] comparisonDigest = wireDigest;
      if (packet instanceof TagsUpdatePacket tags) {
        ByteBuf canonical = Unpooled.buffer(256, MAX_BYTES);
        try {
          tags.encodeCanonical(canonical);
          comparisonDigest = MessageDigest.getInstance("SHA-256").digest(ByteBufUtil.getBytes(canonical));
        } finally {
          canonical.release();
        }
      }
      return new Fingerprint(packet.getClass(), comparisonDigest, wireDigest,
          payload.length, describePayload(packet, payload));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String describePayload(MinecraftPacket packet, byte[] payload) {
    if (!(packet instanceof RegistrySyncPacket)) {
      return "";
    }
    // Native 26.2 encodes the registry identifier before the entry list and its NBT.
    // Read only that bounded prefix, leaving the original deferred packet untouched.
    ByteBuf buffer = Unpooled.wrappedBuffer(payload);
    try {
      String registry = ProtocolUtils.readString(buffer, 256);
      return registry.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? " registry=" + registry : " registry=<invalid>";
    } catch (RuntimeException malformed) {
      return " registry=<unreadable>";
    } finally {
      buffer.release();
    }
  }

  static byte[] encode(MinecraftPacket packet, ProtocolUtils.Direction direction,
      ProtocolVersion protocol) {
    // Deferred packets' encoders consume their content. Copy by index to preserve the live packet.
    if (packet instanceof ByteBufHolder holder) {
      if (holder.content().readableBytes() > MAX_BYTES) {
        throw new IllegalArgumentException("Oversized configuration packet");
      }
      return ByteBufUtil.getBytes(holder.content());
    }
    ByteBuf buffer = Unpooled.buffer(256, MAX_BYTES);
    try {
      packet.encode(buffer, direction, protocol);
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  private static byte[] joinDigest(JoinGamePacket packet, ProtocolVersion version) {
    ByteBuf buffer = Unpooled.buffer(256, MAX_BYTES);
    try {
      packet.encode(buffer, ProtocolUtils.Direction.CLIENTBOUND, version);
      if (buffer.readableBytes() < Integer.BYTES) {
        throw new IllegalStateException("JoinGame packet omitted its entity id");
      }
      // The first field is the entity id, negotiated separately by the handoff protocol. Everything
      // else must remain byte-identical because the client will not receive this destination packet.
      byte[] state = ByteBufUtil.getBytes(buffer, buffer.readerIndex() + Integer.BYTES,
          buffer.readableBytes() - Integer.BYTES, false);
      try {
        return MessageDigest.getInstance("SHA-256").digest(state);
      } catch (NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    } finally {
      buffer.release();
    }
  }
}
