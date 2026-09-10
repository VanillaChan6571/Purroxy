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

/** Version-one hub-position payload, independently validated before journalling or forwarding. */
public record HubPosition(String worldIdentity, String mapRevision, double x, double y, double z,
                          float yaw, float pitch, double velocityX, double velocityY, double velocityZ) {
  /** Rejects invalid replica identities and non-finite or out-of-range positions. */
  public HubPosition {
    new HandoffCapabilities(1, "hub-position", worldIdentity, mapRevision, false);
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
        || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984 || Math.abs(y) > 20_000_000
        || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90
        || !Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
        || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
      throw new IllegalArgumentException("Invalid hub-position payload");
    }
  }
}
