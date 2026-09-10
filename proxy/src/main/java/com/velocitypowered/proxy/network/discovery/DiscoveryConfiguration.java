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

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Validated discovery trust and routing policy, loaded before opening listeners. */
public record DiscoveryConfiguration(Set<String> groups, Map<String, Identity> identities,
                                     SslContext tls, List<String> fallback,
                                     Map<String, List<String>> forcedHosts, Map<String, String> handoffModes,
                                     @Nullable PairingStore pairing) {

  /** Keeps existing certificate-pinned configurations compatible. */
  public DiscoveryConfiguration(Set<String> groups, Map<String, Identity> identities, SslContext tls,
      List<String> fallback, Map<String, List<String>> forcedHosts, Map<String, String> handoffModes) {
    this(groups, identities, tls, fallback, forcedHosts, handoffModes, null);
  }

  /** Resolves manually provisioned or automatically paired backend identities. */
  public @Nullable Identity identity(String name) {
    Identity configured = identities.get(name);
    return configured != null || pairing == null ? configured : pairing.identity(name);
  }

  /** Existing discovery policies keep handoff disabled unless explicitly configured. */
  public DiscoveryConfiguration(Set<String> groups, Map<String, Identity> identities, SslContext tls,
                                List<String> fallback, Map<String, List<String>> forcedHosts) {
    this(groups, identities, tls, fallback, forcedHosts, Map.of());
  }

  /** Creates discovery policy with groups as the default fallback order. */
  public DiscoveryConfiguration(Set<String> groups, Map<String, Identity> identities, SslContext tls) {
    this(groups, identities, tls, groups.stream().sorted().toList(), Map.of());
  }

  /** Validates and freezes routing policy before any listener uses it. */
  public DiscoveryConfiguration {
    groups = Set.copyOf(groups);
    identities = Map.copyOf(identities);
    fallback = List.copyOf(fallback);
    Map<String, List<String>> hosts = new HashMap<>();
    forcedHosts.forEach((host, routes) -> hosts.put(host.toLowerCase(Locale.ROOT), List.copyOf(routes)));
    forcedHosts = Map.copyOf(hosts);
    handoffModes = Map.copyOf(handoffModes);
    if (!groups.containsAll(handoffModes.keySet()) || handoffModes.values().stream()
        .anyMatch(mode -> !Set.of("off", "normal", "seamless-preferred", "seamless-required").contains(mode))) {
      throw new IllegalArgumentException("Invalid group handoff mode");
    }
    Set<String> destinations = new java.util.HashSet<>(groups);
    destinations.addAll(identities.keySet());
    if (pairing != null) {
      destinations.addAll(pairing.names());
    }
    java.util.stream.Stream.concat(fallback.stream(),
        forcedHosts.values().stream().flatMap(List::stream)).forEach(route -> {
          if (!destinations.contains(route)) {
            throw new IllegalArgumentException("Unknown network routing destination: " + route);
          }
        });
  }

  /** Certificate-pinned authorization for a backend's advertised endpoint and groups. */
  public record Identity(String fingerprint, String host, int port, Set<String> groups) {
    public boolean permits(BackendResume resume, String peerFingerprint) {
      return fingerprint.equalsIgnoreCase(peerFingerprint) && host.equalsIgnoreCase(resume.host())
          && port == resume.port() && groups.contains(resume.group());
    }
  }

  /** Returns empty when discovery is disabled; invalid enabled configurations fail startup. */
  public static java.util.Optional<DiscoveryConfiguration> read(Path path) throws IOException {
    if (!Files.exists(path)) {
      try (var resource = Objects.requireNonNull(DiscoveryConfiguration.class
          .getResourceAsStream("/purroxy-network.toml"))) {
        Files.copy(resource, path);
      }
    }
    try (CommentedFileConfig config = CommentedFileConfig.of(path)) {
      config.load();
      if (!Boolean.TRUE.equals(config.get("discovery.enabled"))) {
        return java.util.Optional.empty();
      }
      if (!Integer.valueOf(1).equals(config.get("format-version"))) {
        throw new IllegalArgumentException("Unsupported purroxy-network.toml format-version");
      }
      UnmodifiableConfig groupConfig = config.get("groups");
      Set<String> groups = groupConfig.valueMap().keySet().stream()
          .map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (groups.isEmpty()) {
        throw new IllegalArgumentException("At least one discovery group is required");
      }
      Map<String, String> handoffModes = new HashMap<>();
      for (var groupEntry : groupConfig.valueMap().entrySet()) {
        UnmodifiableConfig settings = (UnmodifiableConfig) groupEntry.getValue();
        String mode = settings.getOrElse("handoff-mode", "off");
        handoffModes.put(groupEntry.getKey().toLowerCase(Locale.ROOT), mode);
      }
      UnmodifiableConfig identityConfig = config.get("backend-identities");
      Map<String, Identity> identities = new HashMap<>();
      for (var entry : (identityConfig == null ? Map.<String, Object>of() : identityConfig.valueMap()).entrySet()) {
        UnmodifiableConfig identity = (UnmodifiableConfig) entry.getValue();
        String fingerprint = identity.get("certificate-sha256");
        if (fingerprint == null || !fingerprint.matches("[a-fA-F0-9]{64}")) {
          throw new IllegalArgumentException("Backend certificate-sha256 must be 64 hex characters");
        }
        List<String> allowed = identity.get("allowed-game-modes");
        Set<String> allowedGroups = allowed.stream().map(s -> s.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!groups.containsAll(allowedGroups)) {
          throw new IllegalArgumentException("Identity references an unknown group");
        }
        String name = entry.getKey().toLowerCase(Locale.ROOT);
        if (groups.contains(name) || identities.containsKey(name)) {
          throw new IllegalArgumentException("Duplicate server/group identity: " + name);
        }
        String host = Objects.requireNonNull(identity.get("host"), "identity host");
        int port = identity.getInt("port");
        identities.put(name, new Identity(fingerprint, host, port, allowedGroups));
      }
      Path base = path.toAbsolutePath().getParent();
      String authentication = config.getOrElse("discovery.authentication", "certificates");
      PairingStore pairing = null;
      SslContext tls;
      if (authentication.equals("pairing")) {
        if (!identities.isEmpty()) {
          throw new IllegalArgumentException("Manual backend identities require authentication = certificates");
        }
        PairingStore.Provisioned provisioned = PairingStore.provision(base.resolve("purroxy-pairing"));
        pairing = provisioned.store();
        tls = provisioned.tls();
      } else if (authentication.equals("certificates")) {
        tls = SslContextBuilder.forServer(
              base.resolve((String) config.get("discovery.certificate-file")).toFile(),
              base.resolve((String) config.get("discovery.private-key-file")).toFile())
          .trustManager(base.resolve((String) config.get("discovery.backend-ca-file")).toFile())
          .clientAuth(ClientAuth.REQUIRE).protocols("TLSv1.3").build();
      } else {
        throw new IllegalArgumentException("Unknown discovery authentication mode");
      }
      List<String> fallback = config.getOrElse("fallback", groups.stream().sorted().toList());
      fallback = fallback.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
      Map<String, List<String>> forcedHosts = new HashMap<>();
      UnmodifiableConfig forced = config.get("forced-hosts");
      if (forced != null) {
        for (var entry : forced.valueMap().entrySet()) {
          List<?> routes = (List<?>) entry.getValue();
          forcedHosts.put(entry.getKey(), routes.stream()
              .map(value -> ((String) value).toLowerCase(Locale.ROOT)).toList());
        }
      }
      return java.util.Optional.of(new DiscoveryConfiguration(groups, identities, tls, fallback, forcedHosts, handoffModes, pairing));
    } catch (RuntimeException exception) {
      throw new IOException("Invalid discovery configuration: " + exception.getMessage(), exception);
    }
  }
}
