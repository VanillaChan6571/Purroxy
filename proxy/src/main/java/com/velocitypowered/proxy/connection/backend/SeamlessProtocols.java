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

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The one place that decides whether a protocol may take the client-invisible path.
 *
 * <p>Eligibility used to be re-derived at each site that needed it - configuration capture, the
 * JoinGame comparison, the detached login probe - which is how a client could be refused for
 * lacking a baseline it was never allowed to capture. Everything now asks here.
 *
 * <p>{@link ProtocolVersion#MINECRAFT_26_2} is qualified. Anything else is a canary: the transfer
 * machinery is version-neutral, but no other family has been through the qualification matrix, so
 * each one stays off until it has been.
 */
public final class SeamlessProtocols {

  /** Qualified by the live 26.2 two-hub soak; never gated behind the canary switch. */
  private static final ProtocolVersion QUALIFIED = ProtocolVersion.MINECRAFT_26_2;

  private SeamlessProtocols() {
  }

  /**
   * Whether a connection negotiated at {@code protocol} may be captured and compared for a
   * seamless switch.
   */
  public static boolean eligible(@Nullable VelocityServer proxy,
      @Nullable ProtocolVersion protocol) {
    if (protocol == null || protocol == ProtocolVersion.UNKNOWN) {
      return false;
    }
    if (protocol == QUALIFIED) {
      return true;
    }
    return canary(proxy).contains(protocol);
  }

  /** Protocols an operator has opted into beyond the qualified one. Empty unless configured. */
  public static Set<ProtocolVersion> canary(@Nullable VelocityServer proxy) {
    if (proxy == null || proxy.getDiscovery() == null) {
      return Set.of();
    }
    return proxy.getDiscovery().seamlessCanaryProtocols();
  }
}
