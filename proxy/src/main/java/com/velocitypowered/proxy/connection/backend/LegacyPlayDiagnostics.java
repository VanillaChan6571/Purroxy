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
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Bounded, selected-player observations only. Never authorizes or changes a transfer. */
final class LegacyPlayDiagnostics {
  private static final Logger logger = LogManager.getLogger(LegacyPlayDiagnostics.class);
  private static final int MAX_BYTES = 512 * 1024;
  private UUID player;
  private String backend;
  private SeamlessConfiguration.JoinBaseline join;
  private CompoundBinaryTag registry;
  private int entityId;
  private Map<Integer, Observation> previous = Map.of();
  private Map<Integer, Observation> current = new HashMap<>();
  private long began;
  private int logged;
  private int playerChat;
  private int playerChatSigned;
  private int playerChatUnreadable;

  /**
   * One packet's digests. {@code canonical} is null unless the payload could be normalised, and a
   * null never compares equal to anything - an unreadable payload must not read as a match.
   */
  private record Observation(byte[] wire, byte @Nullable [] canonical) {

    boolean sameWire(Observation other) {
      return MessageDigest.isEqual(wire, other.wire);
    }

    String sameCanonical(Observation other) {
      return canonical == null || other.canonical == null
          ? "unavailable" : String.valueOf(MessageDigest.isEqual(canonical, other.canonical));
    }
  }

  synchronized void clear() {
    player = null;
    backend = null;
    join = null;
    registry = null;
    previous = Map.of();
    current = new HashMap<>();
    playerChat = 0;
    playerChatSigned = 0;
    playerChatUnreadable = 0;
  }

  synchronized void join(UUID selected, String destination, JoinGamePacket packet) {
    if (!selected.equals(player)) {
      clear();
    }
    // Compare before the normal transfer mutates or forwards JoinGame.
    byte[] encoded = SeamlessConfiguration.encode(packet, ProtocolUtils.Direction.CLIENTBOUND,
        ProtocolVersion.MINECRAFT_1_20);
    final boolean bounded = encoded.length <= MAX_BYTES;
    logger.info("Legacy PLAY debug 763 for {}: {} -> {}; JoinGame bytes={}, entityId={},"
        + " idKept={}, registrySemanticMatch={}, fullJoinExceptIdMatch={},"
        + " playerChatOnPreviousVisit={}, ofThoseSigned={}, unreadable={}."
        + " Diagnostic only: normal JoinGame/Respawn reset remains enabled;"
        + " chat session and live state safety are NOT verified.", selected,
        backend == null ? "initial login" : backend, destination, encoded.length, packet.getEntityId(),
        join == null ? "no baseline" : entityId == packet.getEntityId(),
        join == null ? "no baseline" : java.util.Objects.equals(registry, packet.getRegistry()),
        join == null ? "no baseline" : join.matches(packet, ProtocolVersion.MINECRAFT_1_20),
        backend == null ? "no previous visit" : playerChat, playerChatSigned, playerChatUnreadable);
    playerChat = 0;
    playerChatSigned = 0;
    playerChatUnreadable = 0;
    player = selected;
    backend = destination;
    entityId = packet.getEntityId();
    join = bounded ? SeamlessConfiguration.captureJoin(packet, ProtocolVersion.MINECRAFT_1_20) : null;
    registry = bounded ? packet.getRegistry() : null;
    previous = current;
    current = new HashMap<>();
    began = System.nanoTime();
    logged = 0;
    if (!bounded) {
      logger.info("Legacy PLAY debug: JoinGame exceeds capture limit; comparison baseline omitted.");
    }
  }

  synchronized void packet(UUID selected, String source, ByteBuf packet) {
    if (!selected.equals(player) || !source.equals(backend)) {
      return;
    }
    ByteBuf read = packet.duplicate();
    int id = ProtocolUtils.readVarInt(read);
    if (id == 0x35) {
      // player_chat. The client mutates its chat trackers from exactly one path:
      // markMessageAsProcessed feeds lastSeenMessages only for a non-null signature, and
      // messageSignatureCache.push happens only in handlePlayerChat. system_chat (0x64) and
      // disguised_chat (0x1B) touch neither. So if this packet never arrives, both trackers stay
      // at their initial state and a suppressed JoinGame leaves nothing stale to desync - which
      // is what makes a legacy switch survivable without resetting client chat state.
      //
      // Absence is the load-bearing direction. Presence here does not prove a signature was
      // attached, only that the signed-chat path is in use and the invariant needs checking.
      //
      // Whether a signature is attached is the question that actually matters, and the 1.20.1
      // record reads sender UUID, then a VarInt index, then readNullable for the signature - so
      // the presence flag sits at a bounded offset and needs no full decode.
      playerChat++;
      Boolean signed = playerChatSignature(read);
      if (signed == null) {
        playerChatUnreadable++;
      } else if (signed) {
        playerChatSigned++;
      }
      if (playerChat == 1) {
        logger.info("Legacy PLAY debug 763 {}: player_chat observed, signaturePresent={}."
            + " A signature is what feeds the client's lastSeenMessages tracker; unsigned"
            + " player_chat leaves it untouched. Counting only; nothing is suppressed.",
            source, signed == null ? "unreadable" : signed);
      }
      return;
    }
    // Enumerated from the decompiled 1.20.1 ConnectionProtocol clientbound PLAY registration
    // order: the bundle packet takes 0x00 and the 110 addPacket calls follow it, which puts
    // update_enabled_features at 0x6B and update_tags at 0x6E. An earlier pass had these as 0x68
    // and 0x6B; 0x68 is teleport_entity, so that run compared per-entity movement and reported a
    // difference that meant nothing, while tags were never observed at all.
    if (id != 0x6B && id != 0x6E) {
      return;
    }
    String name = id == 0x6B ? "enabled-features" : "update-tags";
    if (read.readableBytes() > MAX_BYTES) {
      current.remove(id);
      if (logged++ < 32) {
        logger.info("Legacy PLAY debug 763 {}: {} exceeds capture limit; comparison unavailable.", source, name);
      }
      return;
    }
    byte[] payload = ByteBufUtil.getBytes(read);
    // Tags are a map on both sides, so two backends holding the same tags can still serialise them
    // in a different order. The configuration path already treats that as a match once normalised
    // (SeamlessConfiguration.fingerprint, ObservedConfigSessionHandler.report), and the PLAY form
    // is the same wire shape, so normalise it the same way rather than calling a reorder a
    // divergence. The raw digest is still reported: canonical equality is the weaker claim.
    Observation observed = new Observation(digest(payload),
        id == 0x6E ? canonicalTagDigest(payload) : null);
    Observation prior = previous.get(id);
    Observation sameSession = current.put(id, observed);
    if (logged++ < 32) {
      logger.info("Legacy PLAY debug 763 {}: {} +{}ms, bytes={}, previousBackendMatch={},"
          + " orderNormalisedMatch={}, changedSinceLastUpdate={}."
          + " Observed during PLAY; no packets suppressed.", source,
          name, (System.nanoTime() - began) / 1_000_000, read.readableBytes(),
          prior == null ? "no baseline" : observed.sameWire(prior),
          id != 0x6E ? "n/a" : prior == null ? "no baseline" : observed.sameCanonical(prior),
          sameSession == null ? "first update" : !observed.sameWire(sameSession));
    }
    if (logged == 32) {
      logger.info("Legacy PLAY debug {}: packet log limit reached for this backend visit.", source);
    }
  }

  /**
   * Normalises a PLAY tags payload through the same encoder the configuration path compares with.
   * Returns null when the payload will not decode or will not fit, so it is reported as
   * unavailable rather than silently as a mismatch.
   */
  private static byte @Nullable [] canonicalTagDigest(byte[] payload) {
    TagsUpdatePacket tags = new TagsUpdatePacket();
    ByteBuf source = Unpooled.wrappedBuffer(payload);
    ByteBuf canonical = Unpooled.buffer(256, MAX_BYTES);
    try {
      tags.decode(source, ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_20);
      if (source.isReadable()) {
        // Trailing bytes mean this was decoded as something it is not; do not claim a comparison.
        return null;
      }
      tags.encodeCanonical(canonical);
      return digest(ByteBufUtil.getBytes(canonical));
    } catch (RuntimeException undecodable) {
      return null;
    } finally {
      source.release();
      canonical.release();
    }
  }

  /**
   * Reads only {@code player_chat}'s signature presence flag: a 16-byte sender UUID, a VarInt
   * index, then the nullable-signature boolean. Returns null when the prefix will not read, so an
   * unreadable packet is never counted as unsigned.
   *
   * @param read the payload positioned after the packet id
   */
  private static @Nullable Boolean playerChatSignature(ByteBuf read) {
    try {
      ByteBuf prefix = read.duplicate();
      prefix.skipBytes(Long.BYTES * 2);
      ProtocolUtils.readVarInt(prefix);
      return prefix.readBoolean();
    } catch (RuntimeException truncated) {
      return null;
    }
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
