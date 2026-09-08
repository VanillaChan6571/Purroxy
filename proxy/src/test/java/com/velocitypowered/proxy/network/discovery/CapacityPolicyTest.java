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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapacityPolicyTest {

  private static CapacityPolicy.Candidate candidate(String id, String region, int players,
                                                    int reserved, int maximum) {
    return new CapacityPolicy.Candidate(new BackendResume(id, UUID.randomUUID(), "localhost",
        25565, "Hub", "none", 50, maximum, region), players, reserved, true);
  }

  @Test
  void wakesStrictlyAboveSeventyPercentAndDeduplicatesRequests() {
    assertFalse(CapacityPolicy.shouldWake(List.of(candidate("a", "us", 35, 0, 100)), false));
    assertTrue(CapacityPolicy.shouldWake(List.of(candidate("a", "us", 35, 1, 100)), false));
    assertFalse(CapacityPolicy.shouldWake(List.of(candidate("a", "us", 36, 0, 100)), true));
  }

  @Test
  void overflowUsesActualBackendMaximumIncludingReservations() {
    for (int maximum : List.of(60, 100, 200)) {
      assertTrue(CapacityPolicy.select(List.of(candidate("a", "us", maximum - 1, 0, maximum)),
          "", 0).isPresent());
      assertTrue(CapacityPolicy.select(List.of(candidate("a", "us", maximum - 1, 1, maximum)),
          "", 0).isEmpty());
    }
  }

  @Test
  void safeCapacityPrecedesRegionButOverflowStillRespectsPreference() {
    var local = candidate("local", "us-west", 50, 0, 100);
    var remote = candidate("remote", "eu", 10, 0, 100);
    assertEquals(remote, CapacityPolicy.select(List.of(local, remote), "US-West", 0).orElseThrow());
    remote = candidate("remote", "eu", 50, 0, 100);
    assertEquals(local, CapacityPolicy.select(List.of(local, remote), "US-West", 0).orElseThrow());
  }

  @Test
  void tiesRotateAndSleepingInstancesCannotReceivePlayers() {
    var first = candidate("a", "us", 0, 0, 100);
    var second = candidate("b", "us", 0, 0, 100);
    assertEquals(first, CapacityPolicy.select(List.of(first, second), "", 0).orElseThrow());
    assertEquals(second, CapacityPolicy.select(List.of(first, second), "", 1).orElseThrow());
    var asleep = new CapacityPolicy.Candidate(first.resume(), 0, 0, false);
    assertTrue(CapacityPolicy.select(List.of(asleep), "", 0).isEmpty());
  }

  @Test
  void resumeNormalizesGroupsAndRejectsUnsafeMapPaths() {
    assertEquals("hub", candidate("a", "US-West", 0, 0, 100).resume().group());
    assertThrows(IllegalArgumentException.class, () -> new BackendResume("a", UUID.randomUUID(),
        "localhost", 25565, "hub", "../Christmas", 50, 100, "us-west"));
  }
}
