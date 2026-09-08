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

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable backend advertisement; authentication is performed before accepting it. */
public record BackendResume(String serverId, UUID incarnation, String host, int port,
                            String group, String map, int safeLimit, int hardLimit,
                            String region) {

  /** Validates the advertised endpoint and routing metadata without performing DNS lookups. */
  public BackendResume {
    serverId = identifier(serverId, "server ID");
    Objects.requireNonNull(incarnation, "incarnation");
    host = Objects.requireNonNull(host, "host").trim();
    if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::")
        || host.equals("[::]") || port < 1 || port > 65535) {
      throw new IllegalArgumentException("A reachable Minecraft host and port are required");
    }
    group = identifier(group, "group");
    map = Objects.requireNonNull(map, "map").trim();
    if (map.isEmpty() || map.equals(".") || map.equals("..")
        || map.contains("/") || map.contains("\\") || map.contains(":")) {
      throw new IllegalArgumentException("Map must name a world inside the world container");
    }
    if (safeLimit < 1 || hardLimit < 1) {
      throw new IllegalArgumentException("Safe and hard limits must be positive");
    }
    region = identifier(region, "region");
  }

  private static String identifier(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Invalid " + field);
    }
    return normalized;
  }
}
