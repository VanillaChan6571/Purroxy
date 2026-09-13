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
  private final Map<DiscoveryRegistry.Session, Long> nextHandoffSend = new HashMap<>();
  private long nextReleaseRetry;
  private final Map<DiscoveryRegistry.Session, Long> idleSince = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, SleepRequest> sleepRequests = new HashMap<>();
  private final Set<DiscoveryRegistry.Session> managedSleep = new java.util.HashSet<>();

  private record SleepRequest(UUID request, long started) {}

  private record HandoffRpc(DiscoveryRegistry.Session session, CompletableFuture<JsonObject> result) {}

  private final Map<DiscoveryRegistry.Session, Channel> channels = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, RegisteredServer> published = new HashMap<>();
  private final Map<String, Long> lastUnknownGroupAlert = new HashMap<>();
  private final Map<UUID, Admission> admissions = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, Long> waking = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, Long> wakeRetryAfter = new HashMap<>();
  private final Map<DiscoveryRegistry.Session, Long> notReadySince = new HashMap<>();
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
    if (configuration.pairing() != null) {
      logger.info("Backend pairing is enabled. Copy purroxy-pairing/{} into each Nekopur server directory; "
          + "it stays valid until /purroxy pairing rotate revokes it.", PairingStore.CHALLENGE_FILE);
    }
  }

  /** Point-in-time view of one backend, for administrative display only. */
  public record BackendStatus(String name, String group, String region, String host, int port,
                              String state, boolean leaseValid, int players, int reservations,
                              int safeLimit, int hardLimit, long wakeAgeSeconds, boolean managedSleep,
                              boolean sleepPending) {
  }

  /** Snapshots every known backend so administrators can see why one is not accepting players. */
  public synchronized java.util.List<BackendStatus> status() {
    long now = System.nanoTime();
    return registry.snapshots().stream()
        .map(snapshot -> new BackendStatus(snapshot.resume().serverId(), snapshot.resume().group(),
            snapshot.resume().region(), snapshot.resume().host(), snapshot.resume().port(),
            snapshot.state().name(), snapshot.leaseValid(),
            snapshot.players(), snapshot.reservations(), snapshot.resume().safeLimit(),
            snapshot.resume().hardLimit(),
            waking.containsKey(snapshot.session())
                ? TimeUnit.NANOSECONDS.toSeconds(now - waking.get(snapshot.session())) : -1L,
            managedSleep.contains(snapshot.session()), sleepRequests.containsKey(snapshot.session())))
        .sorted(java.util.Comparator.comparing(BackendStatus::group).thenComparing(BackendStatus::name))
        .toList();
  }

  /** Reports whether backends enroll with the shared challenge rather than pinned certificates. */
  public boolean pairingEnabled() {
    return configuration.pairing() != null;
  }

  /** Lists the backends that have completed challenge enrollment. */
  public Set<String> enrolledBackends() {
    return configuration.pairing() == null ? Set.of() : configuration.pairing().names();
  }

  /** Invalidates the shared challenge and publishes a replacement, leaving enrolled backends connected. */
  public void rotateChallenge() throws java.io.IOException {
    if (configuration.pairing() != null) {
      configuration.pairing().rotateChallenge();
    }
  }

  /** Revokes one backend's credential and drops its control connection so it cannot resume. */
  public synchronized boolean revokeBackend(String name) throws java.io.IOException {
    if (configuration.pairing() == null || !configuration.pairing().revoke(name)) {
      return false;
    }
    registry.snapshots().stream().filter(snapshot -> snapshot.resume().serverId().equals(name))
        .map(DiscoveryRegistry.Snapshot::session).toList().forEach(session -> {
          Channel channel = channels.get(session);
          disconnect(session); // Maintenance retires the published server once the lease lapses.
          if (channel != null) {
            channel.close();
          }
        });
    return true;
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
    long now = System.nanoTime();
    long sendAt = Math.max(now, nextHandoffSend.getOrDefault(session, now));
    if (sendAt - now > TimeUnit.SECONDS.toNanos(2)) {
      return CompletableFuture.failedFuture(new IllegalStateException("Backend handoff queue is full"));
    }
    nextHandoffSend.put(session, sendAt + TimeUnit.MILLISECONDS.toNanos(100));
    UUID request = UUID.randomUUID();
    CompletableFuture<JsonObject> response = new CompletableFuture<>();
    handoffRequests.put(request, new HandoffRpc(session, response));
    JsonObject framed = message.deepCopy();
    framed.addProperty("type", "handoff");
    framed.addProperty("session", peer.session().toString());
    framed.addProperty("request", request.toString());
    channel.eventLoop().schedule(() -> {
      if (!response.isDone()) {
        channel.writeAndFlush(gson.toJson(framed) + "\n").addListener(result -> {
          if (!result.isSuccess()) {
            response.completeExceptionally(result.cause());
          }
        });
      }
    }, sendAt - now, TimeUnit.NANOSECONDS);
    return response.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> {
      synchronized (DiscoveryService.this) {
        handoffRequests.remove(request);
      }
    });
  }

  /** Prepares a coordinated transfer after connection events have selected the final destination. */
  public CompletableFuture<HandoffCoordinator.@Nullable Ticket> prepareHandoff(Player player, @Nullable String source,
      String destination, java.util.function.BooleanSupplier valid, java.util.concurrent.Executor playerLoop) {
    if (handoff == null) {
      return CompletableFuture.completedFuture(null);
    }
    String group;
    synchronized (this) {
      group = registry.snapshots().stream().filter(snapshot -> snapshot.resume().serverId().equals(destination))
          .map(snapshot -> snapshot.resume().group()).findFirst().orElse("");
    }
    // The client already holds the source's entity id; ask the destination to keep it so a seamless
    // switch need not reset the client. The destination refuses if that id is taken.
    // Prefer the live connection, but fall back to the last id we saw for this player: a backend that
    // died takes its connection with it, and the client still holds the id it was given.
    String mode = configuration.handoffModes().getOrDefault(group, "off");
    int requestedEntityId = 0;
    if (mode.startsWith("seamless-")
        && player instanceof com.velocitypowered.proxy.connection.client.ConnectedPlayer connected) {
      requestedEntityId = connected.getConnectedServer() != null
          ? connected.getConnectedServer().getEntityId() : connected.lastKnownEntityId();
    }
    return handoff.prepare(player.getUniqueId(), source, destination, mode, valid, playerLoop, requestedEntityId);
  }

  /** Finalizes source release only after Velocity reports a successful destination connection. */
  public void finishHandoff(HandoffCoordinator.@Nullable Ticket ticket, boolean connected) {
    if (handoff != null && ticket != null) {
      handoff.finish(ticket, connected).exceptionally(failure -> {
        logger.error("Handoff {} remains recoverable; source release failed", ticket.transfer(), failure);
        return null;
      });
    }
  }

  /** Ensures a detached-config fallback receives the backend's ordinary arrival teleport. */
  public CompletableFuture<Void> requireVisibleArrival(UUID player, String destination) {
    return handoff == null ? CompletableFuture.completedFuture(null)
        : handoff.requireVisibleArrival(player, destination);
  }

  /** Approves suppressing the arrival sync after detached CONFIG has matched. */
  public CompletableFuture<Void> approveSeamlessArrival(UUID player, String destination) {
    return handoff == null ? CompletableFuture.failedFuture(
        new IllegalStateException("Coordinated handoff is unavailable"))
        : handoff.approveSeamlessArrival(player, destination);
  }

  /** Entities the source had shown this client, reported when it fenced. */
  public int[] sourceEntities(UUID player) {
    return handoff == null ? new int[0] : handoff.sourceEntities(player);
  }

  /** Scoreboard objectives the source had shown this client, reported when it fenced. */
  public String[] sourceObjectives(UUID player) {
    return handoff == null ? new String[0] : handoff.sourceObjectives(player);
  }

  /** Scoreboard teams the source had shown this client, reported when it fenced. */
  public String[] sourceTeams(UUID player) {
    return handoff == null ? new String[0] : handoff.sourceTeams(player);
  }

  /** Whether the destination was already told to suppress this player's arrival position sync. */
  public boolean seamlessArrivalApproved(UUID player) {
    return handoff != null && handoff.seamlessArrivalApproved(player);
  }

  public boolean hasCommittedHandoff(UUID player) {
    return handoff != null && handoff.recoveryOwner(player).isPresent();
  }

  public boolean needsHandoffRecovery(UUID player) {
    return handoff != null && handoff.requiresRecovery(player);
  }

  /** Allows detached negotiation only for the committed destination in preferred mode. */
  public synchronized boolean allowsDetachedConfiguration(UUID player, String source, String destination) {
    Optional<HandoffCoordinator.Peer> from = handoffPeer(source);
    Optional<HandoffCoordinator.Peer> to = handoffPeer(destination);
    return handoff != null && handoff.recoveryOwner(player).filter(destination::equals).isPresent()
        && handoff.canRetainEntityId(player)
        && from.isPresent() && to.isPresent()
        // Both ends must implement arrival control. Without it the destination decides on its own
        // whether to send its arrival sync, and this proxy cannot hold that decision.
        && from.get().capabilities().seamless() && to.get().capabilities().seamless()
        && from.get().capabilities().matches(to.get().capabilities())
        && registry.snapshots().stream().filter(snapshot -> snapshot.resume().serverId().equals(destination))
        .anyMatch(snapshot -> configuration.handoffModes().getOrDefault(snapshot.resume().group(), "off")
            .equals("seamless-preferred"));
  }

  /** Captures native configuration only for explicitly selected seamless testing modes. */
  public synchronized boolean captureSeamlessBaseline(String backend) {
    return registry.snapshots().stream().filter(snapshot -> snapshot.resume().serverId().equals(backend))
        .map(snapshot -> configuration.handoffModes().getOrDefault(snapshot.resume().group(), "off"))
        .anyMatch(mode -> mode.equals("seamless-preferred") || mode.equals("seamless-required"));
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
      if (excluded.contains(owner)) {
        return Optional.empty();
      }
      RegisteredServer target = server.getServer(owner).orElse(null);
      if (target == null) {
        return Optional.empty();
      }
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
    if (configuration.identity(name.toLowerCase(java.util.Locale.ROOT)) != null
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
    boolean managed = configuration.identity(name.toLowerCase(java.util.Locale.ROOT)) != null;
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

  /**
   * Wakes a sleeping backend that a privileged player asked for by name. Ordinary admission never
   * reaches this: capacity policy decides when a spare is needed, and this is the deliberate override.
   * Reports whether the backend is now starting, so the caller can say so instead of blaming capacity.
   */
  public synchronized boolean wakeOnDemand(String name) {
    var target = registry.snapshots().stream()
        .filter(snapshot -> snapshot.resume().serverId().equalsIgnoreCase(name))
        .filter(DiscoveryRegistry.Snapshot::leaseValid).findFirst().orElse(null);
    if (target == null || target.state() == DiscoveryRegistry.State.READY) {
      return false;
    }
    if (waking.containsKey(target.session())) {
      return true; // Already starting; repeated requests must not queue more wakes.
    }
    if (target.state() != DiscoveryRegistry.State.SLEEPING
        || System.nanoTime() < wakeRetryAfter.getOrDefault(target.session(), Long.MIN_VALUE)) {
      return target.state() != DiscoveryRegistry.State.DRAINING;
    }
    sendWake(target.session(), target.resume().group());
    return true;
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
    DiscoveryConfiguration.Identity identity = configuration.identity(resume.serverId());
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
    managedSleep.remove(session);
    sleepRequests.remove(session);
    idleSince.remove(session);
    nextHandoffSend.remove(session);
    java.util.List.copyOf(handoffRequests.values()).stream().filter(request -> request.session().equals(session))
        .forEach(request -> request.result().completeExceptionally(new IllegalStateException("Handoff control disconnected")));
  }

  synchronized void maintenance() {
    try {
      observeConnections();
      wakeSpares();
      nudgeStuckBackends();
      sleepIdleSpares();
      if (handoff != null && System.nanoTime() >= nextReleaseRetry) {
        nextReleaseRetry = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        handoff.retryReleases();
        handoff.recoverAborts();
      }
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
      boolean emptyDemand = (demand.containsKey(group) || members.stream().anyMatch(s ->
          managedSleep.contains(s.session()) && s.leaseValid() && s.state() == DiscoveryRegistry.State.SLEEPING))
          && candidates.stream().noneMatch(CapacityPolicy.Candidate::ready);
      boolean highLoad = CapacityPolicy.shouldWake(candidates, false);
      // Regions fill independently, and the group total hides it: one region at its safe limit
      // averages out against an empty one elsewhere and never trips the group threshold. Judge each
      // region on its own capacity so the region players are actually landing in is what decides.
      String saturated = candidates.stream().map(c -> c.resume().region()).distinct().sorted()
          .filter(region -> CapacityPolicy.shouldWake(candidates.stream()
              .filter(c -> c.resume().region().equals(region)).toList(), false))
          .findFirst().orElse("");
      if (!highLoad && !emptyDemand && saturated.isEmpty()
          && candidates.stream().anyMatch(CapacityPolicy.Candidate::ready)
          && capacityAlerts.remove(group) != null) {
        String recovery = "Group " + group + " has sufficient ready capacity again.";
        logger.info(recovery);
        var notice = net.kyori.adventure.text.Component.text(recovery,
            net.kyori.adventure.text.format.NamedTextColor.GREEN);
        server.getAllPlayers().stream().filter(p -> p.hasPermission("purroxy.notifications.capacity"))
            .forEach(p -> p.sendMessage(notice));
      }
      // Keep a live backend in every region this group occupies. sleepIdleSpares already refuses to
      // sleep the last READY member of a region, so this only has to cover a region that was already
      // fully asleep - at proxy start, or after a backend was lost - where nothing else would wake it
      // until a player arrived and paid the cold start.
      if (wakeRegionFloor(group, members, now)) {
        continue; // A wake is already in flight for this group; load-based waking can follow later.
      }
      if (pending || (!highLoad && !emptyDemand && saturated.isEmpty())) {
        continue;
      }
      // A saturated region outranks the group-wide picture: it names where players are queuing.
      String desiredRegion = !saturated.isEmpty() ? saturated
          : demand.containsKey(group) ? demandRegions.getOrDefault(group, "")
          : candidates.stream().filter(CapacityPolicy.Candidate::ready)
              .max(java.util.Comparator.comparingDouble(c -> (double) c.load() / c.resume().safeLimit()))
              .map(c -> c.resume().region()).orElse("");
      var sleeping = members.stream().filter(s -> s.leaseValid()
              && s.state() == DiscoveryRegistry.State.SLEEPING)
          .filter(s -> now >= wakeRetryAfter.getOrDefault(s.session(), Long.MIN_VALUE)).toList();
      // Escalate outwards: the pressured region first, then anywhere else in the group. A backend in
      // the wrong region still carries load, which beats turning players away on latency grounds.
      var spare = sleeping.stream()
          .filter(s -> desiredRegion.isEmpty() || s.resume().region().equals(desiredRegion))
          .min(java.util.Comparator.comparing(s -> s.resume().serverId()));
      boolean crossRegion = spare.isEmpty();
      if (crossRegion) {
        spare = sleeping.stream().min(java.util.Comparator.comparing(s -> s.resume().serverId()));
      }
      if (spare.isEmpty()) {
        // Nothing left to wake anywhere. Only a human can add capacity now, so say which region.
        capacityAlert(group, desiredRegion.isEmpty()
            ? "no sleeping spare is available, so connections will use overflow capacity."
            : "region " + desiredRegion + " is getting close to full and no spare is left to wake in"
                + " any region, so please deploy another server there to keep the balance.");
        continue;
      }
      if (crossRegion) {
        logger.info("Region {} of group {} has no spare left; waking {} in {} instead to carry load.",
            desiredRegion, group, spare.get().resume().serverId(), spare.get().resume().region());
      }
      sendWake(spare.get().session(), group);
    }
  }

  /**
   * Wakes one sleeping spare in each region of {@code group} that has fallen below its readiness
   * floor. Returns whether any wake was sent, so the caller can let it settle before also waking for
   * load. A region with no sleeping spare left is not an alert: it may simply have none registered.
   */
  private boolean wakeRegionFloor(String group, java.util.List<DiscoveryRegistry.Snapshot> members, long now) {
    int floor = configuration.minReadyPerRegion(group);
    if (floor <= 0) {
      return false;
    }
    boolean woke = false;
    for (String region : members.stream().map(s -> s.resume().region()).distinct().toList()) {
      var inRegion = members.stream().filter(s -> s.resume().region().equals(region)).toList();
      if (inRegion.stream().anyMatch(s -> waking.containsKey(s.session()))
          || inRegion.stream().filter(s -> s.leaseValid()
              && s.state() == DiscoveryRegistry.State.READY).count() >= floor) {
        continue;
      }
      var spare = inRegion.stream()
          .filter(s -> s.leaseValid() && s.state() == DiscoveryRegistry.State.SLEEPING)
          .filter(s -> now >= wakeRetryAfter.getOrDefault(s.session(), Long.MIN_VALUE))
          .min(java.util.Comparator.comparing(s -> s.resume().serverId()));
      if (spare.isPresent()) {
        logger.info("Region {} of group {} has no ready backend; waking {} to hold the floor.",
            region, group, spare.get().resume().serverId());
        sendWake(spare.get().session(), group);
        woke = true;
      }
    }
    return woke;
  }

  private void sendWake(DiscoveryRegistry.Session session, String group) {
    Channel channel = channels.get(session);
    if (channel == null || !channel.isActive()) {
      return;
    }
    waking.put(session, System.nanoTime());
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

  /**
   * Re-wakes a leased backend that has been stuck short of READY. A preparation that never finishes
   * would otherwise strand it: the backend clears its own sleep request on the first wake, so only a
   * further wake can restart preparation. Sleeping and draining backends are left alone.
   */
  private void nudgeStuckBackends() {
    long now = System.nanoTime();
    Set<DiscoveryRegistry.Session> stalled = new java.util.HashSet<>();
    for (var snapshot : registry.snapshots()) {
      DiscoveryRegistry.State state = snapshot.state();
      if (!snapshot.leaseValid() || (state != DiscoveryRegistry.State.REGISTERING
          && state != DiscoveryRegistry.State.WAKING)) {
        continue;
      }
      DiscoveryRegistry.Session session = snapshot.session();
      stalled.add(session);
      Long since = notReadySince.putIfAbsent(session, now);
      if (since == null || now - since < TimeUnit.SECONDS.toNanos(60) || waking.containsKey(session)
          || now < wakeRetryAfter.getOrDefault(session, Long.MIN_VALUE)) {
        continue;
      }
      notReadySince.put(session, now);
      logger.warn("Backend {} has been {} for over a minute; requesting another wake.",
          snapshot.resume().serverId(), state);
      sendWake(session, snapshot.resume().group());
    }
    notReadySince.keySet().retainAll(stalled);
  }

  private void sleepIdleSpares() {
    long now = System.nanoTime();
    for (var pending : java.util.List.copyOf(sleepRequests.entrySet())) {
      if (now - pending.getValue().started() >= TimeUnit.SECONDS.toNanos(20)) {
        Channel channel = channels.get(pending.getKey());
        if (channel != null) {
          channel.close(); // Ambiguous sleep outcome must reauthenticate before routing resumes.
        }
        sleepRequests.remove(pending.getKey());
      }
    }
    var snapshots = registry.snapshots();
    for (var member : snapshots) {
      RegisteredServer physical = published.get(member.session());
      if (!managedSleep.contains(member.session()) || !member.leaseValid()
          || member.state() != DiscoveryRegistry.State.READY || member.players() != 0
          || member.reservations() != 0 || physical == null || !physical.getPlayersConnected().isEmpty()) {
        idleSince.remove(member.session());
        continue;
      }
      long idle = idleSince.computeIfAbsent(member.session(), ignored -> now);
      if (now - idle < TimeUnit.MINUTES.toNanos(5) || demand.containsKey(member.resume().group())
          || !sleepRequests.isEmpty()) {
        continue;
      }
      boolean spare = snapshots.stream().anyMatch(other -> !other.session().equals(member.session())
          && other.resume().group().equals(member.resume().group())
          && other.resume().region().equals(member.resume().region()) && other.leaseValid()
          && other.state() == DiscoveryRegistry.State.READY
          && 2L * (Math.max(other.players(), published.get(other.session()).getPlayersConnected().size())
              + other.reservations()) <= other.resume().safeLimit());
      if (!spare || !registry.prepareSleep(member.session())) {
        continue;
      }
      Channel channel = channels.get(member.session());
      if (channel == null || !channel.isActive()) {
        registry.disconnected(member.session());
        continue;
      }
      UUID request = UUID.randomUUID();
      sleepRequests.put(member.session(), new SleepRequest(request, now));
      JsonObject sleep = new JsonObject();
      sleep.addProperty("type", "sleep");
      sleep.addProperty("session", member.session().token().toString());
      sleep.addProperty("request", request.toString());
      channel.writeAndFlush(gson.toJson(sleep) + "\n").addListener(result -> {
        if (!result.isSuccess()) {
          channel.close();
        }
      });
      logger.info("Requested sleep for idle spare {}", member.resume().serverId());
      break;
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
        + ". Bring another backend online if demand continues.";
    logger.warn(text);
    var notice = net.kyori.adventure.text.Component.text(text, net.kyori.adventure.text.format.NamedTextColor.YELLOW);
    server.getAllPlayers().stream().filter(p -> p.hasPermission("purroxy.notifications.capacity"))
        .forEach(p -> p.sendMessage(notice));
  }

  @Override
  public synchronized void close() {
    timer.shutdownNow();
    regionPreferences.close();
    if (handoff != null) {
      handoff.close();
    }
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
        PairingStore.Registration paired = null;
        BackendResume resume;
        String fingerprint;
        if (configuration.pairing() != null) {
          Set<String> occupied = server.getAllServers().stream().map(physical -> physical.getServerInfo().getName())
              .collect(java.util.stream.Collectors.toSet());
          paired = configuration.pairing().authenticate(message,
              ((InetSocketAddress) context.channel().remoteAddress()).getAddress().getHostAddress(),
              configuration.groups(), occupied);
          resume = paired.resume();
          fingerprint = PairingStore.hash(paired.credential());
        } else {
          SslHandler ssl = context.pipeline().get(SslHandler.class);
          fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
              .digest(ssl.engine().getSession().getPeerCertificates()[0].getEncoded()));
          resume = gson.fromJson(message.get("resume"), BackendResume.class);
        }
        session = register(resume, fingerprint, context.channel());
        if (message.has("handoff")) {
          HandoffCapabilities capabilities = gson.fromJson(message.get("handoff"), HandoffCapabilities.class);
          synchronized (DiscoveryService.this) {
            handoffCapabilities.put(session, capabilities);
          }
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "registered");
        response.addProperty("session", session.token().toString());
        response.addProperty("heartbeatSeconds", 5);
        response.addProperty("leaseSeconds", 20);
        if (paired != null) {
          response.addProperty("serverId", paired.resume().serverId());
          response.addProperty("credential", paired.credential());
          // The proxy is the authority for entity id ranges, as it is for names: a backend that
          // allocates within its own range can never mint an id another backend is already using.
          response.addProperty("entityIdBase", paired.entityIdBase());
          response.addProperty("sleepManaged", true);
          synchronized (DiscoveryService.this) {
            managedSleep.add(session);
          }
        }
        context.writeAndFlush(gson.toJson(response) + "\n");
      } else if (type.equals("heartbeat")) {
        if (!session.token().toString().equals(message.get("session").getAsString())) {
          throw new IllegalArgumentException("Session mismatch");
        }
        if (configuration.pairing() != null) {
          configuration.pairing().confirm(session.serverId());
        }
        if (message.get("state").getAsString().equals("SLEEPING")) {
          synchronized (DiscoveryService.this) {
            sleepRequests.remove(session);
          }
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
      } else if (type.equals("sleep-result")) {
        if (!session.token().toString().equals(message.get("session").getAsString())) {
          throw new IllegalArgumentException("Session mismatch");
        }
        synchronized (DiscoveryService.this) {
          SleepRequest pending = sleepRequests.get(session);
          if (pending != null && pending.request().toString().equals(message.get("request").getAsString())
              && !message.get("accepted").getAsBoolean()) {
            sleepRequests.remove(session);
            idleSince.remove(session);
            registry.cancelSleep(session);
          }
        }
      } else if (type.equals("handoff-result")) {
        if (!session.token().toString().equals(message.get("session").getAsString())) {
          throw new IllegalArgumentException("Handoff session mismatch");
        }
        UUID requestId = UUID.fromString(message.get("request").getAsString());
        HandoffRpc request;
        synchronized (DiscoveryService.this) {
          request = handoffRequests.get(requestId);
        }
        if (request != null && request.session().equals(session)) {
          request.result().complete(message.deepCopy());
        }
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
      if (configuration.pairing() != null && failure.getMessage() != null && failure.getMessage().startsWith("UNKNOWN_GROUP:")) {
        synchronized (DiscoveryService.this) {
          long now = System.nanoTime();
          String warning = failure.getMessage();
          Long last = lastUnknownGroupAlert.get(warning);
          if (last == null || now - last >= TimeUnit.MINUTES.toNanos(1)) {
            lastUnknownGroupAlert.put(warning, now);
            logger.warn("An authenticated backend attempted to join {}. Configure the group or correct its Nekopurr resume.", warning);
            var notice = net.kyori.adventure.text.Component.text("A pairing backend requested " + warning
                + ". Configure the group or correct its Nekopurr resume.");
            server.getAllPlayers().stream().filter(player -> player.hasPermission("purroxy.notifications.discovery"))
                .forEach(player -> player.sendMessage(notice));
          }
        }
      }
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
