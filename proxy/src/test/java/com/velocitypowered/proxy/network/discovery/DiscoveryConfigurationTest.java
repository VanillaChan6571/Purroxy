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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryConfigurationTest {
  @TempDir
  Path directory;

  @Test
  void firstRunCreatesDisabledConfiguration() throws IOException {
    Path path = directory.resolve("purroxy-network.toml");
    assertTrue(DiscoveryConfiguration.read(path).isEmpty());
    assertTrue(Files.readString(path).contains("enabled = false"));
  }

  @Test
  void invalidEnabledConfigurationFailsInsteadOfOpeningUntrustedDiscovery() throws IOException {
    Path path = directory.resolve("purroxy-network.toml");
    Files.writeString(path, "format-version = 2\n[discovery]\nenabled = true\n");
    assertThrows(IOException.class, () -> DiscoveryConfiguration.read(path));
  }

  @Test
  void identityRequiresCertificateEndpointAndGroupTogether() {
    var identity = new DiscoveryConfiguration.Identity("ab".repeat(32), "backend.internal",
        25565, Set.of("hub"));
    var resume = new BackendResume("hub-1", UUID.randomUUID(), "backend.internal", 25565,
        "Hub", "none", 50, 80, "US-West");
    assertTrue(identity.permits(resume, "AB".repeat(32)));
    assertFalse(identity.permits(resume, "cd".repeat(32)));
    var wrongEndpoint = new BackendResume("hub-1", resume.incarnation(), "other.internal", 25565,
        "hub", "none", 50, 80, "us-west");
    assertFalse(identity.permits(wrongEndpoint, "ab".repeat(32)));
    var wrongGroup = new BackendResume("hub-1", resume.incarnation(), "backend.internal", 25565,
        "survival", "none", 50, 80, "us-west");
    assertFalse(identity.permits(wrongGroup, "ab".repeat(32)));
  }
}
