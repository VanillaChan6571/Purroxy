package com.velocitypowered.proxy.network.discovery;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Durable decisions for a single coordinating proxy. Mutations run on its handoff executor. */
final class HandoffStore {
  enum Phase { PREPARING, COMMITTED, COMPLETE, ABORTED }

  record Transfer(UUID id, UUID player, long generation, String source, String destination,
                  long expiresAtMillis, Phase phase, @Nullable HubPosition position) {
    Transfer {
      if (id == null || player == null || generation < 1 || source == null || destination == null
          || !source.matches("[a-z0-9][a-z0-9_-]{0,63}")
          || !destination.matches("[a-z0-9][a-z0-9_-]{0,63}") || source.equals(destination)
          || phase == null || (phase == Phase.COMMITTED || phase == Phase.COMPLETE) && position == null) {
        throw new IllegalArgumentException("Invalid handoff decision record");
      }
    }

    Transfer withPhase(Phase next) {
      return new Transfer(id, player, generation, source, destination, expiresAtMillis, next, position);
    }

    Transfer withPosition(HubPosition snapshot) {
      return new Transfer(id, player, generation, source, destination, expiresAtMillis, phase, snapshot);
    }
  }

  private static final Gson GSON = new Gson();
  private final Path directory;
  private final Map<UUID, Transfer> entries = new ConcurrentHashMap<>();

  HandoffStore(Path directory) throws IOException {
    this.directory = directory;
    Files.createDirectories(directory);
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
        if (Files.size(file) > 8192) {
          throw new IOException("Oversized proxy handoff record");
        }
        try {
          Transfer transfer = GSON.fromJson(Files.readString(file), Transfer.class);
          if (transfer == null || !file.getFileName().toString().equals(transfer.player() + ".json")) {
            throw new IllegalArgumentException("Handoff record filename mismatch");
          }
          entries.put(transfer.player(), transfer);
        } catch (RuntimeException failure) {
          throw new IOException("Invalid proxy handoff journal: " + file.getFileName(), failure);
        }
      }
    }
    // No transaction from the previous process may cross a commit point after restart.
    for (Transfer transfer : Map.copyOf(entries).values()) {
      if (transfer.phase() == Phase.PREPARING) {
        update(transfer.withPhase(Phase.ABORTED));
      }
    }
  }

  @Nullable Transfer get(UUID player) {
    return entries.get(player);
  }

  Map<UUID, Transfer> snapshot() {
    return Map.copyOf(entries);
  }

  synchronized Transfer begin(UUID player, String source, String destination, long deadline) throws IOException {
    Transfer old = entries.get(player);
    if (old != null && (old.phase() == Phase.PREPARING || old.phase() == Phase.COMMITTED)) {
      throw new IllegalStateException("A handoff is already unresolved");
    }
    Transfer transfer = new Transfer(UUID.randomUUID(), player, old == null ? 1 : Math.addExact(old.generation(), 1),
        source, destination, deadline, Phase.PREPARING, null);
    persist(transfer);
    return transfer;
  }

  synchronized Transfer update(Transfer transfer) throws IOException {
    Transfer old = entries.get(transfer.player());
    if (old == null || !old.id().equals(transfer.id()) || old.generation() != transfer.generation()
        || !old.source().equals(transfer.source()) || !old.destination().equals(transfer.destination())
        || old.expiresAtMillis() != transfer.expiresAtMillis()
        || old.position() != null && !old.position().equals(transfer.position())) {
      throw new IllegalStateException("Unknown or altered handoff decision");
    }
    boolean valid = old.equals(transfer) || old.phase() == Phase.PREPARING
        && (transfer.phase() == Phase.PREPARING || transfer.phase() == Phase.COMMITTED || transfer.phase() == Phase.ABORTED)
        || old.phase() == Phase.COMMITTED && transfer.phase() == Phase.COMPLETE;
    if (!valid) {
      throw new IllegalStateException("Illegal handoff decision transition");
    }
    persist(transfer);
    return transfer;
  }

  private void persist(Transfer transfer) throws IOException {
    Path temporary = directory.resolve(transfer.player() + ".tmp");
    Path target = directory.resolve(transfer.player() + ".json");
    try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(GSON.toJson(transfer).getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) {
        file.write(bytes);
      }
      file.force(true);
    }
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    entries.put(transfer.player(), transfer);
  }
}
