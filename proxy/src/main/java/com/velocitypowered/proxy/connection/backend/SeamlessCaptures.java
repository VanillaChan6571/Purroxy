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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Keeps one registry payload for one named player, so a configuration mismatch can be explained
 * instead of only reported.
 *
 * <p>Deliberately narrow. A biome registry runs to roughly twenty kilobytes, and retaining every
 * configuration payload for every player would cost far more than that figure suggests, so this
 * holds a single registry for a single operator-chosen account and nothing else. It is off unless
 * a player is named, and a capture is dropped as soon as it is replaced or its player leaves.
 */
public final class SeamlessCaptures {

  private static final Logger logger = LogManager.getLogger(SeamlessCaptures.class);

  /** Only this registry is retained: it is the one whose ordering assigns chunk-visible ids. */
  public static final String REGISTRY = "minecraft:worldgen/biome";

  /** A payload larger than this is not a registry worth diffing; refuse rather than retain it. */
  static final int MAX_BYTES = 512 * 1024;

  /** A retained payload with everything needed to say which side of which attempt it came from. */
  record Capture(byte[] payload, String backend, String instance, ProtocolVersion protocol,
                 String transfer, int attempt, java.time.Instant at) {
    String describe() {
      // The instance distinguishes one backend incarnation from the next, so a payload that
      // changed after a restart is not mistaken for one that changed between connections.
      return backend + " (instance " + instance + ") at protocol " + protocol.getProtocol()
          + ", transfer " + transfer + ", attempt " + attempt + ", " + payload.length
          + " bytes, captured " + at;
    }
  }

  private final Map<UUID, Capture> baselines = new ConcurrentHashMap<>();
  private volatile @Nullable UUID selected;
  private final LegacyPlayDiagnostics legacy = new LegacyPlayDiagnostics();

  /** Names the single player whose biome registry is retained, or clears the selection. */
  public void select(@Nullable UUID player) {
    UUID previous = this.selected;
    this.selected = player;
    legacy.clear();
    if (previous != null && !previous.equals(player)) {
      baselines.remove(previous);
    }
    logger.info("Seamless capture {}.", player == null ? "disabled" : "armed for " + player);
  }

  public boolean selects(@Nullable UUID player) {
    return player != null && player.equals(selected);
  }

  /**
   * Retains the baseline payload this client was actually configured with. Replaces any previous
   * capture for the player, so only the most recent configuration is ever held.
   */
  void baseline(UUID player, String registry, byte[] payload, String backend, String instance,
      ProtocolVersion protocol, String transfer, int attempt) {
    if (!selects(player) || !REGISTRY.equals(registry) || payload.length > MAX_BYTES) {
      return;
    }
    baselines.put(player, new Capture(payload.clone(), backend, instance, protocol, transfer,
        attempt, java.time.Instant.now()));
  }

  /**
   * Explains a mismatch against the retained baseline, then drops it. The destination payload is
   * only ever examined here and never stored, so a mismatch costs nothing beyond this call.
   */
  void mismatch(UUID player, String registry, byte[] payload, String backend, String instance,
      ProtocolVersion protocol, String transfer, int attempt) {
    if (!selects(player) || !REGISTRY.equals(registry)) {
      return;
    }
    Capture baseline = baselines.remove(player);
    if (baseline == null) {
      logger.info("Seamless capture for {}: {} diverged but no baseline was retained to compare.",
          player, registry);
      return;
    }
    Capture destination = new Capture(payload, backend, instance, protocol, transfer, attempt,
        java.time.Instant.now());
    logger.info("Seamless capture for {} on {}:\n  baseline    {}\n  destination {}\n  {}", player,
        registry, baseline.describe(), destination.describe(),
        RegistryPayloadDiff.describe(baseline.payload(), payload, protocol, 16));
  }

  /**
   * Per-visit histogram of opaque clientbound PLAY ids, for every player rather than the selected
   * one. Identifying a packet by id is what went wrong at 763, so any id a safety gate depends on
   * is confirmed against a live stream before it is trusted. That check earned its place twice: it
   * caught 0x68 being teleport rather than features at 763, and it exposed a mis-parse of the 776
   * packet table that had briefly looked like a defect in {@link SeamlessProtocols}.
   */
  private final Map<UUID, Visit> clientboundIds = new ConcurrentHashMap<>();

  /** One backend visit's counts, named by the backend they were actually observed on. */
  private static final class Visit {
    private final String backend;
    private final Map<Integer, int[]> ids = new ConcurrentHashMap<>();

    private Visit(String backend) {
      this.backend = backend;
    }
  }

  /**
   * Signed {@code player_chat} delivered to each client since its chat frame was last reset. The
   * proxy's own {@code ChatState} cannot answer this: it only moves when the client *sends*, and
   * its acknowledged bitset saturates at {@code LastSeenMessages.WINDOW_SIZE}. A player who has
   * only been reading chat has an empty bitset and a full client-side tracker, which is exactly
   * the case that was kicked on arrival.
   */
  private final Map<UUID, int[]> signedChatSinceReset = new ConcurrentHashMap<>();

  /** Forgets received-chat state, for the JoinGame that also resets the client's tracker. */
  public void resetChatFrame(UUID player) {
    signedChatSinceReset.remove(player);
  }

  /**
   * Whether this client's chat frame is provably still empty, so a destination that starts with an
   * empty validator can accept what the client sends next.
   *
   * <p>Fails closed. An unverified protocol, or any signed message already delivered, means the
   * answer is no - never an assumption that nothing arrived.
   *
   * @param player the player about to be transferred
   * @param protocol their protocol
   * @return true only when continuity is positively established
   */
  public boolean chatFrameProvablyEmpty(UUID player, ProtocolVersion protocol) {
    if (SeamlessProtocols.playerChat(protocol) == null) {
      return false;
    }
    int[] received = signedChatSinceReset.get(player);
    return received == null || received[0] == 0;
  }

  /**
   * Notes a signed {@code player_chat} delivered to this client, if the protocol's layout is
   * verified. An unreadable prefix counts as signed: the safe error is to block a seamless
   * transfer, never to permit one on a message that could not be inspected.
   */
  public void observeDeliveredChat(UUID player, ByteBuf packet, ProtocolVersion protocol) {
    SeamlessProtocols.PlayerChat shape = SeamlessProtocols.playerChat(protocol);
    if (shape == null) {
      return;
    }
    ByteBuf read = packet.duplicate();
    boolean matched = false;
    boolean signed = true;
    try {
      if (ProtocolUtils.readVarInt(read) != shape.id()) {
        return;
      }
      matched = true;
      for (int leading = 0; leading < shape.leadingVarInts(); leading++) {
        ProtocolUtils.readVarInt(read);
      }
      read.skipBytes(Long.BYTES * 2);
      ProtocolUtils.readVarInt(read);
      signed = read.readBoolean();
    } catch (RuntimeException unreadable) {
      // Only a packet already identified as player_chat counts; a prefix that fails before the id
      // is read is some other packet, not an unreadable chat message.
      if (!matched) {
        return;
      }
    }
    if (signed) {
      signedChatSinceReset.computeIfAbsent(player, key -> new int[1])[0]++;
    }
  }

  /** Counts one opaque clientbound packet without consuming or retaining it. */
  public void observeClientbound(UUID player, String backend, ByteBuf packet) {
    // Keyed by the observing backend, not the destination: an earlier build reported the source
    // visit's counts under the destination's name, which reads as the wrong hub's traffic.
    Visit visit = clientboundIds.compute(player, (key, existing) ->
        existing == null || !existing.backend.equals(backend) ? new Visit(backend) : existing);
    Map<Integer, int[]> seen = visit.ids;
    if (seen.size() >= 128) {
      return;
    }
    try {
      ByteBuf read = packet.duplicate();
      int id = ProtocolUtils.readVarInt(read);
      int[] tally = seen.computeIfAbsent(id, key -> new int[2]);
      synchronized (tally) {
        tally[0]++;
        tally[1] = Math.max(tally[1], read.readableBytes());
      }
    } catch (RuntimeException unreadable) {
      // An id that will not read cannot be attributed; drop it rather than mis-attribute a count.
    }
  }

  /** Reports and resets the histogram for one backend visit. */
  public void reportClientbound(UUID player, String destination, ProtocolVersion protocol) {
    Visit visit = clientboundIds.remove(player);
    if (visit == null || visit.ids.isEmpty()) {
      return;
    }
    String backend = visit.backend;
    StringBuilder line = new StringBuilder();
    visit.ids.entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(entry -> line.append(String.format(" 0x%02X x%d(max %dB)", entry.getKey(),
            entry.getValue()[0], entry.getValue()[1])));
    logger.info("Clientbound id histogram for {} observed on {} (now leaving for {}, protocol {}):{}."
        + " Opaque PLAY packets only - anything this proxy decodes is absent. Observation only.",
        player, backend, destination, protocol.getProtocol(), line);
  }

  /**
   * Traces the proxy's mirror of the client's secure-chat state across a handoff. The mirror is
   * discarded on JoinGame precisely because the client resets then, so a suppressed JoinGame would
   * leave the proxy holding the client's frame while the destination starts empty. Whether that
   * frame can be handed over is the open question; this only records it.
   *
   * @param player the player the event belongs to
   * @param event what happened
   * @param detail counts and offsets only, never message content or signatures
   */
  public void chatTrace(UUID player, String event, String detail) {
    // Deliberately not restricted to the selected player: the chat validation kick was suffered by
    // a second account on a different protocol, and tracing only the capture player is what left
    // that transfer undocumented.
    logger.info("Chat trace {}: {} [{}]. Observation only; no chat state is altered.",
        player, event, detail);
  }

  /** Records protocol 763 JoinGame for the selected debugging session. */
  public void legacyJoin(UUID player, String backend,
      com.velocitypowered.proxy.protocol.packet.JoinGamePacket packet, ProtocolVersion protocol) {
    if (selects(player) && protocol == ProtocolVersion.MINECRAFT_1_20) {
      try {
        legacy.join(player, backend, packet);
      } catch (RuntimeException failure) {
        legacy.clear();
        logger.info("Legacy PLAY debug: JoinGame observation failed; baseline discarded.");
      }
    }
  }

  /** Observes legacy PLAY tags/features without consuming or retaining the live packet. */
  public void legacyPacket(UUID player, String backend, io.netty.buffer.ByteBuf packet,
      ProtocolVersion protocol) {
    if (selects(player) && protocol == ProtocolVersion.MINECRAFT_1_20) {
      try {
        legacy.packet(player, backend, packet);
      } catch (RuntimeException failure) {
        legacy.clear();
        logger.info("Legacy PLAY debug: packet observation failed; baseline discarded.");
      }
    }
  }

  /** Drops a player's capture. Called when they disconnect, so nothing outlives a session. */
  public void clear(UUID player) {
    clientboundIds.remove(player);
    signedChatSinceReset.remove(player);
    if (selects(player)) {
      legacy.clear();
    }
    if (baselines.remove(player) != null) {
      logger.debug("Dropped seamless capture for {}.", player);
    }
  }
}
