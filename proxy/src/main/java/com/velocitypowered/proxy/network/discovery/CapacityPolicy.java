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

package com.velocitypowered.proxy.network.discovery;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure routing policy shared by discovery admission and spare wake decisions. */
public final class CapacityPolicy {

  private CapacityPolicy() {
  }

  /** A registry snapshot with reservations not yet included in the backend player count. */
  public record Candidate(BackendResume resume, int players, int reservations, boolean ready) {
    /** Validates a snapshot before using it in capacity arithmetic. */
    public Candidate {
      Objects.requireNonNull(resume, "resume");
      if (players < 0 || reservations < 0) {
        throw new IllegalArgumentException("Player and reservation counts cannot be negative");
      }
    }

    public long load() {
      return (long) players + reservations;
    }

    public boolean hasSafeCapacity() {
      return load() < resume.safeLimit();
    }

    public boolean admits() {
      return ready && load() < resume.hardLimit();
    }
  }

  /**
   * Selects safe capacity before overflowing, with regional preference within each tier.
   * The registry must reserve the result atomically and recheck eligibility at commit.
   *
   * @param candidates eligible group members, in stable registry order
   * @param preferredRegion preferred region, or an empty string for automatic selection
   * @param rotation monotonically advancing tie-break cursor
   * @return selected member, or empty when no ready member has hard capacity
   */
  public static Optional<Candidate> select(List<Candidate> candidates, String preferredRegion,
                                           long rotation) {
    String region = Objects.requireNonNull(preferredRegion, "preferredRegion")
        .toLowerCase(Locale.ROOT);
    Comparator<Candidate> order = Comparator
        .comparing((Candidate c) -> !c.hasSafeCapacity())
        .thenComparing(c -> !region.isEmpty() && !c.resume.region().equals(region))
        .thenComparing((left, right) -> Long.compare(
            left.load() * right.resume.safeLimit(), right.load() * left.resume.safeLimit()));
    List<Candidate> available = candidates.stream().filter(Candidate::admits).toList();
    Optional<Candidate> best = available.stream().min(order);
    if (best.isEmpty()) {
      return Optional.empty();
    }
    List<Candidate> ties = available.stream().filter(c -> order.compare(c, best.get()) == 0)
        .toList();
    return Optional.of(ties.get((int) Math.floorMod(rotation, (long) ties.size())));
  }

  /** Returns whether READY capacity exceeds the strict 70 percent early-wake threshold. */
  public static boolean shouldWake(List<Candidate> candidates, boolean wakeInProgress) {
    if (wakeInProgress) {
      return false;
    }
    double load = 0;
    double safe = 0;
    for (Candidate candidate : candidates) {
      if (candidate.ready()) {
        load += candidate.load();
        safe += Math.min(candidate.resume.safeLimit(), candidate.resume.hardLimit());
      }
    }
    return safe > 0 && load * 10 > safe * 7;
  }
}
