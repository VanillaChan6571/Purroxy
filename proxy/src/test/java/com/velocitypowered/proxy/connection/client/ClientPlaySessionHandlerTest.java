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

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientPlaySessionHandlerTest {
  @Test
  void skipsTheResetOnlyWhenEveryContinuityConditionMatches() {
    assertTrue(ClientPlaySessionHandler.canKeepClientPlayState(true, 42, 42, true, true, true));
    assertFalse(ClientPlaySessionHandler.canKeepClientPlayState(false, 42, 42, true, true, true));
    assertFalse(ClientPlaySessionHandler.canKeepClientPlayState(true, 43, 42, true, true, true));
    assertFalse(ClientPlaySessionHandler.canKeepClientPlayState(true, 42, 42, false, true, true));
    // Everything else can match and the client still must be reset: keeping it in PLAY keeps an
    // entity table that cannot be cleaned when what the source showed it is not known in full.
    assertFalse(ClientPlaySessionHandler.canKeepClientPlayState(true, 42, 42, true, false, true));
    // Likewise for secure chat: the client keeps a last-seen tracker only JoinGame clears, and a
    // destination starting with an empty validator answers the next message with a kick.
    assertFalse(ClientPlaySessionHandler.canKeepClientPlayState(true, 42, 42, true, true, false));
  }
}
