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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.backend.SourceEntityLedger.Discrepancy;
import com.velocitypowered.proxy.connection.backend.SourceEntityLedger.Phase;
import com.velocitypowered.proxy.connection.backend.SourceEntityLedger.Report;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The ledger's whole job is to tell two faults apart, so these cases are the ones that would be
 * confused with each other on a live network rather than a sweep of its methods.
 */
class SourceEntityLedgerTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final int RETAINED = 1_900_000_001;

  private SourceEntityLedger armed() {
    SourceEntityLedger ledger = new SourceEntityLedger();
    ledger.start(PLAYER, "hub-1", "8e814467", ProtocolVersion.MINECRAFT_1_20_5);
    return ledger;
  }

  private Report inspect(SourceEntityLedger ledger, int... snapshot) {
    return ledger.inspect(PLAYER, snapshot, RETAINED).orElseThrow();
  }

  @Test
  void entitySpawnedWhileTheFenceWasInFlightIsNamedAsRace() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 10);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.spawned(PLAYER, 11);
    ledger.fenceReplied(PLAYER);

    // The backend read its tracker during the window, so its snapshot has 10 but cannot have 11.
    Report report = inspect(ledger, 10);
    assertEquals(1, report.unaccounted().size());
    assertEquals(11, report.unaccounted().get(0).id());
    assertEquals(Phase.DURING_FENCE, report.unaccounted().get(0).phase());
  }

  @Test
  void entitySpawnedAfterTheSnapshotIsNamedAsRace() {
    SourceEntityLedger ledger = armed();
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);
    ledger.spawned(PLAYER, 12); // immediately before the source is cut off

    Report report = inspect(ledger);
    assertEquals(1, report.unaccounted().size());
    assertEquals(Phase.AFTER_FENCE, report.unaccounted().get(0).phase());
  }

  @Test
  void entityTheTrackerNeverHeldIsNamedAsPacketOnly() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 20); // a plugin wrote this straight to the connection, long before
    ledger.spawned(PLAYER, 21);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);

    // The snapshot has 21 but never had 20: no re-read of the tracker would ever have found it,
    // which is what separates this from a race and puts the fix somewhere else entirely.
    Report report = inspect(ledger, 21);
    assertEquals(1, report.unaccounted().size());
    assertEquals(20, report.unaccounted().get(0).id());
    assertEquals(Phase.BEFORE_FENCE, report.unaccounted().get(0).phase());
  }

  @Test
  void retriedHandoffClassifiesAgainstItsOwnFence() {
    SourceEntityLedger ledger = armed();
    ledger.fenceRequested(PLAYER, "aborted");
    ledger.fenceReplied(PLAYER);
    // The transfer aborts here. The player stays put and the world keeps being tracked to them.
    ledger.spawned(PLAYER, 30);
    ledger.fenceRequested(PLAYER, "retry");
    ledger.spawned(PLAYER, 31);

    Report report = inspect(ledger);
    assertEquals("retry", report.transfer());
    assertEquals(Phase.AFTER_FENCE, byId(report, 30).phase());
    // 31 belongs to the retry's window, not to the abandoned snapshot that preceded it.
    assertEquals(Phase.DURING_FENCE, byId(report, 31).phase());
  }

  @Test
  void idTheSnapshotAccountsForIsNeverReportedOnDestinationReuse() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 40);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);

    // The destination will mint its own entity 40 from an identical world; that is exactly the
    // collision this exists to find, and it is harmless precisely because 40 will be removed first.
    Report report = inspect(ledger, 40);
    assertTrue(report.unaccounted().isEmpty());
    assertEquals(1, report.live());
  }

  @Test
  void theRetainedPlayerEntityIsNotReported() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, RETAINED);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);

    // Both sides leave the client's own entity alone on purpose, so its absence is the design.
    assertTrue(inspect(ledger).unaccounted().isEmpty());
  }

  @Test
  void entityTheSourceTookBackIsNotStillHeldByTheClient() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 50);
    ledger.spawned(PLAYER, 51);
    ledger.removed(PLAYER, 50);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);

    Report report = inspect(ledger);
    assertEquals(1, report.unaccounted().size());
    assertEquals(51, report.unaccounted().get(0).id());
  }

  @Test
  void discrepanciesReadInTheOrderTheClientReceivedThem() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 60);
    ledger.spawned(PLAYER, 61);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);
    ledger.spawned(PLAYER, 62);

    List<Discrepancy> unaccounted = inspect(ledger).unaccounted();
    assertEquals(List.of(60, 61, 62), unaccounted.stream().map(Discrepancy::id).toList());
    assertTrue(unaccounted.get(0).sequence() < unaccounted.get(2).sequence());
  }

  @Test
  void overflowingSessionSaysSoRatherThanReportingPartialSet() {
    SourceEntityLedger ledger = armed();
    for (int id = 0; id <= SourceEntityLedger.MAX_IDS; id++) {
      ledger.spawned(PLAYER, id);
    }
    Report report = inspect(ledger);
    assertTrue(report.overflowed());
    // A truncated list would read as a small, tidy set of discrepancies and be believed.
    assertTrue(report.unaccounted().isEmpty());
  }

  @Test
  void newBackendConnectionStartsItsOwnLedger() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 70);
    ledger.start(PLAYER, "hub-2", "7ef1ab3b", ProtocolVersion.MINECRAFT_1_20_5);

    Report report = inspect(ledger);
    assertEquals("hub-2", report.backend());
    assertEquals(0, report.live());
    assertTrue(report.unaccounted().isEmpty());
  }

  @Test
  void nothingIsRecordedForPlayerThatWasNeverArmed() {
    SourceEntityLedger ledger = new SourceEntityLedger();
    assertFalse(ledger.records(PLAYER));
    ledger.spawned(PLAYER, 80);
    assertTrue(ledger.inspect(PLAYER, new int[0], RETAINED).isEmpty());
  }

  @Test
  void clearingDropsTheSession() {
    SourceEntityLedger ledger = armed();
    assertTrue(ledger.records(PLAYER));
    ledger.clear(PLAYER);
    assertFalse(ledger.records(PLAYER));
  }

  @Test
  void theRemovalSetIsTheUnionOfWhatWasTrackedAndWhatWasForwarded() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 1); // packet-only: the backend tracker never held this
    ledger.spawned(PLAYER, 2);
    ledger.fenceRequested(PLAYER, "t1");
    ledger.fenceReplied(PLAYER);

    // 90 is tracked but was forwarded before recording could have seen it; both must be removed.
    int[] removal = ledger.removalSet(PLAYER, new int[] {90}, RETAINED);
    assertEquals(List.of(90, 1, 2), java.util.Arrays.stream(removal).boxed().toList());
  }

  @Test
  void theRemovalSetNeverContainsTheRetainedPlayerEntity() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, RETAINED);
    ledger.spawned(PLAYER, 3);

    int[] removal = ledger.removalSet(PLAYER, new int[] {RETAINED, 4}, RETAINED);
    assertEquals(List.of(4, 3), java.util.Arrays.stream(removal).boxed().toList());
  }

  @Test
  void overflowedLedgerFallsBackToTheBackendSnapshot() {
    SourceEntityLedger ledger = armed();
    for (int id = 0; id <= SourceEntityLedger.MAX_IDS; id++) {
      ledger.spawned(PLAYER, id);
    }
    // Removing a truncated set would clear less than the snapshot alone and read as an improvement.
    assertEquals(List.of(7, 8),
        java.util.Arrays.stream(ledger.removalSet(PLAYER, new int[] {7, 8}, RETAINED)).boxed()
            .toList());
  }

  @Test
  void entityTheSourceRemovedIsNotInTheRemovalSet() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 5);
    ledger.spawned(PLAYER, 6);
    ledger.removed(PLAYER, 5);

    assertEquals(List.of(6),
        java.util.Arrays.stream(ledger.removalSet(PLAYER, new int[0], RETAINED)).boxed().toList());
  }

  @Test
  void withoutLedgerSessionTheSnapshotIsUsedUnchanged() {
    SourceEntityLedger ledger = new SourceEntityLedger();
    assertEquals(List.of(11, 12),
        java.util.Arrays.stream(ledger.removalSet(PLAYER, new int[] {11, 12}, RETAINED)).boxed()
            .toList());
  }

  @Test
  void completeTrackingIsWhatSeparatesCleanableTableFromReset() {
    SourceEntityLedger ledger = armed();
    ledger.spawned(PLAYER, 1);
    assertTrue(ledger.tracksCompletely(PLAYER));

    for (int id = 0; id <= SourceEntityLedger.MAX_IDS; id++) {
      ledger.spawned(PLAYER, id);
    }
    // Overflowed: the ids held are a fragment, so the switch must reset rather than half-clean.
    assertFalse(ledger.tracksCompletely(PLAYER));
  }

  @Test
  void sourceThatWasNeverRecordedIsNotTreatedAsHavingShownNothing() {
    SourceEntityLedger ledger = new SourceEntityLedger();
    // An empty removal set and an unknown one are indistinguishable from the removal side, so the
    // difference has to be carried here instead.
    assertFalse(ledger.tracksCompletely(PLAYER));
  }

  private static Discrepancy byId(Report report, int id) {
    return report.unaccounted().stream().filter(entry -> entry.id() == id).findFirst()
        .orElseThrow(() -> new AssertionError("id " + id + " was not reported"));
  }
}
