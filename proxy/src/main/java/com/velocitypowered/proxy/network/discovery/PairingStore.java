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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;

/** Durable reusable challenge enrollment and per-instance credentials for automatically provisioned TLS. */
public final class PairingStore {
  // entityIdBase is allocated once per backend and never reused, so ids minted on one backend can
  // never collide with another's. Zero means an enrollment predating this field.
  private record Entry(String name, String host, int port, String group, String credential,
                       String enrollmentHash, int entityIdBase) {
    Entry(String name, String host, int port, String group, String credential, String enrollmentHash) {
      this(name, host, port, group, credential, enrollmentHash, 0);
    }
  }

  /**
   * Player entity id blocks. Backends allocate players from their own block while vanilla's counter
   * keeps serving world entities from the low ids, so the two can never meet: an entity counter would
   * need nearly two billion allocations to reach the first block.
   */
  static final int PLAYER_ID_BASE = 1_900_000_000;
  static final int PLAYER_ID_SPACING = 10_000_000;

  private record State(String token, Map<UUID, Entry> entries) {}

  record Registration(BackendResume resume, String credential, int entityIdBase) {}

  /** Name of the shared secret administrators copy into each backend's server directory. */
  public static final String CHALLENGE_FILE = "purroxy.challenge";
  private static final Gson GSON = new Gson();
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Path directory;
  private final String certificatePin;
  private State state;

  PairingStore(Path directory, String certificatePin) throws IOException {
    this.directory = directory;
    this.certificatePin = certificatePin;
    Files.createDirectories(directory);
    privateDirectory(directory);
    Path journal = directory.resolve("enrollments.json");
    if (Files.exists(journal)) {
      if (Files.size(journal) > 1024 * 1024) {
        throw new IOException("Oversized pairing registry");
      }
      try {
        state = GSON.fromJson(Files.readString(journal), State.class);
        if (state == null || !validSecret(state.token()) || state.entries() == null || state.entries().size() > 1024) {
          throw new IllegalArgumentException();
        }
        Set<String> names = new java.util.HashSet<>();
        for (var entry : state.entries().entrySet()) {
          Entry value = entry.getValue();
          if (entry.getKey() == null || value == null || !validSecret(value.credential())
              || !value.enrollmentHash().matches("(?:[a-f0-9]{64})?") || !names.add(value.name())) {
            throw new IllegalArgumentException();
          }
          new BackendResume(value.name(), UUID.randomUUID(), value.host(), value.port(), value.group(), "none", 1, 1, "unknown");
        }
        Map<UUID, Entry> backfilled = backfillEntityIdBases(state.entries());
        boolean assigned = backfilled != state.entries();
        state = new State(state.token(), Map.copyOf(backfilled));
        if (assigned) {
          save(state); // Persist the assignment so it is stable across restarts.
        }
      } catch (RuntimeException failure) {
        throw new IOException("Invalid pairing registry; refusing to replace existing credentials", failure);
      }
    } else {
      state = new State(secret(), Map.of());
      save(state);
    }
    publishChallenge();
  }

  synchronized DiscoveryConfiguration.Identity identity(String name) {
    return state.entries().values().stream().filter(entry -> entry.name().equals(name)).findFirst()
        .map(entry -> new DiscoveryConfiguration.Identity(hash(entry.credential()), entry.host(), entry.port(), Set.of(entry.group())))
        .orElse(null);
  }

  synchronized Set<String> names() {
    return state.entries().values().stream().map(Entry::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  synchronized void confirm(String name) throws IOException {
    for (var enrollment : state.entries().entrySet()) {
      Entry entry = enrollment.getValue();
      if (entry.name().equals(name) && !entry.enrollmentHash().isEmpty()) {
        Map<UUID, Entry> entries = new HashMap<>(state.entries());
        entries.put(enrollment.getKey(), new Entry(entry.name(), entry.host(), entry.port(), entry.group(),
            entry.credential(), "", entry.entityIdBase()));
        State next = new State(state.token(), Map.copyOf(entries));
        save(next);
        state = next;
        return;
      }
    }
  }

  synchronized Registration authenticate(JsonObject message, String remoteHost, Set<String> groups,
      Set<String> reservedNames) throws IOException {
    JsonObject auth = message.getAsJsonObject("pairing");
    UUID instance = UUID.fromString(auth.get("instance").getAsString());
    JsonObject raw = message.getAsJsonObject("resume").deepCopy();
    String group = raw.get("group").getAsString().toLowerCase(java.util.Locale.ROOT);
    String host = raw.get("host").getAsString();
    if (host.equals("auto")) {
      raw.addProperty("host", remoteHost);
    }
    Entry existing = state.entries().get(instance);
    if (existing != null) {
      String credential = auth.has("credential") ? auth.get("credential").getAsString() : "";
      String enrollment = auth.has("token") ? auth.get("token").getAsString() : "";
      if (!equal(existing.credential(), credential) && !equal(existing.enrollmentHash(), hash(enrollment))) {
        throw new IllegalArgumentException("Backend pairing credentials were rejected");
      }
      requireGroup(groups, group);
      raw.addProperty("serverId", existing.name());
      BackendResume resume = GSON.fromJson(raw, BackendResume.class);
      if (!existing.host().equals(resume.host()) || existing.port() != resume.port() || !existing.group().equals(resume.group())) {
        throw new IllegalArgumentException("Paired endpoint or group changed; re-enrollment is required");
      }
      return new Registration(resume, existing.credential(), existing.entityIdBase());
    }
    if (state.entries().size() >= 1024 || !auth.has("token") || !equal(state.token(), auth.get("token").getAsString())) {
      throw new IllegalArgumentException("Backend challenge is invalid or revoked");
    }
    requireGroup(groups, group);
    Set<String> occupied = new java.util.HashSet<>(reservedNames);
    occupied.addAll(groups);
    state.entries().values().forEach(entry -> occupied.add(entry.name()));
    String requested = raw.get("serverId").getAsString().toLowerCase(java.util.Locale.ROOT);
    String name = requested;
    if (requested.equals("auto") || requested.endsWith("-*")) {
      String prefix = requested.equals("auto") ? group : requested.substring(0, requested.length() - 2);
      if (!prefix.matches("[a-z0-9][a-z0-9_-]{0,47}")) {
        throw new IllegalArgumentException("Invalid automatic server-name pattern");
      }
      int number = 1;
      do {
        name = prefix + "-" + number++;
      } while (occupied.contains(name));
    }
    if (occupied.contains(name)) {
      throw new IllegalArgumentException("Server name is already assigned");
    }
    raw.addProperty("serverId", name);
    BackendResume resume = GSON.fromJson(raw, BackendResume.class);
    // Allocate a block above every block handed out so far, so two backends can never mint the same id.
    int highest = state.entries().values().stream().mapToInt(Entry::entityIdBase).max().orElse(0);
    int entityIdBase = Math.max(highest, PLAYER_ID_BASE - PLAYER_ID_SPACING) + PLAYER_ID_SPACING;
    if (entityIdBase > Integer.MAX_VALUE - PLAYER_ID_SPACING) {
      throw new IllegalStateException("No player entity id block is left to allocate");
    }
    Entry added = new Entry(name, resume.host(), resume.port(), resume.group(), secret(),
        hash(state.token()), entityIdBase);
    Map<UUID, Entry> entries = new HashMap<>(state.entries());
    entries.put(instance, added);
    State next = new State(state.token(), Map.copyOf(entries)); // The challenge stays valid until it is revoked.
    save(next); // Do not acknowledge credentials until their assignment survives restart.
    state = next;
    return new Registration(resume, added.credential(), added.entityIdBase());
  }

  /**
   * Gives a player entity id block to any backend enrolled before blocks existed. Without this an
   * operator would have to re-pair or hand-configure each one, and the next backend they add would
   * silently share a block with them. Deterministic by name so every proxy start agrees.
   */
  private static Map<UUID, Entry> backfillEntityIdBases(Map<UUID, Entry> entries) {
    if (entries.values().stream().allMatch(entry -> entry.entityIdBase() > 0)) {
      return entries;
    }
    int next = Math.max(entries.values().stream().mapToInt(Entry::entityIdBase).max().orElse(0),
        PLAYER_ID_BASE - PLAYER_ID_SPACING);
    Map<UUID, Entry> updated = new HashMap<>(entries);
    for (UUID instance : entries.entrySet().stream()
        .filter(entry -> entry.getValue().entityIdBase() <= 0)
        .sorted(java.util.Comparator.comparing(entry -> entry.getValue().name()))
        .map(Map.Entry::getKey).toList()) {
      Entry entry = entries.get(instance);
      next += PLAYER_ID_SPACING;
      updated.put(instance, new Entry(entry.name(), entry.host(), entry.port(), entry.group(),
          entry.credential(), entry.enrollmentHash(), next));
    }
    return updated;
  }

  private void publishChallenge() throws IOException {
    write(directory.resolve(CHALLENGE_FILE), "p1." + certificatePin + "." + state.token() + "\n");
    Files.deleteIfExists(directory.resolve("pairing-token.txt")); // Superseded by the reusable challenge.
  }

  /** Invalidates the shared challenge and publishes a replacement. Enrolled backends keep their credentials. */
  public synchronized void rotateChallenge() throws IOException {
    State next = new State(secret(), state.entries());
    save(next);
    state = next;
    publishChallenge();
  }

  /** Removes one backend's credential so it can never resume, reporting false when it was not enrolled. */
  public synchronized boolean revoke(String name) throws IOException {
    UUID instance = state.entries().entrySet().stream()
        .filter(enrollment -> enrollment.getValue().name().equals(name))
        .map(Map.Entry::getKey).findFirst().orElse(null);
    if (instance == null) {
      return false;
    }
    Map<UUID, Entry> entries = new HashMap<>(state.entries());
    entries.remove(instance);
    State next = new State(state.token(), Map.copyOf(entries));
    save(next);
    state = next;
    return true;
  }

  private static void requireGroup(Set<String> groups, String group) {
    if (!group.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Invalid network game mode");
    }
    if (!groups.contains(group)) {
      throw new IllegalArgumentException("UNKNOWN_GROUP: " + group);
    }
  }

  private void save(State next) throws IOException {
    write(directory.resolve("enrollments.json"), GSON.toJson(next));
  }

  static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static boolean equal(String expected, String supplied) {
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean validSecret(String value) {
    return value != null && value.matches("[a-zA-Z0-9_-]{43}");
  }

  private static String secret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static void write(Path target, String text) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
      while (bytes.hasRemaining()) {
        channel.write(bytes);
      }
      channel.force(true);
    }
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void privateDirectory(Path directory) throws IOException {
    if (Files.getFileAttributeView(directory, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
      Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
  }

  record Provisioned(SslContext tls, PairingStore store) {}

  static Provisioned provision(Path directory) throws IOException {
    try {
      Files.createDirectories(directory);
      privateDirectory(directory);
      Path passwordFile = directory.resolve("certificate-password.txt");
      Path keyStoreFile = directory.resolve("proxy.p12");
      if (!Files.exists(passwordFile)) {
        if (Files.exists(keyStoreFile)) {
          throw new IOException("Proxy certificate password is missing; refusing to replace the proxy identity");
        }
        write(passwordFile, secret());
      }
      String password = Files.readString(passwordFile).trim();
      if (!validSecret(password)) {
        throw new IOException("Invalid generated proxy certificate password");
      }
      if (!Files.exists(keyStoreFile)) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        ProcessBuilder builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "purroxy",
            "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Purroxy", "-validity", "3650",
            "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
            "-storepass:env", "PURROXY_GENERATED_STORE_PASSWORD", "-noprompt");
        builder.environment().put("PURROXY_GENERATED_STORE_PASSWORD", password);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("certificate-generation.log").toFile());
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IOException("Proxy certificate generation timed out");
        }
        if (process.exitValue() != 0) {
          throw new IOException("Proxy certificate generation failed; see certificate-generation.log");
        }
      }
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(keyStoreFile)) {
        keyStore.load(input, password.toCharArray());
      }
      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(keyStore, password.toCharArray());
      String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(keyStore.getCertificate("purroxy").getEncoded()));
      SslContext tls = SslContextBuilder.forServer(keys).protocols("TLSv1.3").build();
      return new Provisioned(tls, new PairingStore(directory, pin));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("Proxy certificate generation interrupted", failure);
    } catch (java.security.GeneralSecurityException failure) {
      throw new IOException("Cannot load generated proxy TLS identity", failure);
    }
  }
}
