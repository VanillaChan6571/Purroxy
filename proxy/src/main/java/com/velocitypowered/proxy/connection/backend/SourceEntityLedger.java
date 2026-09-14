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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the proxy actually forwarded to a client about the source server's entities.
 *
 * <p>Diagnostic only: this records and reports, and removes nothing. It exists to answer one
 * question that the backend's own fence-time snapshot cannot, because that snapshot is read from
 * the entity tracker at a single instant and the proxy is the only party that sees what the client
 * was really sent.
 *
 * <p>Two different faults produce the same symptom - an entity the destination cannot replace,
 * because the client already holds that id - and they need different fixes, so they are
 * distinguished here rather than lumped together:
 *
 * <ul>
 *   <li>a spawn forwarded <em>before</em> the fence was even requested, yet absent from the
 *       snapshot, means the entity was never in the tracker at all: a packet-only entity written
 *       straight to the connection by a plugin. No amount of re-reading the tracker would find it.
 *   <li>a spawn forwarded <em>during or after</em> the fence round trip is a race: the tracker was
 *       correct when it was read and kept sending afterwards, because being frozen stops a player
 *       ticking but not the world around them from being tracked to that player.
 * </ul>
 *
 * <p>Recording starts when the backend enters PLAY, not when a handoff begins, because a
 * packet-only entity can have been created at any point in the session. It is bounded: a session
 * holds at most {@link #MAX_IDS} live ids and says so if it overflows rather than reporting a
 * truncated set as if it were complete.
 */
public final class SourceEntityLedger {

  private static final Logger logger = LogManager.getLogger(SourceEntityLedger.class);

  /** Live ids held per session. A hub shows a client far fewer than this; overflow is reported. */
  static final int MAX_IDS = 4096;

  /** Ids listed in one remove_entities packet beyond which the payload is not believed. */
  static final int MAX_REMOVALS = 4096;

  /** Where a spawn fell relative to the fence, which is what separates a race from a plugin. */
  enum Phase {
    BEFORE_FENCE("before the fence was requested"),
    DURING_FENCE("while the fence request was in flight"),
    AFTER_FENCE("after the fence reply");

    private final String description;

    Phase(String description) {
      this.description = description;
    }

    @Override
    public String toString() {
      return description;
    }
  }

  private record Spawn(int sequence, Instant at, Phase phase) {}

  /** One backend connection's worth of forwarded entity lifecycle, in the order it was sent. */
  static final class Session {
    private final String backend;
    private final String instance;
    private final ProtocolVersion protocol;
    // Insertion-ordered so a report reads in the order the client received them, which is what
    // makes "immediately before cutoff" legible as distinct from "early in the session".
    private final Map<Integer, Spawn> live = new LinkedHashMap<>();
    private int sequence;
    private boolean overflowed;
    private int fenceRequested = -1;
    private int fenceReplied = -1;
    private @Nullable String transfer;

    Session(String backend, String instance, ProtocolVersion protocol) {
      this.backend = backend;
      this.instance = instance;
      this.protocol = protocol;
    }

    private Phase phase() {
      if (fenceRequested < 0) {
        return Phase.BEFORE_FENCE;
      }
      return fenceReplied < 0 ? Phase.DURING_FENCE : Phase.AFTER_FENCE;
    }

    synchronized void spawned(int id) {
      if (overflowed) {
        return;
      }
      if (live.size() >= MAX_IDS && !live.containsKey(id)) {
        overflowed = true;
        live.clear();
        return;
      }
      // A repeated id replaces the earlier entry: the client's table holds one entity per id, and
      // the later spawn is the one it is actually showing.
      live.put(id, new Spawn(++sequence, Instant.now(), phase()));
    }

    synchronized void removed(int id) {
      live.remove(id);
    }

    synchronized void fenceRequested(@Nullable String transfer) {
      this.transfer = transfer;
      this.fenceRequested = sequence;
      // A retry after an abort opens a new window. Without clearing the previous reply mark the
      // second cycle would classify its own in-flight spawns as having followed a snapshot that
      // belongs to the transfer before it.
      this.fenceReplied = -1;
    }

    synchronized void fenceReplied() {
      this.fenceReplied = sequence;
    }
  }

  /**
   * One thing the proxy forwarded, or did itself, in the order the client saw it.
   *
   * <p>{@code subject} is the profile or entity UUID the event concerns, where it has one. It is
   * carried as a field rather than left inside {@code detail} because correlating a spawn with the
   * player-list entry it needs is the entire point, and parsing that back out of a log line would
   * be a worse way to do it.
   */
  record Event(int sequence, Instant at, String origin, String kind, @Nullable UUID subject,
               @Nullable String name, String detail) {

    String render(@Nullable Instant previous) {
      String gap = previous == null ? ""
          : " +" + java.time.Duration.between(previous, at).toMillis() + "ms";
      return TIME.format(at) + gap + "  #" + sequence + " " + origin + " " + kind
          + (detail.isEmpty() ? "" : " " + detail);
    }
  }

  private static final java.time.format.DateTimeFormatter TIME =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
          .withZone(java.time.ZoneId.systemDefault());

  /**
   * A player's whole packet story, across backends, which is what a switch has to be read against.
   *
   * <p>Deliberately outlives {@link Session}: the question an arrival raises is what the
   * destination did after the switch, and the destination is a different connection from the one
   * whose entities were cleaned up. One monotonic sequence spans them both so ordering between a
   * cleanup and a destination spawn is a comparison rather than a guess.
   */
  static final class Trace {
    private final List<Event> events = new ArrayList<>();
    private int sequence;
    private int dumped;
    private boolean truncated;

    synchronized int record(String origin, String kind, @Nullable UUID subject,
        @Nullable String name, String detail) {
      int at = ++sequence;
      if (events.size() >= MAX_EVENTS) {
        truncated = true;
        events.remove(0);
        dumped = Math.max(0, dumped - 1);
      }
      events.add(new Event(at, Instant.now(), origin, kind, subject, name, detail));
      return at;
    }

    synchronized List<Event> since() {
      List<Event> fresh = List.copyOf(events.subList(dumped, events.size()));
      dumped = events.size();
      return fresh;
    }
  }

  /** Events kept per player before the oldest are dropped. A switch is a few dozen. */
  static final int MAX_EVENTS = 2048;

  private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  /**
   * Begins recording for a backend connection that has just entered PLAY, replacing any earlier
   * session for that player. The previous backend's ledger is deliberately dropped: only the
   * current source can strand entities in the client's table.
   */
  public void start(UUID player, String backend, String instance, ProtocolVersion protocol) {
    sessions.put(player, new Session(backend, instance, protocol));
    traces.computeIfAbsent(player, ignored -> new Trace())
        .record(backend + "/" + instance, "play-begins", null, null,
            "protocol " + protocol.getProtocol());
  }

  /** Whether anything is being recorded for this player, so callers can skip the work entirely. */
  public boolean records(UUID player) {
    return sessions.containsKey(player);
  }

  /**
   * Whether this player's entity table can be cleaned exactly.
   *
   * <p>False means the removal set would be a guess: either nothing was recorded for this source,
   * or the session overflowed and the ids it holds are a fragment of what the client has. A
   * seamless switch depends on being able to remove precisely what the source put there, so this
   * being false is a reason to send the client a visible reset instead - JoinGame and Respawn
   * rebuild the entity table wholesale, which is correct by construction where this is not.
   */
  public boolean tracksCompletely(UUID player) {
    Session session = sessions.get(player);
    if (session == null) {
      return false;
    }
    synchronized (session) {
      return !session.overflowed;
    }
  }

  void spawned(UUID player, int id) {
    spawned(player, id, null);
  }

  void spawned(UUID player, int id, @Nullable UUID entity) {
    Session session = sessions.get(player);
    if (session != null) {
      session.spawned(id);
      trace(player, session, "spawn", entity, null,
          "entity " + id + (entity == null ? "" : " uuid " + entity));
    }
  }

  void removed(UUID player, int id) {
    Session session = sessions.get(player);
    if (session != null) {
      session.removed(id);
      trace(player, session, "remove", null, null, "entity " + id);
    }
  }

  /** A profile added to the client's player list - what a player-type entity needs to render. */
  void tabAdded(UUID player, UUID profile, String name) {
    Session session = sessions.get(player);
    if (session != null) {
      trace(player, session, "tab-add", profile, name, profile + " (" + name + ")");
    }
  }

  /** A profile taken off the client's player list. */
  void tabRemoved(UUID player, UUID profile) {
    Session session = sessions.get(player);
    if (session != null) {
      trace(player, session, "tab-remove", profile, null, profile.toString());
    }
  }

  /** The proxy's own removal packet, recorded so ordering against it is readable. */
  public void cleanup(UUID player, int[] ids) {
    trace(player, sessions.get(player), "proxy-cleanup", null, null,
        ids.length + " ids " + java.util.Arrays.toString(ids));
  }

  /** The proxy clearing the client's player list on a seamless switch. */
  public void tabCleared(UUID player) {
    trace(player, sessions.get(player), "proxy-tab-clear", null, null, "");
  }

  private void trace(UUID player, @Nullable Session session, String kind, @Nullable UUID subject,
      @Nullable String name, String detail) {
    Trace trace = traces.get(player);
    if (trace != null) {
      trace.record(session == null ? "proxy" : session.backend + "/" + session.instance, kind,
          subject, name, detail);
    }
  }

  /**
   * Prints everything forwarded since the last dump, which is one arrival's worth of story.
   *
   * <p>Emitted on each switch rather than on a timer, so it covers the previous destination's
   * arrival in full without anything having to guess how long an arrival takes.
   */
  public void dump(UUID player, String label) {
    Trace trace = traces.get(player);
    if (trace == null) {
      return;
    }
    List<Event> events = trace.since();
    if (events.isEmpty()) {
      return;
    }
    StringBuilder detail = new StringBuilder();
    Instant previous = null;
    for (Event event : events) {
      detail.append(System.lineSeparator()).append("    ").append(event.render(previous));
      previous = event.at();
    }
    String verdict = spawnedWithoutProfile(events);
    logger.info("Packet trace for {} ({}), {} event(s){}:{}{}", player, label, events.size(),
        trace.truncated ? ", oldest dropped" : "", detail, verdict);
  }

  /**
   * Names the player-type entities that were spawned while their player-list entry was withdrawn.
   *
   * <p>A client resolves a player entity's profile from its player list at the moment the spawn
   * arrives. An entry added and then taken away again before the spawn leaves nothing to resolve,
   * so the entity is the one thing in the trace that can be read as a fault rather than as data.
   * Ordering alone decides it, which is why this is derived from the trace rather than guessed at.
   */
  private static String spawnedWithoutProfile(List<Event> events) {
    Map<UUID, String> named = new java.util.HashMap<>();
    Map<UUID, Boolean> live = new java.util.HashMap<>();
    List<String> orphaned = new ArrayList<>();
    for (Event event : events) {
      UUID subject = event.subject();
      if (subject == null) {
        continue;
      }
      switch (event.kind()) {
        case "tab-add" -> {
          live.put(subject, Boolean.TRUE);
          if (event.name() != null) {
            named.put(subject, event.name());
          }
        }
        case "tab-remove" -> live.put(subject, Boolean.FALSE);
        case "spawn" -> {
          Boolean present = live.get(subject);
          if (present != null && !present) {
            // Seen in the player list during this window, but withdrawn again before the spawn.
            orphaned.add(named.getOrDefault(subject, subject.toString()) + " (#" + event.sequence()
                + ")");
          }
        }
        default -> {
          // Nothing else bears on whether a profile was present when its entity spawned.
        }
      }
    }
    if (orphaned.isEmpty()) {
      return System.lineSeparator()
          + "    every player-type entity spawned with its profile still in the player list.";
    }
    return System.lineSeparator() + "    spawned with no live player-list entry, so the client had"
        + " no profile to resolve them against: " + String.join(", ", orphaned);
  }

  /** Marks the point the fence was asked for, so an in-flight spawn is not read as a pre-existing one. */
  public void fenceRequested(UUID player, @Nullable String transfer) {
    Session session = sessions.get(player);
    if (session != null) {
      session.fenceRequested(transfer);
    }
  }

  /** Marks the point the snapshot came back. Everything after this the snapshot cannot contain. */
  public void fenceReplied(UUID player) {
    Session session = sessions.get(player);
    if (session != null) {
      session.fenceReplied();
    }
  }

  /** Drops a player's ledger. Called when they disconnect, so nothing outlives a session. */
  public void clear(UUID player) {
    sessions.remove(player);
    traces.remove(player);
  }

  /** One id the client holds that the backend's snapshot did not account for. */
  record Discrepancy(int id, int sequence, Phase phase) {}

  /** What a switch looked like from the proxy's side, whether or not anything was wrong. */
  record Report(String backend, String instance, String transfer, ProtocolVersion protocol,
                int live, int snapshot, boolean overflowed, List<Discrepancy> unaccounted) {}

  /**
   * Compares what the client was actually sent against the backend's fence-time snapshot.
   *
   * <p>{@code retainedEntityId} is excluded. The client keeps its own player entity across a
   * seamless switch by design, and the backend leaves it out of the snapshot for that same reason,
   * so its absence is the intended outcome rather than a discrepancy.
   */
  Optional<Report> inspect(UUID player, int[] snapshot, int retainedEntityId) {
    Session session = sessions.get(player);
    if (session == null) {
      return Optional.empty();
    }
    synchronized (session) {
      List<Discrepancy> unaccounted = new ArrayList<>();
      if (!session.overflowed) {
        java.util.Set<Integer> accounted = new java.util.HashSet<>();
        for (int id : snapshot) {
          accounted.add(id);
        }
        for (Map.Entry<Integer, Spawn> entry : session.live.entrySet()) {
          int id = entry.getKey();
          if (id == retainedEntityId || accounted.contains(id)) {
            continue;
          }
          unaccounted.add(new Discrepancy(id, entry.getValue().sequence(), entry.getValue().phase()));
        }
      }
      return Optional.of(new Report(session.backend, session.instance,
          session.transfer == null ? "none" : session.transfer, session.protocol,
          session.live.size(), snapshot.length, session.overflowed, List.copyOf(unaccounted)));
    }
  }

  /**
   * Every id the client still holds from this source, which is what actually has to be removed.
   *
   * <p>The backend's snapshot is read from its entity tracker, so it is complete only for entities
   * the tracker knows about. An entity a plugin wrote straight to the connection was never in the
   * tracker and can never appear in that snapshot however carefully it is timed - but it is in the
   * client's table all the same, and the destination will reuse its id. The proxy forwarded it, so
   * the proxy knows about it; this is the union of both views.
   *
   * <p>A session that is absent or overflowed falls back to the snapshot alone. That is a guard
   * rather than a policy: {@link #tracksCompletely} refuses the seamless path in exactly those
   * cases, so a switch that reaches here has a complete ledger and the fallback is not the thing
   * standing between a client and a half-cleaned entity table.
   * {@code retainedEntityId} is never included: the client keeps its own player entity.
   */
  public int[] removalSet(UUID player, int[] snapshot, int retainedEntityId) {
    java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
    for (int id : snapshot) {
      if (id != retainedEntityId) {
        ids.add(id);
      }
    }
    Session session = sessions.get(player);
    if (session != null) {
      synchronized (session) {
        if (!session.overflowed) {
          for (Integer id : session.live.keySet()) {
            if (id != retainedEntityId) {
              ids.add(id);
            }
          }
        }
      }
    }
    int[] removal = new int[ids.size()];
    int index = 0;
    for (Integer id : ids) {
      removal[index++] = id;
    }
    return removal;
  }

  /** Says out loud what {@link #inspect} found, and changes nothing about the switch. */
  public void report(UUID player, int[] snapshot, int retainedEntityId, String destination) {
    Report report = inspect(player, snapshot, retainedEntityId).orElse(null);
    if (report == null) {
      return;
    }
    if (report.overflowed()) {
      logger.warn("Source entity ledger for {} on {} overflowed past {} live ids, so what the"
          + " client holds from this source is no longer known exactly. The switch takes the"
          + " visible path instead of guessing at a removal set.", player, report.backend(),
          MAX_IDS);
      return;
    }
    if (report.unaccounted().isEmpty()) {
      logger.info("Source entity ledger for {}: {} live id(s) forwarded from {} (instance {},"
          + " protocol {}), all accounted for by the backend snapshot of {}. Switching to {},"
          + " transfer {}.", player, report.live(), report.backend(), report.instance(),
          report.protocol().getProtocol(), report.snapshot(), destination, report.transfer());
      return;
    }
    StringBuilder detail = new StringBuilder();
    for (Discrepancy entry : report.unaccounted()) {
      detail.append(System.lineSeparator()).append("    id ").append(entry.id()).append(" forwarded ").append(entry.phase())
          .append(" (#").append(entry.sequence()).append(')');
    }
    // Not a failure, and deliberately not phrased as one: these are removed, by the union below,
    // using exactly this list. It is reported because an id the backend's tracker never held is
    // the signature of an entity a plugin wrote straight to the connection, and knowing how many
    // of those a hub has is worth having.
    logger.info("Source entity ledger for {}: {} id(s) the client was sent from {} (instance {},"
        + " protocol {}) are absent from the backend snapshot of {} - its tracker never held them -"
        + " and are removed from this ledger instead before {} reuses those ids. Transfer {}.{}",
        player, report.unaccounted().size(), report.backend(), report.instance(),
        report.protocol().getProtocol(), report.snapshot(), destination, report.transfer(), detail);
  }
}
