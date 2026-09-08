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

/** Explicit backend contract; discovery alone never implies transfer compatibility. */
public record HandoffCapabilities(int version, String profile, String worldIdentity,
                                  String mapRevision, boolean seamless) {
  public HandoffCapabilities {
    if (version != 1 || !"hub-position".equals(profile) || worldIdentity == null
        || !worldIdentity.matches("[a-z0-9][a-z0-9_-]{0,63}") || mapRevision == null
        || !mapRevision.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("Unsupported backend handoff capability");
    }
  }

  public boolean matches(HandoffCapabilities other) {
    return version == other.version && profile.equals(other.profile)
        && worldIdentity.equals(other.worldIdentity) && mapRevision.equals(other.mapRevision);
  }
}
