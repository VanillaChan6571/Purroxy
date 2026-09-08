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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persists UUID-based region preferences without writing files on player event loops. */
public final class RegionPreferences implements AutoCloseable {
  private final Path path;
  private volatile Map<UUID, String> preferences;
  private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "purroxy-region-preferences");
    thread.setDaemon(true);
    return thread;
  });

  /** Loads existing preferences; writes are serialized and atomically replace the file. */
  public RegionPreferences(Path path) throws IOException {
    this.path = path.toAbsolutePath();
    Map<UUID, String> loaded = new HashMap<>();
    if (Files.exists(this.path)) {
      Properties values = new Properties();
      try (var reader = Files.newBufferedReader(this.path)) {
        values.load(reader);
      }
      for (String id : values.stringPropertyNames()) {
        loaded.put(UUID.fromString(id), normalize(values.getProperty(id)));
      }
    }
    preferences = Map.copyOf(loaded);
  }

  public String get(UUID player) {
    return preferences.getOrDefault(player, "");
  }

  /** Saves the preference before publishing it; auto removes an explicit preference. */
  public CompletableFuture<Void> set(UUID player, String requested) {
    String region = normalize(requested);
    return CompletableFuture.runAsync(() -> {
      Map<UUID, String> updated = new HashMap<>(preferences);
      if (region.equals("auto")) {
        updated.remove(player);
      } else {
        updated.put(player, region);
      }
      Properties values = new Properties();
      updated.forEach((id, value) -> values.setProperty(id.toString(), value));
      try {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "purroxy-regions-", ".tmp");
        try {
          try (var output = Files.newBufferedWriter(temporary)) {
            values.store(output, "Purroxy preferred regions by player UUID");
          }
          Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          preferences = Map.copyOf(updated);
        } finally {
          Files.deleteIfExists(temporary);
        }
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }, writer);
  }

  private static String normalize(String value) {
    String region = value.toLowerCase(Locale.ROOT);
    if (!region.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Invalid region name");
    }
    return region;
  }

  @Override
  public void close() {
    writer.shutdown();
  }
}
