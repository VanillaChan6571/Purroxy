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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PairingStoreTest {
  @TempDir Path directory;
  private static final String PIN = "a".repeat(64);

  @Test
  void assignsNumbersAndKeepsIdentityAcrossRestart() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    UUID first = UUID.randomUUID();
    String originalChallenge = challenge();
    var registration = store.authenticate(resume(first, "hub-*", originalChallenge, null), "10.0.0.5", Set.of("hub"), Set.of());
    assertEquals("hub-1", registration.resume().serverId());
    assertEquals("10.0.0.5", registration.resume().host());
    var second = store.authenticate(resume(UUID.randomUUID(), "auto", challenge(), null), "10.0.0.6", Set.of("hub"), Set.of());
    assertEquals("hub-2", second.resume().serverId());
    PairingStore restarted = new PairingStore(directory, PIN);
    var recovered = restarted.authenticate(resume(first, "hub-*", null, registration.credential()), "10.0.0.5", Set.of("hub"), Set.of());
    assertEquals("hub-1", recovered.resume().serverId());
    assertEquals(registration.credential(), recovered.credential());
  }

  @Test
  void oneChallengeEnrollsAnEntireFleetWithDistinctCredentials() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    String shared = challenge();
    var names = new java.util.HashSet<String>();
    var credentials = new java.util.HashSet<String>();
    for (int backend = 0; backend < 5; backend++) {
      var registration = store.authenticate(resume(UUID.randomUUID(), "auto", shared, null),
          "10.0.0." + backend, Set.of("hub"), Set.of());
      names.add(registration.resume().serverId());
      credentials.add(registration.credential());
    }
    assertEquals(Set.of("hub-1", "hub-2", "hub-3", "hub-4", "hub-5"), names);
    assertEquals(5, credentials.size());
    assertEquals(shared, challenge()); // Deploying the fleet never required a second visit to the proxy.
  }

  @Test
  void rotatingStopsNewEnrollmentsWithoutDisturbingEnrolledBackends() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    String leaked = challenge();
    UUID enrolled = UUID.randomUUID();
    var registration = store.authenticate(resume(enrolled, "auto", leaked, null), "10.0.0.5", Set.of("hub"), Set.of());
    store.confirm(registration.resume().serverId());
    store.rotateChallenge();
    assertNotEquals(leaked, challenge());
    assertThrows(IllegalArgumentException.class,
        () -> store.authenticate(resume(UUID.randomUUID(), "auto", leaked, null), "10.0.0.9", Set.of("hub"), Set.of()));
    assertEquals(registration.credential(), store.authenticate(resume(enrolled, "auto", null, registration.credential()),
        "10.0.0.5", Set.of("hub"), Set.of()).credential());
    assertEquals("hub-2", store.authenticate(resume(UUID.randomUUID(), "auto", challenge(), null),
        "10.0.0.9", Set.of("hub"), Set.of()).resume().serverId());
  }

  @Test
  void revokingOneBackendLeavesTheRestEnrolled() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    UUID compromised = UUID.randomUUID();
    UUID untouched = UUID.randomUUID();
    var first = store.authenticate(resume(compromised, "auto", challenge(), null), "10.0.0.5", Set.of("hub"), Set.of());
    var survivor = store.authenticate(resume(untouched, "auto", challenge(), null), "10.0.0.6", Set.of("hub"), Set.of());
    store.confirm(first.resume().serverId());
    store.confirm(survivor.resume().serverId());
    assertTrue(store.revoke("hub-1"));
    assertFalse(store.revoke("hub-1"));
    assertEquals(Set.of("hub-2"), store.names());
    assertThrows(IllegalArgumentException.class,
        () -> store.authenticate(resume(compromised, "auto", null, first.credential()), "10.0.0.5", Set.of("hub"), Set.of()));
    assertEquals(survivor.credential(), store.authenticate(resume(untouched, "auto", null, survivor.credential()),
        "10.0.0.6", Set.of("hub"), Set.of()).credential());
    var reenrolled = store.authenticate(resume(compromised, "auto", challenge(), null), "10.0.0.5", Set.of("hub"), Set.of());
    assertNotEquals(first.credential(), reenrolled.credential());
  }

  @Test
  void lostEnrollmentReplyCanRetryUntilBackendConfirmsStoredCredentials() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    UUID instance = UUID.randomUUID();
    JsonObject request = resume(instance, "auto", challenge(), null);
    var first = store.authenticate(request, "10.0.0.5", Set.of("hub"), Set.of());
    assertEquals(first, store.authenticate(request, "10.0.0.5", Set.of("hub"), Set.of()));
    store.confirm(first.resume().serverId());
    assertThrows(IllegalArgumentException.class,
        () -> store.authenticate(request, "10.0.0.5", Set.of("hub"), Set.of()));
    assertEquals(first.credential(), store.authenticate(resume(instance, "auto", null, first.credential()),
        "10.0.0.5", Set.of("hub"), Set.of()).credential());
  }

  @Test
  void rejectsInvalidChallengeAndEndpointChanges() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    assertThrows(IllegalArgumentException.class,
        () -> store.authenticate(resume(UUID.randomUUID(), "auto", "invalid", null), "10.0.0.5", Set.of("hub"), Set.of()));
    UUID instance = UUID.randomUUID();
    var first = store.authenticate(resume(instance, "hub-*", challenge(), null), "10.0.0.5", Set.of("hub"), Set.of("hub-1"));
    assertEquals("hub-2", first.resume().serverId());
    assertThrows(IllegalArgumentException.class,
        () -> store.authenticate(resume(instance, "auto", null, first.credential()), "10.0.0.6", Set.of("hub"), Set.of()));
  }

  @Test
  void unknownGroupLeavesTheChallengeUsable() throws Exception {
    PairingStore store = new PairingStore(directory, PIN);
    String before = challenge();
    assertThrows(IllegalArgumentException.class,
        () -> store.authenticate(resume(UUID.randomUUID(), "auto", before, null), "10.0.0.5", Set.of("pvp"), Set.of()));
    assertEquals(before, challenge());
    assertEquals("hub-1", store.authenticate(resume(UUID.randomUUID(), "auto", before, null),
        "10.0.0.5", Set.of("hub"), Set.of()).resume().serverId());
  }

  @Test
  void provisionsAndReusesProxyCertificate() throws Exception {
    var first = PairingStore.provision(directory);
    String published = Files.readString(directory.resolve(PairingStore.CHALLENGE_FILE));
    byte[] certificate = Files.readAllBytes(directory.resolve("proxy.p12"));
    var second = PairingStore.provision(directory);
    assertTrue(first.tls().isServer() && second.tls().isServer());
    assertEquals(published, Files.readString(directory.resolve(PairingStore.CHALLENGE_FILE)));
    org.junit.jupiter.api.Assertions.assertArrayEquals(certificate, Files.readAllBytes(directory.resolve("proxy.p12")));
  }

  @Test
  void replacesSupersededOneTimeTokenFile() throws Exception {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("pairing-token.txt"), "p1." + PIN + "." + "z".repeat(43));
    new PairingStore(directory, PIN);
    assertFalse(Files.exists(directory.resolve("pairing-token.txt")));
    assertTrue(Files.readString(directory.resolve(PairingStore.CHALLENGE_FILE)).startsWith("p1." + PIN + "."));
  }

  private String challenge() throws Exception {
    String published = Files.readString(directory.resolve(PairingStore.CHALLENGE_FILE)).trim();
    return published.substring(published.length() - 43);
  }

  private static JsonObject resume(UUID instance, String name, String token, String credential) {
    JsonObject auth = new JsonObject();
    auth.addProperty("instance", instance.toString());
    if (token != null) {
      auth.addProperty("token", token);
    }
    if (credential != null) {
      auth.addProperty("credential", credential);
    }
    JsonObject resume = new JsonObject();
    resume.addProperty("serverId", name);
    resume.addProperty("incarnation", instance.toString());
    resume.addProperty("host", "auto");
    resume.addProperty("port", 25565);
    resume.addProperty("group", "hub");
    resume.addProperty("map", "none");
    resume.addProperty("safeLimit", 50);
    resume.addProperty("hardLimit", 73);
    resume.addProperty("region", "us-west");
    JsonObject message = new JsonObject();
    message.add("pairing", auth);
    message.add("resume", resume);
    return message;
  }
}
