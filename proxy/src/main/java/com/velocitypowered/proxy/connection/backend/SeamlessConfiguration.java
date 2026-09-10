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
final class SeamlessConfiguration {
  private static final int MAX_BYTES = 8 * 1024 * 1024;
  private static final int MAX_PACKETS = 1024;

  private record Fingerprint(Class<?> type, byte[] digest) {
    boolean matches(Fingerprint other) {
      return type == other.type && MessageDigest.isEqual(digest, other.digest);
    }
  }

  /** Immutable backend baseline from this client's normal negotiation, pending live-state qualification. */
  static final class Baseline {
    private final List<Fingerprint> packets;
    private final byte[] knownPacksReply;

    private Baseline(List<Fingerprint> packets, byte[] reply) {
      this.packets = List.copyOf(packets);
      this.knownPacksReply = reply.clone();
    }
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

    Capture(ProtocolVersion version) {
      invalid = version != ProtocolVersion.MINECRAFT_26_2;
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
        byte[] payload = encode(packet, ProtocolUtils.Direction.CLIENTBOUND);
        bytes = Math.addExact(bytes, payload.length);
        if (bytes > MAX_BYTES || packets.size() >= MAX_PACKETS) {
          throw new IllegalStateException("Configuration capture limit exceeded");
        }
        packets.add(fingerprint(packet, payload));
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
        knownPacksReply = encode(reply, ProtocolUtils.Direction.SERVERBOUND);
      } catch (RuntimeException failure) {
        invalidate();
      }
    }

    void invalidate() {
      invalid = true;
      packets.clear();
      knownPacksReply = null;
    }

    Optional<Baseline> baseline() {
      return !invalid && finished && features && tags && registries > 0 && knownPacksReply != null
          ? Optional.of(new Baseline(packets, knownPacksReply)) : Optional.empty();
    }
  }

  private final Baseline baseline;
  private int cursor;
  private int bytes;
  private boolean finished;
  private boolean failed;

  SeamlessConfiguration(Baseline baseline, ProtocolVersion version) {
    if (version != ProtocolVersion.MINECRAFT_26_2) {
      throw new IllegalArgumentException("Detached configuration requires native 26.2");
    }
    this.baseline = baseline;
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
      byte[] payload = encode(packet, ProtocolUtils.Direction.CLIENTBOUND);
      bytes = Math.addExact(bytes, payload.length);
      if (bytes > MAX_BYTES || !baseline.packets.get(cursor).matches(fingerprint(packet, payload))) {
        throw new IllegalStateException("Destination configuration differs from the client baseline");
      }
      cursor++;
      if (packet instanceof KnownPacksPacket) {
        KnownPacksPacket reply = new KnownPacksPacket();
        ByteBuf buffer = Unpooled.wrappedBuffer(baseline.knownPacksReply);
        try {
          reply.decode(buffer, ProtocolUtils.Direction.SERVERBOUND, ProtocolVersion.MINECRAFT_26_2);
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
      return new Fingerprint(packet.getClass(), MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static byte[] encode(MinecraftPacket packet, ProtocolUtils.Direction direction) {
    // Deferred packets' encoders consume their content. Copy by index to preserve the live packet.
    if (packet instanceof ByteBufHolder holder) {
      if (holder.content().readableBytes() > MAX_BYTES) {
        throw new IllegalArgumentException("Oversized configuration packet");
      }
      return ByteBufUtil.getBytes(holder.content());
    }
    ByteBuf buffer = Unpooled.buffer(256, MAX_BYTES);
    try {
      packet.encode(buffer, direction, ProtocolVersion.MINECRAFT_26_2);
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }
}
