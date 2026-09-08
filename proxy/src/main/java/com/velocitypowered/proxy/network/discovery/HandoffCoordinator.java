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
  public record Peer(String name, UUID session, HandoffCapabilities capabilities) {}
  public record Ticket(UUID player, UUID transfer, long generation, String destination) {}

  interface Transport {
    Optional<Peer> current(String name);
    CompletableFuture<JsonObject> request(Peer peer, JsonObject request);
  }

  private static final Gson GSON = new Gson();
  private final HandoffStore store;
  private final Transport transport;
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();
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

  /** Executes export, stage, source fence, durable commit, then destination commit. */
  public CompletableFuture<@Nullable Ticket> prepare(UUID player, @Nullable String source, String destination,
                                                     String mode, BooleanSupplier valid, Executor playerLoop) {
    if (!active.add(player)) {
      return CompletableFuture.failedFuture(new IllegalStateException("A player handoff is already running"));
    }
    HandoffStore.Transfer previous = store.get(player);
    if (source == null) {
      return recoverLogin(previous, destination).whenComplete((ticket, failure) -> {
        if (ticket == null || failure != null) active.remove(player);
      });
    }
    if (previous != null && previous.phase() == HandoffStore.Phase.COMMITTED) {
      active.remove(player);
      return CompletableFuture.failedFuture(new IllegalStateException("Previous committed handoff needs recovery"));
    }
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
    CompletableFuture<Void> cleanup = previous != null && previous.phase() == HandoffStore.Phase.ABORTED
        ? rollback(previous) : CompletableFuture.completedFuture(null);
    return cleanup.thenCompose(ignored -> onIo(() -> store.begin(player, source, destination, System.currentTimeMillis() + 25000)))
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
          expect(reply, transfer, "DESTINATION", "STAGED");
          return transfer;
        }))
        .thenCompose(transfer -> check(transfer, origin, target, valid, playerLoop))
        .thenCompose(transfer -> call(origin, transfer, "fence").thenApply(reply -> {
          expect(reply, transfer, "SOURCE", "FENCED");
          return transfer;
        }))
        .thenCompose(transfer -> check(transfer, origin, target, valid, playerLoop))
        .thenCompose(transfer -> onIo(() -> store.update(transfer.withPhase(HandoffStore.Phase.COMMITTED))))
        .thenCompose(transfer -> call(target, transfer, "commit").thenApply(reply -> {
          expect(reply, transfer, "DESTINATION", "COMMITTED", "ACTIVATED");
          return ticket(transfer);
        }))
        .exceptionallyCompose(failure -> abortBeforeCommit(player).handle((ignored, abortFailure) -> {
          if (abortFailure != null) failure.addSuppressed(abortFailure);
          throw new java.util.concurrent.CompletionException(failure);
        }))
        .whenComplete((ticket, failure) -> {
          if (failure != null) active.remove(player);
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
    return abortPeer(transfer.source(), transfer, "SOURCE")
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

  private CompletableFuture<@Nullable Ticket> recoverLogin(@Nullable HandoffStore.Transfer transfer, String destination) {
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
    HandoffStore.Transfer transfer = store.get(ticket.player());
    if (transfer == null || !transfer.id().equals(ticket.transfer())) {
      active.remove(ticket.player());
      return CompletableFuture.failedFuture(new IllegalStateException("Superseded handoff completion"));
    }
    if (!connected) {
      active.remove(ticket.player());
      return CompletableFuture.completedFuture(null); // COMMITTED remains recoverable; never roll it back.
    }
    Peer source = transport.current(transfer.source()).orElse(null);
    if (source == null) {
      active.remove(ticket.player());
      return CompletableFuture.failedFuture(new IllegalStateException("Source release awaits its control connection"));
    }
    return call(source, transfer, "release").thenCompose(reply -> {
      expect(reply, transfer, "SOURCE", "RELEASED");
      return onIo(() -> store.update(transfer.withPhase(HandoffStore.Phase.COMPLETE)));
    }).thenAccept(ignored -> {}).whenComplete((ignored, failure) -> active.remove(ticket.player()));
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
    request.addProperty("expiresAtMillis", transfer.phase() == HandoffStore.Phase.COMMITTED
        ? System.currentTimeMillis() + 25000 : transfer.expiresAtMillis());
    if (transfer.position() != null) request.add("snapshot", GSON.toJsonTree(transfer.position()));
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
      try { return operation.get(); }
      catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
    }, io);
  }

  @FunctionalInterface
  private interface IoSupplier<T> { T get() throws Exception; }

  @Override
  public void close() {
    io.shutdown();
  }
}
