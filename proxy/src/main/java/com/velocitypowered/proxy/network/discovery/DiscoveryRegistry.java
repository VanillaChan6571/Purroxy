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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Serializes authenticated backend sessions and capacity reservations.
 * Transport code must authenticate and authorize resumes before calling register.
 */
public final class DiscoveryRegistry {
  private final Set<String> groups;
  private final long leaseNanos;
  private final LongSupplier clock;
  private final Map<String, Entry> entries = new HashMap<>();
  private long rotation;

  /** Creates a registry using a monotonic clock, typically {@link System#nanoTime()}. */
  public DiscoveryRegistry(Set<String> groups, Duration lease, LongSupplier clock) {
    this.groups = groups.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    this.leaseNanos = lease.toNanos();
    if (leaseNanos <= 0) {
      throw new IllegalArgumentException("Lease must be positive");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** State reported by the backend or enforced by the control connection. */
  public enum State {
    REGISTERING, READY, SLEEPING, WAKING, DRAINING, SUSPECT
  }

  /** Identity of a particular authenticated connection, independent of process incarnation. */
  public record Session(String serverId, UUID token) {
  }

  /** Slot held until admitted, cancelled, or timed out by the connection coordinator. */
  public record Reservation(Session session, UUID token) {
  }

  /** Read-only control-plane view, including expired records retained for retirement. */
  public record Snapshot(BackendResume resume, Session session, State state, int players,
                         int reservations, boolean leaseValid) {
  }

  private static final class Entry {
    private final BackendResume resume;
    private final Session session;
    private final Set<UUID> reservations = new java.util.HashSet<>();
    private long renewed;
    private long sequence = -1;
    private State state = State.REGISTERING;
    private int players;
    private int connectedPlayers;

    private Entry(BackendResume resume, long now) {
      this.resume = resume;
      this.session = new Session(resume.serverId(), UUID.randomUUID());
      this.renewed = now;
    }
  }

  /** Registers an authorized resume, rejecting unknown groups and unresolved older sessions. */
  public synchronized Session register(BackendResume resume) {
    if (!groups.contains(resume.group())) {
      throw new IllegalArgumentException("UNKNOWN_GROUP: " + resume.group());
    }
    Entry previous = entries.get(resume.serverId());
    if (previous != null && ((live(previous) && previous.state != State.SUSPECT)
        || !previous.resume.equals(resume) || !previous.reservations.isEmpty())) {
      throw new IllegalStateException("Identity still registered; retire its previous session first");
    }
    Entry entry = new Entry(resume, clock.getAsLong());
    if (previous != null) {
      entry.players = previous.players;
    }
    entries.put(resume.serverId(), entry);
    return entry.session;
  }

  /** Applies a strictly sequenced heartbeat; admitted tokens reconcile pending reservations. */
  public synchronized boolean heartbeat(Session session, long sequence, State state,
                                        int players, Set<UUID> admitted) {
    Entry entry = entry(session);
    if (entry == null || !live(entry) || sequence <= entry.sequence) {
      return false;
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(admitted, "admitted");
    if (players < 0 || (state == State.SLEEPING
        && (players != 0 || !entry.reservations.isEmpty()))) {
      throw new IllegalArgumentException("Invalid player count or occupied sleep request");
    }
    if (entry.state == State.SUSPECT) {
      return false; // A lost control connection must reauthenticate, not revive via queued data.
    }
    entry.players = players;
    entry.reservations.removeAll(admitted);
    entry.sequence = sequence;
    entry.state = state;
    entry.renewed = clock.getAsLong();
    return true;
  }

  /** Immediately removes a disconnected session from admission without losing its player record. */
  public synchronized void disconnected(Session session) {
    Entry entry = entry(session);
    if (entry != null) {
      entry.state = State.SUSPECT;
    }
  }

  /** Atomically chooses and reserves one slot in a group, including overflow fallback. */
  public synchronized Optional<Reservation> reserve(String group, String preferredRegion) {
    return reserve(group, preferredRegion, Set.of());
  }

  /** Reserves a group member while excluding destinations already attempted by this operation. */
  public synchronized Optional<Reservation> reserve(String group, String preferredRegion,
                                                    Set<String> excluded) {
    List<CapacityPolicy.Candidate> candidates = entries.values().stream()
        .filter(e -> e.resume.group().equalsIgnoreCase(group))
        .filter(e -> !excluded.contains(e.resume.serverId()))
        .sorted(java.util.Comparator.comparing(e -> e.resume.serverId()))
        .map(e -> new CapacityPolicy.Candidate(e.resume, Math.max(e.players, e.connectedPlayers), e.reservations.size(),
            live(e) && e.state == State.READY)).toList();
    return CapacityPolicy.select(candidates, preferredRegion, rotation++).map(selected -> {
      Entry entry = entries.get(selected.resume().serverId());
      UUID token = UUID.randomUUID();
      entry.reservations.add(token);
      return new Reservation(entry.session, token);
    });
  }

  /** Reserves an explicit physical target, bypassing only the soft balancing target. */
  public synchronized Optional<Reservation> reservePhysical(String name) {
    Entry entry = entries.get(name.toLowerCase(Locale.ROOT));
    if (entry == null || !live(entry) || entry.state != State.READY
        || (long) Math.max(entry.players, entry.connectedPlayers) + entry.reservations.size()
            >= entry.resume.hardLimit()) {
      return Optional.empty();
    }
    UUID token = UUID.randomUUID();
    entry.reservations.add(token);
    return Optional.of(new Reservation(entry.session, token));
  }

  /** Supplies the proxy's live count so a delayed heartbeat cannot hide completed joins. */
  public synchronized void observeConnections(Session session, int players) {
    if (players < 0) {
      throw new IllegalArgumentException("Negative connection count");
    }
    Entry entry = entry(session);
    if (entry != null) {
      entry.connectedPlayers = players;
    }
  }

  /** Rechecks a held slot before starting the final admission. */
  public synchronized boolean canCommit(Reservation reservation) {
    Entry entry = entry(reservation.session());
    return entry != null && live(entry) && entry.state == State.READY
        && entry.reservations.contains(reservation.token())
        // The backend may already count this joining player. Do not count its held slot twice.
        // Reservations enforce capacity before connect; backend admission remains authoritative.
        && Math.max(entry.players, entry.connectedPlayers) <= entry.resume.hardLimit();
  }

  /** Releases a cancelled/failed slot; successful slots are reconciled in backend heartbeats. */
  public synchronized void release(Reservation reservation) {
    Entry entry = entry(reservation.session());
    if (entry != null) {
      entry.reservations.remove(reservation.token());
    }
  }

  /**
   * Retires a session once the coordinator has verified zero physical connections and requests.
   * Expired backend player counts may be stale, so the coordinator owns this verification.
   */
  public synchronized boolean retire(Session session, boolean connectionsEmpty) {
    Entry entry = entry(session);
    if (entry == null || !connectionsEmpty || !entry.reservations.isEmpty()
        || (live(entry) && entry.state != State.DRAINING && entry.state != State.SUSPECT)) {
      return false;
    }
    entries.remove(session.serverId());
    return true;
  }

  public synchronized List<Snapshot> snapshots() {
    return entries.values().stream().map(e -> new Snapshot(e.resume, e.session, e.state,
        e.players, e.reservations.size(), live(e))).toList();
  }

  private boolean live(Entry entry) {
    return clock.getAsLong() - entry.renewed < leaseNanos;
  }

  private Entry entry(Session session) {
    Entry entry = entries.get(session.serverId());
    return entry != null && entry.session.equals(session) ? entry : null;
  }
}
