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
import com.velocitypowered.proxy.VelocityServer;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The one place that decides whether a protocol may take the client-invisible path.
 *
 * <p>Eligibility used to be re-derived at each site that needed it - configuration capture, the
 * JoinGame comparison, the detached login probe - which is how a client could be refused for
 * lacking a baseline it was never allowed to capture. Everything now asks here.
 *
 * <p>Three tiers. {@link #QUALIFIED} is what a live two-hub soak has taken through a switch in
 * both directions. Anything else at or above {@link #FLOOR} is a canary: the transfer machinery is
 * version-neutral, so a family works or not on its own evidence, and stays off until an operator
 * has produced that evidence themselves. Below the floor nothing is eligible however it is
 * configured, because nothing there can work.
 */
public final class SeamlessProtocols {

  /**
   * Soaked live, in both directions, on a two-hub network; never gated behind the canary switch.
   *
   * <p>A family is added here by its own commit after its soak, never by the change that first
   * made it expressible - otherwise "qualified" would only mean "someone thought it would work".
   */
  private static final Set<ProtocolVersion> QUALIFIED = Set.of(ProtocolVersion.MINECRAFT_26_2);

  /**
   * The oldest protocol that can work at all. Below it, configuration cannot enable anything.
   *
   * <p>A capture only becomes a baseline once it holds the client's known-packs reply, and
   * {@code KnownPacksPacket} exists only from 1.20.5 - so an older connection negotiates to
   * completion and still produces nothing, silently. 1.20.5 is also where the per-registry sync
   * shape that the comparison parses was introduced, and below 1.20.2 there is no configuration
   * phase on the wire for the proxy to absorb in the first place. An entry under this floor would
   * therefore not name an unqualified protocol but a broken one, so it is refused here rather than
   * left to fail further in.
   */
  static final ProtocolVersion FLOOR = ProtocolVersion.MINECRAFT_1_20_5;

  /**
   * The clientbound PLAY {@code update_tags} packet id, per protocol.
   *
   * <p>Velocity registers this packet in CONFIG but not in PLAY, so its PLAY form arrives opaquely
   * and can only be recognised by id - and the id moves with almost every release. The values are
   * taken from the vanilla clientbound ladder and cross-checked against the two neighbours this
   * proxy does register: {@code custom_report_details} sits exactly two ids above
   * {@code update_tags} at every version here, and its own registrations in
   * {@code StateRegistry} (0x7A at 1.21, 0x81 at 1.21.2, 0x86 at 1.21.9, 0x88 at 26.1) agree with
   * this table on all four. 1.20.5 has no {@code custom_report_details} to check against; there the
   * ladder simply ends one packet later, at {@code projectile_power}.
   *
   * <p>A protocol absent from here cannot be made eligible, because the invalidation below would
   * silently never fire and a stale baseline is the one failure this code exists to prevent.
   */
  private static final Map<ProtocolVersion, Integer> PLAY_UPDATE_TAGS = Map.ofEntries(
      Map.entry(ProtocolVersion.MINECRAFT_1_20_5, 0x78),
      Map.entry(ProtocolVersion.MINECRAFT_1_21, 0x78),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_2, 0x7F),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_4, 0x7F),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_5, 0x7F),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_6, 0x7F),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_7, 0x7F),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_9, 0x84),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_11, 0x84),
      Map.entry(ProtocolVersion.MINECRAFT_26_1, 0x86),
      Map.entry(ProtocolVersion.MINECRAFT_26_2, 0x86));

  private SeamlessProtocols() {
  }

  /**
   * Whether a connection negotiated at {@code protocol} may be captured and compared for a
   * seamless switch.
   */
  public static boolean eligible(@Nullable VelocityServer proxy,
      @Nullable ProtocolVersion protocol) {
    if (!canaryable(protocol)) {
      return false;
    }
    if (QUALIFIED.contains(protocol)) {
      return true;
    }
    return canary(proxy).contains(protocol);
  }

  /** Protocols an operator has opted into beyond the qualified ones. Empty unless configured. */
  public static Set<ProtocolVersion> canary(@Nullable VelocityServer proxy) {
    if (proxy == null || proxy.getDiscovery() == null) {
      return Set.of();
    }
    return proxy.getDiscovery().seamlessCanaryProtocols();
  }

  /**
   * Clientbound PLAY {@code add_entity}. Constant across the whole band, unlike most ids; asserted
   * in {@code SeamlessProtocolsTest} so a future protocol that moves it cannot pass unnoticed.
   */
  private static final int PLAY_ADD_ENTITY = 0x01;

  /**
   * Clientbound PLAY {@code add_experience_orb}, which exists only up to 1.21.4 - 1.21.5 folded it
   * into {@code add_entity}. Absent is not an error here, just one fewer packet to watch.
   */
  private static final Map<ProtocolVersion, Integer> PLAY_ADD_EXPERIENCE_ORB = Map.of(
      ProtocolVersion.MINECRAFT_1_20_5, 0x02,
      ProtocolVersion.MINECRAFT_1_21, 0x02,
      ProtocolVersion.MINECRAFT_1_21_2, 0x02,
      ProtocolVersion.MINECRAFT_1_21_4, 0x02);

  /** Clientbound PLAY {@code remove_entities}, mirroring the encode-only registration. */
  private static final Map<ProtocolVersion, Integer> PLAY_REMOVE_ENTITIES = Map.ofEntries(
      Map.entry(ProtocolVersion.MINECRAFT_1_20_5, 0x42),
      Map.entry(ProtocolVersion.MINECRAFT_1_21, 0x42),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_2, 0x47),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_4, 0x47),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_5, 0x46),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_6, 0x46),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_7, 0x46),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_9, 0x4B),
      Map.entry(ProtocolVersion.MINECRAFT_1_21_11, 0x4B),
      Map.entry(ProtocolVersion.MINECRAFT_26_1, 0x4D),
      Map.entry(ProtocolVersion.MINECRAFT_26_2, 0x4D));

  /** The clientbound PLAY {@code add_entity} id, or {@code -1} outside the band. */
  static int playAddEntityId(@Nullable ProtocolVersion protocol) {
    return canaryable(protocol) ? PLAY_ADD_ENTITY : -1;
  }

  /** The clientbound PLAY {@code add_experience_orb} id, or {@code -1} where it does not exist. */
  static int playAddExperienceOrbId(@Nullable ProtocolVersion protocol) {
    return protocol == null ? -1 : PLAY_ADD_EXPERIENCE_ORB.getOrDefault(protocol, -1);
  }

  /** The clientbound PLAY {@code remove_entities} id, or {@code -1} outside the band. */
  static int playRemoveEntitiesId(@Nullable ProtocolVersion protocol) {
    return protocol == null ? -1 : PLAY_REMOVE_ENTITIES.getOrDefault(protocol, -1);
  }

  /**
   * Whether {@code protocol} is one an operator could qualify for themselves.
   *
   * <p>This is the structural half of {@link #eligible} without the opt-in half, so configuration
   * can reject a protocol that could never work at the point it is read rather than leaving it to
   * look enabled and quietly do nothing.
   */
  public static boolean canaryable(@Nullable ProtocolVersion protocol) {
    return protocol != null && protocol != ProtocolVersion.UNKNOWN && !protocol.lessThan(FLOOR)
        && PLAY_UPDATE_TAGS.containsKey(protocol);
  }

  /**
   * The clientbound PLAY {@code update_tags} id for {@code protocol}, or {@code -1} when this
   * build does not know it and so cannot watch for the packet.
   */
  static int playUpdateTagsId(@Nullable ProtocolVersion protocol) {
    return protocol == null ? -1 : PLAY_UPDATE_TAGS.getOrDefault(protocol, -1);
  }
}
