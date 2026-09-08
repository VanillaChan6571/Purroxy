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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionPreferencesTest {
  @TempDir
  Path directory;

  @Test
  void persistsByUuidAndAutoRemovesPreference() throws IOException {
    Path path = directory.resolve("regions.properties");
    UUID player = UUID.randomUUID();
    try (RegionPreferences preferences = new RegionPreferences(path)) {
      preferences.set(player, "US-West").join();
    }
    try (RegionPreferences preferences = new RegionPreferences(path)) {
      assertEquals("us-west", preferences.get(player));
      preferences.set(player, "auto").join();
    }
    try (RegionPreferences preferences = new RegionPreferences(path)) {
      assertEquals("", preferences.get(player));
    }
  }

  @Test
  void failedWriteDoesNotPublishAnUnsavedPreference() throws IOException {
    Path path = directory.resolve("regions.properties");
    UUID player = UUID.randomUUID();
    try (RegionPreferences preferences = new RegionPreferences(path)) {
      Files.createDirectory(path);
      Files.writeString(path.resolve("occupied"), "prevent directory replacement");
      assertThrows(CompletionException.class, () -> preferences.set(player, "US-West").join());
      assertEquals("", preferences.get(player));
    }
  }
}
