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

package com.velocitypowered.proxy.connection.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.network.discovery.DiscoveryService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class SeamlessProtocolsTest {

  /** Every protocol this build is willing to let an operator qualify. */
  static Set<ProtocolVersion> band() {
    return Set.of(ProtocolVersion.MINECRAFT_1_20, ProtocolVersion.MINECRAFT_1_20_2,
        ProtocolVersion.MINECRAFT_1_20_3, ProtocolVersion.MINECRAFT_1_20_5, ProtocolVersion.MINECRAFT_1_21,
        ProtocolVersion.MINECRAFT_1_21_2, ProtocolVersion.MINECRAFT_1_21_4,
        ProtocolVersion.MINECRAFT_1_21_5, ProtocolVersion.MINECRAFT_1_21_6,
        ProtocolVersion.MINECRAFT_1_21_7, ProtocolVersion.MINECRAFT_1_21_9,
        ProtocolVersion.MINECRAFT_1_21_11, ProtocolVersion.MINECRAFT_26_1,
        ProtocolVersion.MINECRAFT_26_2);
  }

  @Test
  void theQualifiedProtocolNeedsNoConfigurationAtAll() {
    // A null proxy stands in for one without discovery: qualified must not depend on it.
    assertTrue(SeamlessProtocols.eligible(null, ProtocolVersion.MINECRAFT_26_2));
  }

  @ParameterizedTest
  @MethodSource("band")
  void nothingBelowTheQualifiedProtocolIsOnWithoutBeingAskedFor(ProtocolVersion protocol) {
    if (protocol == ProtocolVersion.MINECRAFT_26_2) {
      return;
    }
    assertFalse(SeamlessProtocols.eligible(null, protocol),
        protocol + " must stay off until an operator lists it");
    assertTrue(SeamlessProtocols.canaryable(protocol), protocol + " must be listable");
  }

  @ParameterizedTest
  @MethodSource("band")
  void listedProtocolBecomesEligible(ProtocolVersion protocol) {
    VelocityServer proxy = mock(VelocityServer.class);
    DiscoveryService discovery = mock(DiscoveryService.class);
    when(proxy.getDiscovery()).thenReturn(discovery);
    when(discovery.seamlessCanaryProtocols()).thenReturn(Set.of(protocol));
    assertTrue(SeamlessProtocols.eligible(proxy, protocol));
  }

  @ParameterizedTest
  @EnumSource(value = ProtocolVersion.class,
      names = {"UNKNOWN", "LEGACY", "MINECRAFT_1_19_4",
          "MINECRAFT_1_16", "MINECRAFT_1_8"})
  void belowTheFloorNothingCanBeTurnedOn(ProtocolVersion protocol) {
    // These protocols do not have a supported seamless path in this build.
    assertFalse(SeamlessProtocols.canaryable(protocol));
    VelocityServer proxy = mock(VelocityServer.class);
    DiscoveryService discovery = mock(DiscoveryService.class);
    when(proxy.getDiscovery()).thenReturn(discovery);
    when(discovery.seamlessCanaryProtocols()).thenReturn(Set.of(protocol));
    assertFalse(SeamlessProtocols.eligible(proxy, protocol),
        protocol + " must not be reachable even when listed");
  }

  @Test
  void anAbsentProtocolIsNeitherEligibleNorNull() {
    assertFalse(SeamlessProtocols.eligible(null, null));
    assertFalse(SeamlessProtocols.canaryable(null));
    assertEquals(-1, SeamlessProtocols.playUpdateTagsId(null));
  }

  @ParameterizedTest
  @MethodSource("band")
  void everyEligibleProtocolKnowsItsPlayTagsPacket(ProtocolVersion protocol) {
    // Without this id the PLAY-phase invalidation cannot fire, and a stale baseline survives into
    // a switch. Eligibility and the table must therefore never disagree.
    assertTrue(SeamlessProtocols.playUpdateTagsId(protocol) >= 0);
  }

  @Test
  void thePlayTagsIdsSitTwoBelowTheReportDetailsThisProxyRegisters() {
    // Cross-check against the one neighbour Velocity itself maps, per StateRegistry's PLAY
    // registrations: 0x7A at 1.21, 0x81 at 1.21.2, 0x86 at 1.21.9 and 0x88 at 26.1.
    assertEquals(0x7A - 2, SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_1_21));
    assertEquals(0x81 - 2, SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_1_21_2));
    assertEquals(0x86 - 2, SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_1_21_9));
    assertEquals(0x88 - 2, SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_26_1));
    assertEquals(0x88 - 2, SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_26_2));
    // 1.20.5 has no report details to check against; it shares 1.21's id, which is what the
    // vanilla ladder says, since the packets 1.21 added were appended after update_tags.
    assertEquals(SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_1_21),
        SeamlessProtocols.playUpdateTagsId(ProtocolVersion.MINECRAFT_1_20_5));
  }

  @Test
  void theBandIsContiguousUpToWhatThisProxySpeaks() {
    // A newly supported protocol must not become listable before someone records its tags id.
    for (ProtocolVersion protocol : ProtocolVersion.SUPPORTED_VERSIONS) {
      boolean expected = band().contains(protocol);
      assertEquals(expected, SeamlessProtocols.canaryable(protocol),
          protocol + " (" + protocol.getProtocol() + ")");
    }
  }
}
