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
  record Capture(byte[] payload, String backend, ProtocolVersion protocol, String transfer,
                 int attempt) {
    String describe() {
      return backend + " at protocol " + protocol.getProtocol() + ", transfer " + transfer
          + ", attempt " + attempt + ", " + payload.length + " bytes";
    }
  }

  private final Map<UUID, Capture> baselines = new ConcurrentHashMap<>();
  private volatile @Nullable UUID selected;

  /** Names the single player whose biome registry is retained, or clears the selection. */
  public void select(@Nullable UUID player) {
    UUID previous = this.selected;
    this.selected = player;
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
  void baseline(UUID player, String registry, byte[] payload, String backend,
      ProtocolVersion protocol, String transfer, int attempt) {
    if (!selects(player) || !REGISTRY.equals(registry) || payload.length > MAX_BYTES) {
      return;
    }
    baselines.put(player, new Capture(payload.clone(), backend, protocol, transfer, attempt));
  }

  /**
   * Explains a mismatch against the retained baseline, then drops it. The destination payload is
   * only ever examined here and never stored, so a mismatch costs nothing beyond this call.
   */
  void mismatch(UUID player, String registry, byte[] payload, String backend,
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
    Capture destination = new Capture(payload, backend, protocol, transfer, attempt);
    logger.info("Seamless capture for {} on {}:\n  baseline    {}\n  destination {}\n  {}", player,
        registry, baseline.describe(), destination.describe(),
        RegistryPayloadDiff.describe(baseline.payload(), payload, protocol, 16));
  }

  /** Drops a player's capture. Called when they disconnect, so nothing outlives a session. */
  public void clear(UUID player) {
    if (baselines.remove(player) != null) {
      logger.debug("Dropped seamless capture for {}.", player);
    }
  }
}
