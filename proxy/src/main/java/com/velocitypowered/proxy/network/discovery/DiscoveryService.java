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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Authenticated backend control transport and publication into Velocity's physical registry. */
public final class DiscoveryService implements AutoCloseable {
  private static final Logger logger = LogManager.getLogger(DiscoveryService.class);
  private static final Gson gson = new Gson();
  private final VelocityServer server;
  private final DiscoveryConfiguration configuration;
  private final DiscoveryRegistry registry;
  private final RegionPreferences regionPreferences;
  private final @Nullable HandoffCoordinator handoff;
  private final Map<DiscoveryRegistry.Session, HandoffCapabilities> handoffCapabilities = new HashMap<>();
  private final Map<UUID, HandoffRpc> handoffRequests = new HashMap<>();

  private record HandoffRpc(DiscoveryRegistry.Session session, CompletableFuture<JsonObject> result) {}
  private final Map<DiscoveryRegistry.Session, Channel> channels = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, RegisteredServer> published = new HashMap<>();
  private final Map<String, Long> lastUnknownGroupAlert = new HashMap<>();
  private final Map<UUID, Admission> admissions = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, Long> waking = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, Long> wakeRetryAfter = new HashMap<>();
  private final Map<String, Long> capacityAlerts = new HashMap<>();
  private final Map<String, Long> demand = new HashMap<>();
  private final Map<String, String> demandRegions = new HashMap<>();
  private final Map<UUID, Long> evacuationRetryAfter = new HashMap<>();

  /** A specific connection attempt; completion of older attempts cannot release newer slots. */
  public record Admission(UUID attempt, UUID player, String name,
                          DiscoveryRegistry.@Nullable Reservation slot, long created) {
  }

  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "purroxy-discovery");
    thread.setDaemon(true);
    return thread;
  });

  /** Starts lease maintenance for an enabled, validated configuration. */
  public DiscoveryService(VelocityServer server, DiscoveryConfiguration configuration) {
    this.server = server;
    this.configuration = configuration;
    try {
      regionPreferences = new RegionPreferences(java.nio.file.Path.of("purroxy-regions.properties"));
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException("Unable to load player region preferences", failure);
    }
    registry = new DiscoveryRegistry(configuration.groups(), Duration.ofSeconds(20), System::nanoTime);
    try {
      handoff = configuration.handoffModes().values().stream().anyMatch(mode -> !mode.equals("off"))
          || java.nio.file.Files.isDirectory(java.nio.file.Path.of("purroxy-handoffs"))
          ? new HandoffCoordinator(java.nio.file.Path.of("purroxy-handoffs"), new HandoffCoordinator.Transport() {
            @Override
            public Optional<HandoffCoordinator.Peer> current(String name) {
              return handoffPeer(name);
            }

            @Override
            public CompletableFuture<JsonObject> request(HandoffCoordinator.Peer peer, JsonObject message) {
              return handoffRequest(peer, message);
            }
          }) : null;
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException("Unable to load durable handoff decisions", failure);
    }
    timer.scheduleWithFixedDelay(this::maintenance, 1, 1, TimeUnit.SECONDS);
  }

  private synchronized Optional<HandoffCoordinator.Peer> handoffPeer(String name) {
    return registry.snapshots().stream().filter(snapshot -> snapshot.resume().serverId().equals(name)
        && snapshot.leaseValid() && snapshot.state() != DiscoveryRegistry.State.SUSPECT)
        .filter(snapshot -> handoffCapabilities.containsKey(snapshot.session()) && channels.containsKey(snapshot.session()))
        .findFirst().map(snapshot -> new HandoffCoordinator.Peer(name, snapshot.session().token(), handoffCapabilities.get(snapshot.session())));
  }

  private synchronized CompletableFuture<JsonObject> handoffRequest(HandoffCoordinator.Peer peer, JsonObject message) {
    DiscoveryRegistry.Session session = new DiscoveryRegistry.Session(peer.name(), peer.session());
    Channel channel = channels.get(session);
    if (channel == null || !channel.isActive() || !handoffPeer(peer.name()).filter(peer::equals).isPresent()) {
      return CompletableFuture.failedFuture(new IllegalStateException("Handoff backend session is no longer active"));
    }
    if (handoffRequests.size() >= 256) {
      return CompletableFuture.failedFuture(new IllegalStateException("Too many handoff control requests"));
    }
    UUID request = UUID.randomUUID();
    CompletableFuture<JsonObject> response = new CompletableFuture<>();
    handoffRequests.put(request, new HandoffRpc(session, response));
    JsonObject framed = message.deepCopy();
    framed.addProperty("type", "handoff");
    framed.addProperty("session", peer.session().toString());
    framed.addProperty("request", request.toString());
    channel.writeAndFlush(gson.toJson(framed) + "\n").addListener(result -> {
      if (!result.isSuccess()) response.completeExceptionally(result.cause());
    });
    return response.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> {
      synchronized (DiscoveryService.this) { handoffRequests.remove(request); }
    });
  }

  /** Prepares a coordinated transfer after connection events have selected the final destination. */
  public CompletableFuture<@Nullable HandoffCoordinator.Ticket> prepareHandoff(Player player, @Nullable String source,
      String destination, java.util.function.BooleanSupplier valid, java.util.concurrent.Executor playerLoop) {
    if (handoff == null) return CompletableFuture.completedFuture(null);
    String group;
    synchronized (this) {
      group = registry.snapshots().stream().filter(snapshot -> snapshot.resume().serverId().equals(destination))
          .map(snapshot -> snapshot.resume().group()).findFirst().orElse("");
    }
    return handoff.prepare(player.getUniqueId(), source, destination,
        configuration.handoffModes().getOrDefault(group, "off"), valid, playerLoop);
  }

  /** Finalizes source release only after Velocity reports a successful destination connection. */
  public void finishHandoff(@Nullable HandoffCoordinator.Ticket ticket, boolean connected) {
    if (handoff != null && ticket != null) {
      handoff.finish(ticket, connected).exceptionally(failure -> {
        logger.error("Handoff {} remains recoverable; source release failed", ticket.transfer(), failure);
        return null;
      });
    }
  }

  public boolean hasCommittedHandoff(UUID player) {
    return handoff != null && handoff.recoveryOwner(player).isPresent();
  }

  /** Installs the TLS control path after the unencrypted protocol discriminator. */
  public void initialize(Channel channel) {
    SslHandler ssl = configuration.tls().newHandler(channel.alloc());
    ssl.setHandshakeTimeoutMillis(5000);
    channel.pipeline().addLast("discovery-tls", ssl)
        .addLast("discovery-lines", new LineBasedFrameDecoder(65536, true, true))
        .addLast("discovery-text", new StringDecoder(StandardCharsets.UTF_8))
        .addLast("discovery-output", new StringEncoder(StandardCharsets.UTF_8))
        .addLast("discovery-control", new ControlHandler());
  }

  /** Indicates whether a physical destination can currently receive a new player. */
  public synchronized boolean allows(String name) {
    return registry.snapshots().stream()
        .filter(s -> s.resume().serverId().equalsIgnoreCase(name))
        .allMatch(s -> s.leaseValid() && s.state() == DiscoveryRegistry.State.READY
            && (long) s.players() + s.reservations() < s.resume().hardLimit());
  }

  public Set<String> groups() {
    return configuration.groups();
  }

  public synchronized Set<String> regions() {
    return registry.snapshots().stream().map(s -> s.resume().region())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public String preferredRegion(UUID player) {
    return regionPreferences.get(player);
  }

  public CompletableFuture<Void> setPreferredRegion(UUID player, String region) {
    return regionPreferences.set(player, region);
  }

  private void observeConnections() {
    published.forEach((session, physical) -> registry.observeConnections(session,
        physical.getPlayersConnected().size()));
  }

  /** Resolves a fresh login/kick fallback, holding its slot until the connection starts. */
  public synchronized Optional<RegisteredServer> nextFallback(Player player, String host,
                                                               Set<String> excluded) {
    if (admissions.containsKey(player.getUniqueId())) {
      return Optional.empty();
    }
    observeConnections();
    if (handoff != null && handoff.recoveryOwner(player.getUniqueId()).isPresent()) {
      String owner = handoff.recoveryOwner(player.getUniqueId()).orElseThrow();
      if (excluded.contains(owner)) return Optional.empty();
      RegisteredServer target = server.getServer(owner).orElse(null);
      if (target == null) return Optional.empty();
      Optional<Admission> admission = begin(player.getUniqueId(), target);
      return admission.isPresent() ? Optional.of(target) : Optional.empty();
    }
    var destinations = configuration.forcedHosts().getOrDefault(
        host.toLowerCase(java.util.Locale.ROOT), configuration.fallback());
    for (String destination : destinations) {
      if (configuration.groups().contains(destination)) {
        demand.put(destination, System.nanoTime());
        demandRegions.put(destination, preferredRegion(player.getUniqueId()));
      }
      Optional<DiscoveryRegistry.Reservation> reserved = configuration.groups().contains(destination)
          ? registry.reserve(destination, preferredRegion(player.getUniqueId()), excluded)
          : excluded.contains(destination) ? Optional.empty() : registry.reservePhysical(destination);
      if (reserved.isEmpty()) {
        continue;
      }
      var slot = reserved.get();
      RegisteredServer physical = published.get(slot.session());
      if (physical == null) {
        registry.release(slot);
        continue;
      }
      admissions.put(player.getUniqueId(), new Admission(UUID.randomUUID(), player.getUniqueId(),
          physical.getServerInfo().getName(), slot, System.nanoTime()));
      return Optional.of(physical);
    }
    return Optional.empty();
  }

  /** Releases an abandoned planned route when its player disconnects. */
  public synchronized void cancel(UUID player) {
    Admission admission = admissions.get(player);
    if (admission != null) {
      finish(admission);
    }
  }

  /** Begins a physical connection, atomically acquiring or consuming its planned group slot. */
  public synchronized Optional<Admission> begin(UUID player, RegisteredServer destination) {
    String name = destination.getServerInfo().getName();
    if (configuration.identities().containsKey(name.toLowerCase(java.util.Locale.ROOT))
        && published.values().stream().noneMatch(value -> value == destination)) {
      return Optional.empty();
    }
    observeConnections();
    Admission planned = admissions.get(player);
    if (planned != null) {
      if (planned.name().equalsIgnoreCase(name) && planned.slot() != null
          && registry.canCommit(planned.slot())) {
        return Optional.of(planned);
      }
      finish(planned); // A plugin redirected the planned connection.
    }
    boolean managed = configuration.identities().containsKey(name.toLowerCase(java.util.Locale.ROOT));
    DiscoveryRegistry.Reservation slot = null;
    if (managed) {
      slot = registry.reservePhysical(name).orElse(null);
      if (slot == null) {
        return Optional.empty();
      }
    }
    Admission admission = new Admission(UUID.randomUUID(), player, name, slot, System.nanoTime());
    admissions.put(player, admission);
    return Optional.of(admission);
  }

  /** Releases exactly this attempt after the backend connection future completes. */
  public synchronized void finish(Admission admission) {
    observeConnections();
    if (admission.slot() != null) {
      registry.release(admission.slot());
    }
    admissions.remove(admission.player(), admission);
  }

  /** Rechecks the negotiated slot before disconnecting the source backend. */
  public synchronized boolean canComplete(UUID player, String destination) {
    Admission admission = admissions.get(player);
    if (admission == null || !admission.name().equalsIgnoreCase(destination)) {
      return false;
    }
    return System.nanoTime() - admission.created() < TimeUnit.SECONDS.toNanos(30)
        && (admission.slot() == null || registry.canCommit(admission.slot()));
  }

  /** Routes a group request through the normal connection API and its plugin events. */
  public CompletableFuture<Boolean> connectGroup(Player player, String group, String region) {
    final Admission planned;
    final RegisteredServer target;
    synchronized (this) {
      if (admissions.containsKey(player.getUniqueId())) {
        return CompletableFuture.completedFuture(false);
      }
      observeConnections();
      String current = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
      boolean alreadyInGroup = registry.snapshots().stream().anyMatch(s -> s.leaseValid()
          && s.state() == DiscoveryRegistry.State.READY && s.resume().group().equalsIgnoreCase(group)
          && s.resume().serverId().equalsIgnoreCase(current));
      if (alreadyInGroup) {
        player.sendMessage(net.kyori.adventure.text.Component.text("You are already connected to " + group + "."));
        return CompletableFuture.completedFuture(true);
      }
      demand.put(group.toLowerCase(java.util.Locale.ROOT), System.nanoTime());
      demandRegions.put(group.toLowerCase(java.util.Locale.ROOT), region.toLowerCase(java.util.Locale.ROOT));
      Optional<DiscoveryRegistry.Reservation> reserved = registry.reserve(group, region);
      if (reserved.isEmpty()) {
        return CompletableFuture.completedFuture(false);
      }
      DiscoveryRegistry.Reservation slot = reserved.get();
      target = published.get(slot.session());
      if (target == null) {
        registry.release(slot);
        return CompletableFuture.completedFuture(false);
      }
      planned = new Admission(UUID.randomUUID(), player.getUniqueId(),
          target.getServerInfo().getName(), slot, System.nanoTime());
      admissions.put(player.getUniqueId(), planned);
    }
    try {
      if (!region.isEmpty()) {
        String destinationRegion = registry.snapshots().stream()
            .filter(s -> s.session().equals(planned.slot().session()))
            .map(s -> s.resume().region()).findFirst().orElse("");
        if (!destinationRegion.equalsIgnoreCase(region)) {
          player.sendMessage(net.kyori.adventure.text.Component.text(
              "Your preferred region is unavailable for this connection; using " + destinationRegion + ".",
              net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        }
      }
      return player.createConnectionRequest(target).connect()
          .handle((result, failure) -> failure == null && (result.isSuccessful()
              || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED))
          .whenComplete((result, failure) -> finish(planned));
    } catch (RuntimeException failure) {
      finish(planned);
      return CompletableFuture.failedFuture(failure);
    }
  }

  synchronized DiscoveryRegistry.Session register(BackendResume resume,
                                                            String fingerprint, Channel channel) {
    DiscoveryConfiguration.Identity identity = configuration.identities().get(resume.serverId());
    if (identity == null || !identity.fingerprint().equalsIgnoreCase(fingerprint)
        || !identity.host().equalsIgnoreCase(resume.host()) || identity.port() != resume.port()) {
      throw new IllegalArgumentException("Backend identity, endpoint or group is not authorized");
    }
    if (!configuration.groups().contains(resume.group())) {
      long now = System.nanoTime();
      Long last = lastUnknownGroupAlert.get(resume.serverId());
      if (last == null || now - last >= TimeUnit.MINUTES.toNanos(1)) {
        lastUnknownGroupAlert.put(resume.serverId(), now);
        String warning = "Backend " + resume.serverId() + " attempted to join unknown group "
            + resume.group() + ". Configure the group or correct its Nekopurr resume.";
        logger.warn(warning);
        net.kyori.adventure.text.Component notice = net.kyori.adventure.text.Component.text(warning,
            net.kyori.adventure.text.format.NamedTextColor.YELLOW);
        server.getAllPlayers().stream().filter(p -> p.hasPermission("purroxy.notifications.discovery"))
            .forEach(p -> p.sendMessage(notice));
      }
      throw new IllegalArgumentException("UNKNOWN_GROUP: " + resume.group());
    }
    if (!identity.permits(resume, fingerprint)) {
      throw new IllegalArgumentException("Network game mode is not authorized for this backend");
    }
    DiscoveryRegistry.Snapshot previous = registry.snapshots().stream()
        .filter(s -> s.resume().serverId().equals(resume.serverId())).findFirst().orElse(null);
    RegisteredServer retained = previous == null ? null : published.get(previous.session());
    if (server.getServer(resume.serverId()).isPresent()
        && server.getServer(resume.serverId()).orElseThrow() != retained) {
      throw new IllegalArgumentException("Physical server name is already registered");
    }
    DiscoveryRegistry.Session session = registry.register(resume);
    try {
      RegisteredServer physical = retained == null
          ? server.registerServer(new ServerInfo(resume.serverId(),
              InetSocketAddress.createUnresolved(resume.host(), resume.port()))) : retained;
      if (previous != null) {
        published.remove(previous.session());
        Channel old = channels.remove(previous.session());
        if (old != null) {
          old.close();
        }
      }
      published.put(session, physical);
      channels.put(session, channel);
      logger.info("Backend {} registered for {} in {}", resume.serverId(), resume.group(), resume.region());
      return session;
    } catch (RuntimeException failure) {
      registry.disconnected(session);
      registry.retire(session, true);
      throw failure;
    }
  }

  synchronized void heartbeat(DiscoveryRegistry.Session session, JsonObject message) {
    long sequence = message.get("sequence").getAsLong();
    int players = message.get("players").getAsInt();
    DiscoveryRegistry.State state = DiscoveryRegistry.State.valueOf(message.get("state").getAsString());
    if (state == DiscoveryRegistry.State.SUSPECT) {
      throw new IllegalArgumentException("SUSPECT is a proxy-owned state");
    }
    Set<UUID> admitted = new java.util.HashSet<>();
    if (message.has("admitted")) {
      message.getAsJsonArray("admitted").forEach(value -> admitted.add(UUID.fromString(value.getAsString())));
    }
    if (!registry.heartbeat(session, sequence, state, players, admitted)) {
      throw new IllegalStateException("Expired or stale backend session");
    }
  }

  private synchronized void disconnect(DiscoveryRegistry.Session session) {
    registry.disconnected(session);
    channels.remove(session);
    handoffCapabilities.remove(session);
    java.util.List.copyOf(handoffRequests.values()).stream().filter(request -> request.session().equals(session))
        .forEach(request -> request.result().completeExceptionally(new IllegalStateException("Handoff control disconnected")));
  }

  synchronized void maintenance() {
    try {
      observeConnections();
      wakeSpares();
      for (Admission admission : java.util.List.copyOf(admissions.values())) {
        if (System.nanoTime() - admission.created() >= TimeUnit.SECONDS.toNanos(30)) {
          finish(admission);
        }
      }
      for (DiscoveryRegistry.Snapshot snapshot : registry.snapshots()) {
        if (snapshot.leaseValid() && snapshot.state() != DiscoveryRegistry.State.SUSPECT
            && snapshot.state() != DiscoveryRegistry.State.DRAINING) {
          continue;
        }
        RegisteredServer physical = published.get(snapshot.session());
        if (physical != null && snapshot.state() == DiscoveryRegistry.State.DRAINING) {
          for (Player player : physical.getPlayersConnected()) {
            long now = System.nanoTime();
            if (now < evacuationRetryAfter.getOrDefault(player.getUniqueId(), 0L)) {
              continue;
            }
            evacuationRetryAfter.put(player.getUniqueId(), now + TimeUnit.SECONDS.toNanos(5));
            nextFallback(player, player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(""),
                Set.of(snapshot.resume().serverId())).ifPresent(destination -> {
                  Admission planned = admissions.get(player.getUniqueId());
                  player.createConnectionRequest(destination).connect().whenComplete((result, failure) -> {
                    if (planned != null) {
                      finish(planned);
                    }
                  });
                });
          }
        }
        if (physical != null && registry.retire(snapshot.session(), physical.getPlayersConnected().isEmpty())) {
          if (server.getServer(physical.getServerInfo().getName()).orElse(null) == physical) {
            server.unregisterServer(physical.getServerInfo());
          }
          published.remove(snapshot.session());
          Channel channel = channels.remove(snapshot.session());
          if (channel != null) {
            JsonObject drained = new JsonObject();
            drained.addProperty("type", "retired");
            drained.addProperty("session", snapshot.session().token().toString());
            channel.writeAndFlush(gson.toJson(drained) + "\n")
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
          }
          waking.remove(snapshot.session());
          wakeRetryAfter.remove(snapshot.session());
          logger.info("Backend {} retired", snapshot.resume().serverId());
        }
      }
    } catch (RuntimeException failure) {
      logger.error("Discovery maintenance failed", failure);
    }
  }

  private void wakeSpares() {
    long now = System.nanoTime();
    var snapshots = registry.snapshots();
    for (var snapshot : snapshots) {
      Long requested = waking.get(snapshot.session());
      if (requested != null && snapshot.state() == DiscoveryRegistry.State.READY && snapshot.leaseValid()) {
        waking.remove(snapshot.session());
      } else if (requested != null && (!snapshot.leaseValid()
          || now - requested >= TimeUnit.SECONDS.toNanos(60))) {
        waking.remove(snapshot.session());
        wakeRetryAfter.put(snapshot.session(), now + TimeUnit.SECONDS.toNanos(30));
        capacityAlert(snapshot.resume().group(), "A spare failed to become ready.");
      }
    }
    demand.entrySet().removeIf(entry -> now - entry.getValue() > TimeUnit.SECONDS.toNanos(30));
    evacuationRetryAfter.entrySet().removeIf(entry -> now - entry.getValue() > TimeUnit.SECONDS.toNanos(30));
    for (String group : configuration.groups()) {
      var members = snapshots.stream().filter(s -> s.resume().group().equals(group)).toList();
      boolean pending = members.stream().anyMatch(s -> waking.containsKey(s.session()));
      var candidates = members.stream().map(s -> new CapacityPolicy.Candidate(s.resume(),
          Math.max(s.players(), published.get(s.session()).getPlayersConnected().size()), s.reservations(),
          s.leaseValid() && s.state() == DiscoveryRegistry.State.READY)).toList();
      boolean emptyDemand = demand.containsKey(group) && candidates.stream().noneMatch(CapacityPolicy.Candidate::ready);
      boolean highLoad = CapacityPolicy.shouldWake(candidates, false);
      if (!highLoad && !emptyDemand && candidates.stream().anyMatch(CapacityPolicy.Candidate::ready)
          && capacityAlerts.remove(group) != null) {
        String recovery = "Group " + group + " has sufficient ready capacity again.";
        logger.info(recovery);
        var notice = net.kyori.adventure.text.Component.text(recovery,
            net.kyori.adventure.text.format.NamedTextColor.GREEN);
        server.getAllPlayers().stream().filter(p -> p.hasPermission("purroxy.notifications.capacity"))
            .forEach(p -> p.sendMessage(notice));
      }
      if (pending || (!highLoad && !emptyDemand)) {
        continue;
      }
      String desiredRegion = demand.containsKey(group) ? demandRegions.getOrDefault(group, "")
          : candidates.stream().filter(CapacityPolicy.Candidate::ready)
              .max(java.util.Comparator.comparingDouble(c -> (double) c.load() / c.resume().safeLimit()))
              .map(c -> c.resume().region()).orElse("");
      var spare = members.stream().filter(s -> s.leaseValid() && s.state() == DiscoveryRegistry.State.SLEEPING)
          .filter(s -> now >= wakeRetryAfter.getOrDefault(s.session(), Long.MIN_VALUE))
          .sorted(java.util.Comparator.comparing((DiscoveryRegistry.Snapshot s) ->
              !desiredRegion.isEmpty() && !s.resume().region().equals(desiredRegion))
              .thenComparing(s -> s.resume().serverId())).findFirst();
      if (spare.isEmpty()) {
        capacityAlert(group, "No sleeping spare is available; connections will use overflow capacity.");
        continue;
      }
      var session = spare.get().session();
      Channel channel = channels.get(session);
      if (channel == null || !channel.isActive()) {
        continue;
      }
      waking.put(session, now);
      JsonObject wake = new JsonObject();
      wake.addProperty("type", "wake");
      wake.addProperty("session", session.token().toString());
      wake.addProperty("request", UUID.randomUUID().toString());
      channel.writeAndFlush(gson.toJson(wake) + "\n").addListener(result -> {
        if (!result.isSuccess()) {
          synchronized (DiscoveryService.this) {
            waking.remove(session);
            wakeRetryAfter.put(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            capacityAlert(group, "A wake request could not be delivered.");
          }
        }
      });
      logger.info("Requested wake for backend {} in group {}", session.serverId(), group);
    }
  }

  private void capacityAlert(String group, String reason) {
    long now = System.nanoTime();
    Long last = capacityAlerts.get(group);
    if (last != null && now - last < TimeUnit.MINUTES.toNanos(1)) {
      return;
    }
    capacityAlerts.put(group, now);
    String counts = registry.snapshots().stream().filter(s -> s.resume().group().equals(group))
        .map(s -> s.resume().serverId() + "=" + s.players() + " players, " + s.resume().safeLimit()
            + " safe, " + s.resume().hardLimit() + " hard")
        .collect(java.util.stream.Collectors.joining("; "));
    String text = "Group " + group + ": " + reason + " " + counts
        + ". Start another server in Pterodactyl if demand continues.";
    logger.warn(text);
    var notice = net.kyori.adventure.text.Component.text(text, net.kyori.adventure.text.format.NamedTextColor.YELLOW);
    server.getAllPlayers().stream().filter(p -> p.hasPermission("purroxy.notifications.capacity"))
        .forEach(p -> p.sendMessage(notice));
  }

  @Override
  public synchronized void close() {
    timer.shutdownNow();
    regionPreferences.close();
    if (handoff != null) handoff.close();
    channels.values().forEach(Channel::close);
    channels.clear();
    for (Admission admission : java.util.List.copyOf(admissions.values())) {
      finish(admission);
    }
  }

  private final class ControlHandler extends SimpleChannelInboundHandler<String> {
    private DiscoveryRegistry.Session session;
    private boolean reachable;
    private boolean probing;
    private int messages;
    private long window = System.nanoTime();

    @Override
    protected void channelRead0(ChannelHandlerContext context, String text) throws Exception {
      long now = System.nanoTime();
      if (now - window >= TimeUnit.SECONDS.toNanos(1)) {
        window = now;
        messages = 0;
      }
      if (++messages > 20) {
        throw new IllegalArgumentException("Control message rate exceeded");
      }
      JsonObject message = JsonParser.parseString(text).getAsJsonObject();
      String type = message.get("type").getAsString();
      if (session == null) {
        if (!type.equals("resume") || message.get("version").getAsInt() != 1) {
          throw new IllegalArgumentException("Expected resume protocol version 1");
        }
        SslHandler ssl = context.pipeline().get(SslHandler.class);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(ssl.engine().getSession().getPeerCertificates()[0].getEncoded()));
        session = register(gson.fromJson(message.get("resume"), BackendResume.class),
            fingerprint, context.channel());
        if (message.has("handoff")) {
          HandoffCapabilities capabilities = gson.fromJson(message.get("handoff"), HandoffCapabilities.class);
          synchronized (DiscoveryService.this) { handoffCapabilities.put(session, capabilities); }
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "registered");
        response.addProperty("session", session.token().toString());
        response.addProperty("heartbeatSeconds", 5);
        response.addProperty("leaseSeconds", 20);
        context.writeAndFlush(gson.toJson(response) + "\n");
      } else if (type.equals("heartbeat")) {
        if (!session.token().toString().equals(message.get("session").getAsString())) {
          throw new IllegalArgumentException("Session mismatch");
        }
        if (message.get("state").getAsString().equals("READY") && !reachable) {
          message.addProperty("state", "REGISTERING");
          if (!probing) {
            probing = true;
            RegisteredServer physical;
            synchronized (DiscoveryService.this) {
              physical = published.get(session);
            }
            if (physical == null) {
              throw new IllegalStateException("Backend registration was retired");
            }
            physical.ping().orTimeout(5, TimeUnit.SECONDS).whenComplete((ping, failure) ->
                context.executor().execute(() -> {
                  probing = false;
                  reachable = failure == null;
                }));
          }
        } else if (message.get("state").getAsString().equals("SLEEPING")) {
          reachable = false;
        }
        heartbeat(session, message);
      } else if (type.equals("handoff-result")) {
        if (!session.token().toString().equals(message.get("session").getAsString())) {
          throw new IllegalArgumentException("Handoff session mismatch");
        }
        UUID requestId = UUID.fromString(message.get("request").getAsString());
        HandoffRpc request;
        synchronized (DiscoveryService.this) { request = handoffRequests.get(requestId); }
        if (request != null && request.session().equals(session)) request.result().complete(message.deepCopy());
      } else {
        throw new IllegalArgumentException("Unknown control message");
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
      if (session != null) {
        disconnect(session);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
      JsonObject error = new JsonObject();
      error.addProperty("type", "error");
      error.addProperty("code", failure.getMessage() != null
          && failure.getMessage().startsWith("UNKNOWN_GROUP:") ? "UNKNOWN_GROUP" : "REJECTED");
      error.addProperty("message", "Resume or control message rejected");
      context.writeAndFlush(gson.toJson(error) + "\n")
          .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }
  }
}
