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

package com.velocitypowered.proxy.network.discovery;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Single-proxy coordinator. The persisted decision, not an RPC timeout, determines ownership. */
public final class HandoffCoordinator implements AutoCloseable {
  private static final org.apache.logging.log4j.Logger logger =
      org.apache.logging.log4j.LogManager.getLogger(HandoffCoordinator.class);

  /** An authenticated backend incarnation and its advertised replica contract. */
  public record Peer(String name, UUID session, HandoffCapabilities capabilities) {}

  /** Identifies the committed transaction associated with a connection attempt. */
  public record Ticket(UUID player, UUID transfer, long generation, String destination) {}

  interface Transport {
    Optional<Peer> current(String name);

    CompletableFuture<JsonObject> request(Peer peer, JsonObject request);
  }

  private static final Gson GSON = new Gson();
  private final HandoffStore store;
  private final Transport transport;
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();
  private final Set<UUID> rolledBackSources = ConcurrentHashMap.newKeySet();
  private final java.util.Map<UUID, UUID> recoveredAborts = new ConcurrentHashMap<>();
  // The entity id the client already holds on the source, asked of the destination at stage time.
  private final java.util.Map<UUID, Integer> requestedEntityIds = new ConcurrentHashMap<>();
  // What the destination actually reserved. Equal to the requested id means the client can keep it.
  private final java.util.Map<UUID, Integer> reservedEntityIds = new ConcurrentHashMap<>();
  // Entities the source had shown this client, as reported on the fence reply. The proxy removes
  // them from the client itself, so a source that dies between fencing and the switch cannot leave
  // the client holding entities nothing will ever clear.
  private final java.util.Map<UUID, int[]> sourceEntities = new ConcurrentHashMap<>();
  private final java.util.Map<UUID, Ticket> pendingReleases = new ConcurrentHashMap<>();
  // Transfers whose destination was told to suppress its arrival sync. Only these make the
  // matching visible-arrival mark load-bearing on a fallback.
  private final java.util.Map<UUID, UUID> seamlessApprovals = new ConcurrentHashMap<>();
  private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "purroxy-handoff-journal");
    thread.setDaemon(true);
    return thread;
  });

  HandoffCoordinator(Path directory, Transport transport) throws IOException {
    this.store = new HandoffStore(directory);
    this.transport = transport;
  }

  /** Only interrupted commits pin recovery routing; completed handoffs restore ordinary routing. */
  public Optional<String> recoveryOwner(UUID player) {
    HandoffStore.Transfer transfer = store.get(player);
    return transfer != null && transfer.phase() == HandoffStore.Phase.COMMITTED
        ? Optional.of(transfer.destination()) : Optional.empty();
  }

  /** A lost fence acknowledgment cannot be treated as permission to keep playing on the source. */
  public boolean requiresRecovery(UUID player) {
    HandoffStore.Transfer transfer = store.get(player);
    return transfer != null && (transfer.phase() == HandoffStore.Phase.COMMITTED
        || transfer.phase() == HandoffStore.Phase.ABORTED && !rolledBackSources.contains(transfer.id()));
  }

  /** Entities the source had shown this client, for the proxy to remove before the switch. */
  public int[] sourceEntities(UUID player) {
    return sourceEntities.getOrDefault(player, new int[0]);
  }

  /** The entity id the destination reserved for this player, or 0 when none was negotiated. */
  public int reservedEntityId(UUID player) {
    return reservedEntityIds.getOrDefault(player, 0);
  }

  /** Whether this player's committed transfer already had its arrival sync suppressed. */
  public boolean seamlessArrivalApproved(UUID player) {
    HandoffStore.Transfer transfer = store.get(player);
    return transfer != null && transfer.id().equals(seamlessApprovals.get(player));
  }

  /** Whether staging retained the exact entity id the connected client already holds. */
  public boolean canRetainEntityId(UUID player) {
    int requested = requestedEntityIds.getOrDefault(player, 0);
    return requested > 0 && reservedEntityIds.getOrDefault(player, 0) == requested;
  }

  /** Whether a backend advertises the arrival-control operations this proxy would send it. */
  public boolean supportsArrivalControl(String backend) {
    return transport.current(backend).filter(peer -> peer.capabilities().seamless()).isPresent();
  }

  /** Approves arrival-sync suppression only after detached CONFIG has matched. */
  public CompletableFuture<Void> approveSeamlessArrival(UUID player, String destination) {
    if (!supportsArrivalControl(destination)) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Destination does not advertise seamless arrival control"));
    }
    HandoffStore.Transfer transfer = store.get(player);
    if (transfer == null || transfer.phase() != HandoffStore.Phase.COMMITTED
        || !transfer.destination().equals(destination) || !canRetainEntityId(player)) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Seamless arrival does not match a committed entity-id reservation"));
    }
    Peer peer = transport.current(destination).orElse(null);
    if (peer == null) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Seamless arrival awaits the committed destination"));
    }
    return call(peer, transfer, "seamless").thenApply(reply -> {
      expect(reply, transfer, "DESTINATION", "COMMITTED", "ACTIVATED");
      seamlessApprovals.put(player, transfer.id());
      return null;
    });
  }

  /** Makes a committed destination use its ordinary arrival position sync before a visible retry. */
  public CompletableFuture<Void> requireVisibleArrival(UUID player, String destination) {
    if (!supportsArrivalControl(destination)) {
      // Such a backend was never asked to suppress anything, because this proxy refuses the
      // detached path against it. There is no mark to undo, so demanding one would fail a
      // transfer over an operation the destination does not even implement.
      return CompletableFuture.completedFuture(null);
    }
    HandoffStore.Transfer transfer = store.get(player);
    if (transfer == null || transfer.phase() != HandoffStore.Phase.COMMITTED
        || !transfer.destination().equals(destination)) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Visible arrival does not match a committed destination"));
    }
    Peer peer = transport.current(destination).orElse(null);
    if (peer == null) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Visible arrival awaits the committed destination"));
    }
    return call(peer, transfer, "visible").thenApply(reply -> {
      expect(reply, transfer, "DESTINATION", "COMMITTED", "ACTIVATED");
      seamlessApprovals.remove(player, transfer.id());
      return null;
    });
  }

  /** Executes export, stage, source fence, durable commit, then destination commit. */
  public CompletableFuture<@Nullable Ticket> prepare(UUID player, @Nullable String source, String destination,
                                                     String mode, BooleanSupplier valid, Executor playerLoop) {
    return prepare(player, source, destination, mode, valid, playerLoop, 0);
  }

  /**
   * As above, additionally asking the destination to keep {@code requestedEntityId} for this player so
   * the client need not be reset. Zero asks for nothing and the destination allocates its own id.
   */
  public CompletableFuture<@Nullable Ticket> prepare(UUID player, @Nullable String source, String destination,
                                                     String mode, BooleanSupplier valid, Executor playerLoop,
                                                     int requestedEntityId) {
    requestedEntityIds.put(player, requestedEntityId);
    if (!active.add(player)) {
      return CompletableFuture.failedFuture(new IllegalStateException("A player handoff is already running"));
    }
    HandoffStore.Transfer previous = store.get(player);
    if (source == null) {
      return recoverLogin(previous, destination).whenComplete((ticket, failure) -> {
        if (ticket == null || failure != null) {
          active.remove(player);
        }
      });
    }
    if (previous != null && previous.phase() == HandoffStore.Phase.COMMITTED) {
      active.remove(player);
      return CompletableFuture.failedFuture(new IllegalStateException("Previous committed handoff needs recovery"));
    }
    if (previous != null && previous.phase() == HandoffStore.Phase.ABORTED) {
      return rollback(previous).thenCompose(ignored -> {
        return prepareActive(player, source, destination, mode, valid, playerLoop);
      }).whenComplete((ticket, failure) -> {
        if (failure != null) {
          active.remove(player);
        }
      });
    }
    return prepareActive(player, source, destination, mode, valid, playerLoop);
  }

  private CompletableFuture<@Nullable Ticket> prepareActive(UUID player, String source, String destination,
      String mode, BooleanSupplier valid, Executor playerLoop) {
    Optional<Peer> from = transport.current(source);
    Optional<Peer> to = transport.current(destination);
    if (mode.equals("off") || from.isEmpty() || to.isEmpty()
        || !from.get().capabilities().matches(to.get().capabilities())) {
      active.remove(player);
      return mode.equals("seamless-required")
          ? CompletableFuture.failedFuture(new IllegalStateException("Required handoff compatibility is unavailable"))
          : CompletableFuture.completedFuture(null);
    }
    // Client-continuity qualification is separate from data handoff. Never suppress resets on this capability alone.
    if (mode.equals("seamless-required")) {
      active.remove(player);
      return CompletableFuture.failedFuture(new IllegalStateException("Native seamless client continuity is not yet qualified"));
    }
    Peer origin = from.get();
    Peer target = to.get();
    return onIo(() -> store.begin(player, source, destination, System.currentTimeMillis() + 25000))
        .thenCompose(transfer -> call(origin, transfer, "export").thenCompose(reply -> {
          JsonObject entry = expect(reply, transfer, "SOURCE", "EXPORTED");
          HubPosition position = GSON.fromJson(entry.get("snapshot"), HubPosition.class);
          if (!origin.capabilities().worldIdentity().equals(position.worldIdentity())
              || !origin.capabilities().mapRevision().equals(position.mapRevision())) {
            throw new IllegalStateException("Source snapshot does not match negotiated replica identity");
          }
          return onIo(() -> store.update(transfer.withPosition(position)));
        }))
        .thenCompose(transfer -> call(target, transfer, "stage").thenApply(reply -> {
          JsonObject staged = expect(reply, transfer, "DESTINATION", "STAGED");
          // The destination reserves the id before the client connects, so its reply says whether the
          // client keeps the id it already holds. A different id means the client must still be reset.
          int reserved = staged.has("requestedEntityId") ? staged.get("requestedEntityId").getAsInt() : 0;
          int asked = requestedEntityIds.getOrDefault(transfer.player(), 0);
          if (reserved > 0 && asked > 0) {
            logger.info("Handoff to {}: destination reserved entity id {} for a client holding {}{}",
                transfer.destination(), reserved, asked, reserved == asked ? " (kept)" : " (changed)");
          }
          reservedEntityIds.put(transfer.player(), reserved);
          return transfer;
        }))
        .thenCompose(transfer -> check(transfer, origin, target, valid, playerLoop))
        .thenCompose(transfer -> call(origin, transfer, "fence").thenApply(reply -> {
          expect(reply, transfer, "SOURCE", "FENCED");
          if (reply.has("trackedEntities")) {
            var reported = reply.getAsJsonArray("trackedEntities");
            int[] ids = new int[reported.size()];
            for (int index = 0; index < ids.length; index++) {
              ids[index] = reported.get(index).getAsInt();
            }
            sourceEntities.put(transfer.player(), ids);
          }
          return transfer;
        }))
        .thenCompose(transfer -> check(transfer, origin, target, valid, playerLoop))
        .thenCompose(transfer -> onIo(() -> store.update(transfer.withPhase(HandoffStore.Phase.COMMITTED))))
        .thenCompose(transfer -> call(target, transfer, "commit").thenApply(reply -> {
          expect(reply, transfer, "DESTINATION", "COMMITTED", "ACTIVATED");
          return ticket(transfer);
        }))
        .exceptionallyCompose(failure -> abortBeforeCommit(player).handle((ignored, abortFailure) -> {
          if (abortFailure != null) {
            failure.addSuppressed(abortFailure);
          }
          throw new java.util.concurrent.CompletionException(failure);
        }))
        .whenComplete((ticket, failure) -> {
          if (failure != null) {
            active.remove(player);
          }
        });
  }

  private CompletableFuture<HandoffStore.Transfer> check(HandoffStore.Transfer transfer, Peer source, Peer target,
                                                          BooleanSupplier valid, Executor playerLoop) {
    return CompletableFuture.supplyAsync(() -> {
      if (!valid.getAsBoolean() || System.currentTimeMillis() >= transfer.expiresAtMillis()
          || !transport.current(source.name()).filter(source::equals).isPresent()
          || !transport.current(target.name()).filter(target::equals).isPresent()) {
        throw new IllegalStateException("Handoff admission, player or backend session changed before commit");
      }
      return transfer;
    }, playerLoop);
  }

  private CompletableFuture<Void> abortBeforeCommit(UUID player) {
    HandoffStore.Transfer transfer = store.get(player);
    if (transfer == null || transfer.phase() == HandoffStore.Phase.COMMITTED
        || transfer.phase() == HandoffStore.Phase.COMPLETE) {
      return CompletableFuture.completedFuture(null);
    }
    return onIo(() -> store.update(transfer.withPhase(HandoffStore.Phase.ABORTED))).thenCompose(this::rollback);
  }

  private CompletableFuture<Void> rollback(HandoffStore.Transfer transfer) {
    // The ABORT decision was forced to disk before either of these messages is sent.
    return abortPeer(transfer.source(), transfer, "SOURCE").thenRun(() -> rolledBackSources.add(transfer.id()))
        .thenCompose(ignored -> abortPeer(transfer.destination(), transfer, "DESTINATION"));
  }

  private CompletableFuture<Void> abortPeer(String name, HandoffStore.Transfer transfer, String role) {
    Peer peer = transport.current(name).orElse(null);
    if (peer == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("Rollback awaits backend " + name));
    }
    return call(peer, transfer, "status").thenCompose(reply -> {
      if ("NOT_FOUND".equals(reply.get("status").getAsString())) {
        return CompletableFuture.completedFuture(null);
      }
      JsonObject entry = expect(reply, transfer, role, "EXPORTED", "STAGED", "FENCED", "ABORTED");
      if (entry.get("phase").getAsString().equals("ABORTED")) {
        return CompletableFuture.completedFuture(null);
      }
      return call(peer, transfer, "abort").thenApply(aborted -> {
        expect(aborted, transfer, role, "ABORTED");
        return null;
      });
    });
  }

  private CompletableFuture<@Nullable Ticket> recoverLogin(HandoffStore.@Nullable Transfer transfer, String destination) {
    if (transfer == null || transfer.phase() == HandoffStore.Phase.COMPLETE) {
      return CompletableFuture.completedFuture(null);
    }
    if (transfer.phase() == HandoffStore.Phase.ABORTED) {
      return rollback(transfer).thenApply(ignored -> null);
    }
    if (!transfer.destination().equals(destination)) {
      return CompletableFuture.failedFuture(new IllegalStateException("Reconnect must recover at the committed owner"));
    }
    Peer peer = transport.current(destination).orElse(null);
    if (peer == null || transfer.position() == null
        || !peer.capabilities().worldIdentity().equals(transfer.position().worldIdentity())
        || !peer.capabilities().mapRevision().equals(transfer.position().mapRevision())) {
      return CompletableFuture.failedFuture(new IllegalStateException("Committed destination is not available for recovery"));
    }
    return call(peer, transfer, "status").thenCompose(reply -> {
      if ("NOT_FOUND".equals(reply.get("status").getAsString())) {
        // The coordinator retains the committed snapshot even if the backend lost its local staging record.
        return call(peer, transfer, "stage").thenApply(staged -> {
          expect(staged, transfer, "DESTINATION", "STAGED");
          return staged;
        });
      }
      expect(reply, transfer, "DESTINATION", "STAGED", "COMMITTED", "ACTIVATED");
      return CompletableFuture.completedFuture(reply);
    }).thenCompose(ignored -> call(peer, transfer, "commit")).thenApply(reply -> {
      expect(reply, transfer, "DESTINATION", "COMMITTED", "ACTIVATED");
      return ticket(transfer);
    });
  }

  /** Releases the fenced source only after the destination became the live connection. */
  public CompletableFuture<Void> finish(Ticket ticket, boolean connected) {
    requestedEntityIds.remove(ticket.player()); // The request belongs to this transfer only.
    reservedEntityIds.remove(ticket.player());
    seamlessApprovals.remove(ticket.player(), ticket.transfer());
    sourceEntities.remove(ticket.player());
    HandoffStore.Transfer transfer = store.get(ticket.player());
    if (transfer == null || !transfer.id().equals(ticket.transfer())
        || transfer.generation() != ticket.generation() || !transfer.destination().equals(ticket.destination())) {
      active.remove(ticket.player());
      return CompletableFuture.failedFuture(new IllegalStateException("Superseded handoff completion"));
    }
    if (!connected) {
      active.remove(ticket.player());
      return CompletableFuture.completedFuture(null); // COMMITTED remains recoverable; never roll it back.
    }
    pendingReleases.put(ticket.player(), ticket);
    Peer source = transport.current(transfer.source()).orElse(null);
    if (source == null) {
      active.remove(ticket.player());
      return CompletableFuture.failedFuture(new IllegalStateException("Source release awaits its control connection"));
    }
    return call(source, transfer, "release").thenCompose(reply -> {
      expect(reply, transfer, "SOURCE", "RELEASED");
      return onIo(() -> store.update(transfer.withPhase(HandoffStore.Phase.COMPLETE)));
    }).thenAccept(ignored -> pendingReleases.remove(ticket.player(), ticket))
        .whenComplete((ignored, failure) -> active.remove(ticket.player()));
  }

  /** Retries releases whose destination connection already succeeded in this proxy process. */
  public CompletableFuture<Void> retryReleases() {
    java.util.List<CompletableFuture<Void>> attempts = new java.util.ArrayList<>();
    for (Ticket ticket : java.util.List.copyOf(pendingReleases.values())) {
      if (active.add(ticket.player())) {
        attempts.add(finish(ticket, true).exceptionally(failure -> null));
      }
    }
    return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new));
  }

  /** Reconciles durable aborts when backend control connections return, without waiting for a login. */
  public CompletableFuture<Void> recoverAborts() {
    java.util.List<CompletableFuture<Void>> attempts = new java.util.ArrayList<>();
    for (HandoffStore.Transfer transfer : store.snapshot().values()) {
      if (transfer.phase() != HandoffStore.Phase.ABORTED
          || transfer.id().equals(recoveredAborts.get(transfer.player())) || !active.add(transfer.player())) {
        continue;
      }
      attempts.add(rollback(transfer).thenRun(() -> recoveredAborts.put(transfer.player(), transfer.id()))
          .whenComplete((ignored, failure) -> active.remove(transfer.player())).exceptionally(failure -> null));
    }
    return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new));
  }

  private CompletableFuture<JsonObject> call(Peer peer, HandoffStore.Transfer transfer, String action) {
    JsonObject request = new JsonObject();
    request.addProperty("action", action);
    request.addProperty("transfer", transfer.id().toString());
    request.addProperty("player", transfer.player().toString());
    request.addProperty("generation", transfer.generation());
    request.addProperty("source", transfer.source());
    request.addProperty("destination", transfer.destination());
    request.addProperty("profile", "hub-position");
    if ("stage".equals(action)) {
      request.addProperty("requestedEntityId", requestedEntityIds.getOrDefault(transfer.player(), 0));
    }
    request.addProperty("expiresAtMillis", transfer.phase() == HandoffStore.Phase.COMMITTED
        ? System.currentTimeMillis() + 25000 : transfer.expiresAtMillis());
    if (transfer.position() != null) {
      request.add("snapshot", GSON.toJsonTree(transfer.position()));
    }
    return transport.request(peer, request);
  }

  private static JsonObject expect(JsonObject reply, HandoffStore.Transfer transfer, String role, String... phases) {
    if (!"OK".equals(reply.get("status").getAsString())) {
      throw new IllegalStateException("Backend rejected the handoff operation");
    }
    JsonObject entry = reply.getAsJsonObject("entry");
    if (!entry.get("transfer").getAsString().equals(transfer.id().toString())
        || !entry.get("player").getAsString().equals(transfer.player().toString())
        || entry.get("generation").getAsLong() != transfer.generation()
        || !entry.get("source").getAsString().equals(transfer.source())
        || !entry.get("destination").getAsString().equals(transfer.destination())
        || !entry.get("role").getAsString().equals(role)
        || !Set.of(phases).contains(entry.get("phase").getAsString())
        || transfer.position() != null && !transfer.position().equals(GSON.fromJson(entry.get("snapshot"), HubPosition.class))) {
      throw new IllegalStateException("Backend handoff reply does not match the transaction");
    }
    return entry;
  }

  private static Ticket ticket(HandoffStore.Transfer transfer) {
    return new Ticket(transfer.player(), transfer.id(), transfer.generation(), transfer.destination());
  }

  private <T> CompletableFuture<T> onIo(IoSupplier<T> operation) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return operation.get();
      } catch (Exception failure) {
        throw new java.util.concurrent.CompletionException(failure);
      }
    }, io);
  }

  @FunctionalInterface
  private interface IoSupplier<T> { T get() throws Exception; }

  @Override
  public void close() {
    io.shutdown();
  }
}
