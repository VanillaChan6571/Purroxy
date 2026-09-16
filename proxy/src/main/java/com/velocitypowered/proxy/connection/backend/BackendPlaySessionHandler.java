/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

import static com.velocitypowered.proxy.connection.backend.BungeeCordMessageResponder.getBungeeCordChannel;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreTransferEvent;
import com.velocitypowered.api.event.player.CookieRequestEvent;
import com.velocitypowered.api.event.player.CookieStoreEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.command.CommandGraphInjector;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.player.resourcepack.VelocityResourcePackInfo;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ResourcePackHandler;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import com.velocitypowered.proxy.protocol.packet.BundleDelimiterPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.RemovePlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.RemoveResourcePackPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerDataPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TransferPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import com.velocitypowered.proxy.protocol.util.DeferredByteBufHolder;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles a connected player.
 */
public class BackendPlaySessionHandler implements MinecraftSessionHandler {

  private static final Pattern PLAUSIBLE_SHA1_HASH = Pattern.compile("^[a-z0-9]{40}$");
  private static final Logger logger = LogManager.getLogger(BackendPlaySessionHandler.class);
  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");
  private static final int MAXIMUM_PACKETS_TO_FLUSH =
      Integer.getInteger("velocity.max-packets-per-flush", 8192);
  private static final int LARGE_PACKET_THRESHOLD = 1024 * 128;

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final ClientPlaySessionHandler playerSessionHandler;
  private final MinecraftConnection playerConnection;
  private final BungeeCordMessageResponder bungeecordMessageResponder;
  private boolean exceptionTriggered = false;
  private int packetsFlushed;

  BackendPlaySessionHandler(VelocityServer server, VelocityServerConnection serverConn) {
    this.server = server;
    this.serverConn = serverConn;
    this.playerConnection = serverConn.getPlayer().getConnection();

    MinecraftSessionHandler psh = playerConnection.getActiveSessionHandler();
    if (!(psh instanceof ClientPlaySessionHandler)) {
      throw new IllegalStateException(
          "Initializing BackendPlaySessionHandler with no backing client play session handler!");
    }
    this.playerSessionHandler = (ClientPlaySessionHandler) psh;

    this.bungeecordMessageResponder = new BungeeCordMessageResponder(server,
        serverConn.getPlayer());
  }

  @Override
  public void activated() {
    serverConn.getServer().addPlayer(serverConn.getPlayer());
    startEntityLedger();

    MinecraftConnection serverMc = serverConn.ensureConnected();
    if (server.getConfiguration().isBungeePluginChannelEnabled()) {
      serverMc.write(PluginMessageUtil.constructChannelsPacket(serverMc.getProtocolVersion(),
          ImmutableList.of(getBungeeCordChannel(serverMc.getProtocolVersion()))
      ));
    }

  }

  @Override
  public boolean beforeHandle() {
    if (!serverConn.isActive()) {
      // Obsolete connection
      serverConn.disconnect();
      return true;
    }
    return false;
  }

  @Override
  public boolean handle(BundleDelimiterPacket bundleDelimiterPacket) {
    serverConn.getPlayer().getBundleHandler().toggleBundleSession();
    return false;
  }

  @Override
  public boolean handle(StartUpdatePacket packet) {
    MinecraftConnection smc = serverConn.ensureConnected();
    smc.setAutoReading(false);
    // Even when not auto reading messages are still decoded. Decode them with the correct state
    smc.getChannel().pipeline().get(MinecraftVarintFrameDecoder.class).setState(StateRegistry.CONFIG);
    smc.getChannel().pipeline().get(MinecraftDecoder.class).setState(StateRegistry.CONFIG);
    serverConn.getPlayer().switchToConfigState();
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    serverConn.getPendingPings().put(packet.getRandomId(), System.nanoTime());
    return false; // forwards on
  }

  @Override
  public boolean handle(ClientSettingsPacket packet) {
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    serverConn.disconnect();
    serverConn.getPlayer().handleConnectionException(serverConn.getServer(), packet, true);
    return true;
  }

  @Override
  public boolean handle(BossBarPacket packet) {
    if (packet.getAction() == BossBarPacket.ADD) {
      if (!playerSessionHandler.getServerBossBars().contains(packet.getUuid())) {
        playerSessionHandler.getServerBossBars().add(packet.getUuid());
      }
    } else if (packet.getAction() == BossBarPacket.REMOVE) {
      playerSessionHandler.getServerBossBars().remove(packet.getUuid());
    }
    return false; // forward
  }

  /**
   * Registered in PLAY from 1.21, so it is recognised by type rather than by a moving packet id.
   * Either packet changes the configuration the client holds after its baseline was captured.
   */
  @Override
  public boolean handle(
      com.velocitypowered.proxy.protocol.packet.config.ClientboundServerLinksPacket packet) {
    serverConn.getPlayer().setSeamlessBaseline(null);
    return false; // forward
  }

  @Override
  public boolean handle(
      com.velocitypowered.proxy.protocol.packet.config.ClientboundCustomReportDetailsPacket packet) {
    serverConn.getPlayer().setSeamlessBaseline(null);
    return false; // forward
  }

  @Override
  public boolean handle(final ResourcePackRequestPacket packet) {
    serverConn.getPlayer().setSeamlessBaseline(null);
    final ResourcePackInfo.Builder builder = new VelocityResourcePackInfo.BuilderImpl(
        Preconditions.checkNotNull(packet.getUrl()))
        .setId(packet.getId())
        .setPrompt(packet.getPrompt() == null ? null : packet.getPrompt().getComponent())
        .setShouldForce(packet.isRequired())
        .setOrigin(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);

    final String hash = packet.getHash();
    if (hash != null && !hash.isEmpty()) {
      if (PLAUSIBLE_SHA1_HASH.matcher(hash).matches()) {
        builder.setHash(ByteBufUtil.decodeHexDump(hash));
      }
    }

    final ResourcePackInfo resourcePackInfo = builder.build();
    final ServerResourcePackSendEvent event = new ServerResourcePackSendEvent(
            resourcePackInfo, this.serverConn);
    server.getEventManager().fire(event).thenAcceptAsync(serverResourcePackSendEvent -> {
      if (playerConnection.isClosed()) {
        return;
      }
      if (serverResourcePackSendEvent.getResult().isAllowed()) {
        final ResourcePackInfo toSend = serverResourcePackSendEvent.getProvidedResourcePack();
        boolean modifiedPack = false;
        if (toSend != serverResourcePackSendEvent.getReceivedResourcePack()) {
          ((VelocityResourcePackInfo) toSend)
              .setOriginalOrigin(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
          modifiedPack = true;
        }
        if (serverConn.getPlayer().resourcePackHandler().hasPackAppliedByHash(toSend.getHash())) {
          // Do not apply a resource pack that has already been applied
          if (serverConn.getConnection() != null) {
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                    packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.ACCEPTED));
            if (serverConn.getConnection().getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_3)) {
              serverConn.getConnection().write(new ResourcePackResponsePacket(
                  packet.getId(), packet.getHash(),
                  PlayerResourcePackStatusEvent.Status.DOWNLOADED));
            }
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                packet.getId(), packet.getHash(),
                PlayerResourcePackStatusEvent.Status.SUCCESSFUL));
          }
          if (modifiedPack) {
            logger.warn("A plugin has tried to modify a ResourcePack provided by the backend server "
                    + "with a ResourcePack already applied, the applying of the resource pack will be skipped.");
          }
        } else {
          serverConn.getPlayer().resourcePackHandler().queueResourcePack(toSend);
        }
      } else if (serverConn.getConnection() != null) {
        serverConn.getConnection().write(new ResourcePackResponsePacket(
            packet.getId(),
            packet.getHash(),
            PlayerResourcePackStatusEvent.Status.DECLINED
        ));
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      if (serverConn.getConnection() != null) {
        serverConn.getConnection().write(new ResourcePackResponsePacket(
            packet.getId(),
            packet.getHash(),
            PlayerResourcePackStatusEvent.Status.DECLINED
        ));
      }
      logger.error("Exception while handling resource pack send for {}", playerConnection, ex);
      return null;
    });

    return true;
  }

  @Override
  public boolean handle(RemoveResourcePackPacket packet) {
    final ServerResourcePackRemoveEvent event = new ServerResourcePackRemoveEvent(
            packet.getId(), this.serverConn);
    server.getEventManager().fire(event).thenAcceptAsync(serverResourcePackRemoveEvent -> {
      if (playerConnection.isClosed()) {
        return;
      }
      if (serverResourcePackRemoveEvent.getResult().isAllowed()) {
        final ConnectedPlayer player = serverConn.getPlayer();
        final ResourcePackHandler handler = player.resourcePackHandler();
        if (packet.getId() != null) {
          handler.remove(packet.getId());
        } else {
          handler.clearAppliedResourcePacks();
        }
        playerConnection.write(packet);
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception while handling resource pack remove for {}", playerConnection, ex);
      return null;
    });
    return true;
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (bungeecordMessageResponder.process(packet)) {
      return true;
    }

    // Register and unregister packets are simply forwarded to the client as-is.
    if (PluginMessageUtil.isRegister(packet) || PluginMessageUtil.isUnregister(packet)) {
      return false;
    }

    if (PluginMessageUtil.isMcBrand(packet)) {
      PluginMessagePacket rewritten = PluginMessageUtil
              .rewriteMinecraftBrand(packet,
                      server.getVersion(), playerConnection.getProtocolVersion());
      playerConnection.write(rewritten);
      return true;
    }

    if (serverConn.getPhase().handle(serverConn, serverConn.getPlayer(), packet)) {
      // Handled.
      return true;
    }

    ChannelIdentifier id = server.getChannelRegistrar().getFromId(packet.getChannel());
    if (id == null) {
      return false;
    }

    byte[] copy = ByteBufUtil.getBytes(packet.content());
    PluginMessageEvent event = new PluginMessageEvent(serverConn, serverConn.getPlayer(), id, copy);
    server.getEventManager().fire(event).thenAcceptAsync(pme -> {
      if (pme.getResult().isAllowed() && !playerConnection.isClosed()) {
        PluginMessagePacket copied = new PluginMessagePacket(
                packet.getChannel(), Unpooled.wrappedBuffer(copy));
        playerConnection.write(copied);
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception while handling plugin message {}", packet, ex);
      return null;
    });
    return true;
  }

  @Override
  public boolean handle(TabCompleteResponsePacket packet) {
    playerSessionHandler.handleTabCompleteResponse(packet);
    return true;
  }

  @Override
  public boolean handle(LegacyPlayerListItemPacket packet) {
    serverConn.getPlayer().getTabList().processLegacy(packet);
    return false;
  }

  @Override
  public boolean handle(UpsertPlayerInfoPacket packet) {
    if (packet.getActions().contains(UpsertPlayerInfoPacket.Action.ADD_PLAYER)) {
      recordTabAdditions(packet);
    }
    serverConn.getPlayer().getTabList().processUpdate(packet);
    return false;
  }

  @Override
  public boolean handle(RemovePlayerInfoPacket packet) {
    SourceEntityLedger ledger = entityLedger();
    if (ledger != null) {
      for (java.util.UUID profile : packet.getProfilesToRemove()) {
        ledger.tabRemoved(serverConn.getPlayer().getUniqueId(), profile);
      }
    }
    serverConn.getPlayer().getTabList().processRemove(packet);
    return false;
  }

  @Override
  public boolean handle(AvailableCommandsPacket commands) {
    RootCommandNode<CommandSource> rootNode = commands.getRootNode();
    if (server.getConfiguration().isAnnounceProxyCommands()) {
      // Inject commands from the proxy.
      final CommandGraphInjector<CommandSource> injector = server.getCommandManager().getInjector();
      injector.inject(rootNode, serverConn.getPlayer());

      // In 1.21.6 a confirmation prompt was added when executing a command via `run_command` click
      // action if the command is unknown. To prevent this prompt we have to send the command.
      if (this.playerConnection.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_6)) {
        rootNode.removeChildByName("velocity:callback");
      }
    }

    server.getEventManager().fire(
            new PlayerAvailableCommandsEvent(serverConn.getPlayer(), rootNode))
        .thenAcceptAsync(event -> playerConnection.write(commands), playerConnection.eventLoop())
        .exceptionally((ex) -> {
          logger.error("Exception while handling available commands for {}", playerConnection, ex);
          return null;
        });
    return true;
  }

  @Override
  public boolean handle(ServerDataPacket packet) {
    server.getServerListPingHandler().getInitialPing(this.serverConn.getPlayer()).thenComposeAsync(
        ping -> server.getEventManager()
            .fire(new ProxyPingEvent(this.serverConn.getPlayer(), ping)),
        playerConnection.eventLoop()).thenAcceptAsync(pingEvent -> this.playerConnection.write(
            new ServerDataPacket(new ComponentHolder(
                this.serverConn.ensureConnected().getProtocolVersion(),
                pingEvent.getPing().getDescriptionComponent()),
                pingEvent.getPing().getFavicon().orElse(null), packet.isSecureChatEnforced())),
        playerConnection.eventLoop());
    return true;
  }

  @Override
  public boolean handle(TransferPacket packet) {
    final InetSocketAddress originalAddress = packet.address();
    if (originalAddress == null) {
      logger.error("""
          Unexpected nullable address received in TransferPacket \
          from Backend Server in Play State""");
      return true;
    }
    this.server.getEventManager()
        .fire(new PreTransferEvent(this.serverConn.getPlayer(), originalAddress))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            InetSocketAddress resultedAddress = event.getResult().address();
            if (resultedAddress == null) {
              resultedAddress = originalAddress;
            }
            this.playerConnection.write(new TransferPacket(
                    resultedAddress.getHostName(), resultedAddress.getPort()));
          }
        }, playerConnection.eventLoop());
    return true;
  }

  @Override
  public boolean handle(ClientboundStoreCookiePacket packet) {
    server.getEventManager()
        .fire(new CookieStoreEvent(serverConn.getPlayer(), packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();
            final byte[] resultedData = event.getResult().getData() == null
                ? event.getOriginalData() : event.getResult().getData();

            playerConnection.write(new ClientboundStoreCookiePacket(resultedKey, resultedData));
          }
        }, playerConnection.eventLoop());

    return true;
  }

  @Override
  public boolean handle(ClientboundCookieRequestPacket packet) {
    server.getEventManager().fire(new CookieRequestEvent(serverConn.getPlayer(), packet.getKey()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();

            playerConnection.write(new ClientboundCookieRequestPacket(resultedKey));
          }
        }, playerConnection.eventLoop());

    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    if (packet instanceof PluginMessagePacket pluginMessage) {
      pluginMessage.retain();
    }
    boolean huge = packet instanceof DeferredByteBufHolder def && def.content().readableBytes() > LARGE_PACKET_THRESHOLD;
    playerConnection.delayedWrite(packet);
    if (huge || ++packetsFlushed >= MAXIMUM_PACKETS_TO_FLUSH) {
      playerConnection.flush();
      packetsFlushed = 0;
    }
  }

  private void recordTabAdditions(UpsertPlayerInfoPacket packet) {
    SourceEntityLedger ledger = entityLedger();
    if (ledger == null) {
      return;
    }
    for (UpsertPlayerInfoPacket.Entry entry : packet.getEntries()) {
      ledger.tabAdded(serverConn.getPlayer().getUniqueId(), entry.getProfileId(),
          entry.getProfile() == null ? "no profile" : entry.getProfile().getName());
    }
  }

  /** The ledger, or null where discovery is absent or has not built one. */
  private SourceEntityLedger entityLedger() {
    return server.getDiscovery() == null ? null : server.getDiscovery().entityLedger();
  }

  /**
   * Begins recording the entities this backend sends the client, for the groups that can take a
   * seamless switch. Recording starts here, at the beginning of PLAY, rather than when a handoff
   * begins: an entity written straight to the connection by a plugin never enters the backend's
   * tracker, so it can only be caught by having watched the whole session.
   */
  private void startEntityLedger() {
    if (server.getDiscovery() == null) {
      return;
    }
    String backend = serverConn.getServerInfo().getName();
    if (!server.getDiscovery().captureSeamlessBaseline(backend)
        || !SeamlessProtocols.eligible(server, serverConn.getPlayer().getProtocolVersion())) {
      return;
    }
    SourceEntityLedger ledger = server.getDiscovery().entityLedger();
    if (ledger == null) {
      return;
    }
    ledger.start(serverConn.getPlayer().getUniqueId(), backend,
        server.getDiscovery().backendInstance(backend),
        serverConn.getPlayer().getProtocolVersion());
  }

  /**
   * Records an entity this backend has just shown the client, or drops ids it has taken away.
   *
   * <p>Reads through a duplicate and never past the fields it needs, so the buffer forwarded below
   * is untouched; a payload that does not parse is ignored rather than allowed to break the
   * connection, because this is a diagnostic and must not be able to cost a player their session.
   */
  private void observeEntityLifecycle(ByteBuf buf) {
    if (server.getDiscovery() == null) {
      return;
    }
    SourceEntityLedger ledger = entityLedger();
    java.util.UUID player = serverConn.getPlayer().getUniqueId();
    if (ledger == null || !ledger.records(player)) {
      return;
    }
    ProtocolVersion protocol = serverConn.getPlayer().getProtocolVersion();
    int addEntity = SeamlessProtocols.playAddEntityId(protocol);
    if (addEntity < 0) {
      return;
    }
    int addOrb = SeamlessProtocols.playAddExperienceOrbId(protocol);
    int removeEntities = SeamlessProtocols.playRemoveEntitiesId(protocol);
    // Same id-then-UUID prefix as add_entity; see SeamlessProtocols for why a version may spawn
    // through one, the other, or both.
    int addMob = SeamlessProtocols.playAddMobId(protocol);
    int addPlayer = SeamlessProtocols.playAddPlayerId(protocol);
    ByteBuf reading = buf.duplicate();
    try {
      int packetId = com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(reading);
      if (packetId == addEntity || addMob >= 0 && packetId == addMob
          || addPlayer >= 0 && packetId == addPlayer) {
        int entityId = com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(reading);
        // add_entity is id then UUID. The UUID is what ties a player-type entity to the profile
        // the client needs in its player list before it can render one.
        java.util.UUID entity = reading.readableBytes() >= 16
            ? new java.util.UUID(reading.readLong(), reading.readLong()) : null;
        ledger.spawned(player, entityId, entity);
      } else if (addOrb >= 0 && packetId == addOrb) {
        ledger.spawned(player, com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(reading));
      } else if (packetId == removeEntities) {
        int count = com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(reading);
        if (count < 0 || count > SourceEntityLedger.MAX_REMOVALS) {
          return;
        }
        for (int index = 0; index < count; index++) {
          ledger.removed(player, com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(reading));
        }
      }
    } catch (RuntimeException unreadable) {
      // Not ours to interpret. The packet is still forwarded verbatim below.
      logger.debug("Could not read an entity packet for the source ledger.", unreadable);
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    if (server.getDiscovery() != null && server.getDiscovery().captures() != null) {
      server.getDiscovery().captures().legacyPacket(serverConn.getPlayer().getUniqueId(),
          serverConn.getServerInfo().getName(), buf, serverConn.getPlayer().getProtocolVersion());
      server.getDiscovery().captures().observeClientbound(serverConn.getPlayer().getUniqueId(),
          serverConn.getServerInfo().getName(), buf);
      server.getDiscovery().captures().observeDeliveredChat(serverConn.getPlayer().getUniqueId(),
          buf, serverConn.getPlayer().getProtocolVersion());
    }
    observeEntityLifecycle(buf);
    // PLAY tags can change the configuration a baseline was captured from, and this proxy does not
    // register the PLAY form of that packet, so it arrives here opaquely and has to be recognised
    // by id. Report details and server links are registered from 1.21 and are caught by type
    // instead, above. Nothing is read at all once there is no baseline left to invalidate, which
    // is the ordinary case for every connection on every unknown packet.
    if (serverConn.getPlayer().seamlessBaseline() != null) {
      int updateTags = SeamlessProtocols.playUpdateTagsId(
          serverConn.getPlayer().getProtocolVersion());
      if (updateTags >= 0
          && com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(buf.duplicate())
              == updateTags) {
        serverConn.getPlayer().setSeamlessBaseline(null);
      }
    }
    boolean huge = buf.readableBytes() > LARGE_PACKET_THRESHOLD;
    playerConnection.delayedWrite(buf.retain());
    if (huge || ++packetsFlushed >= MAXIMUM_PACKETS_TO_FLUSH) {
      playerConnection.flush();
      packetsFlushed = 0;
    }
  }

  @Override
  public void readCompleted() {
    playerConnection.flush();
    packetsFlushed = 0;
  }

  @Override
  public void exception(Throwable throwable) {
    exceptionTriggered = true;
    serverConn.getPlayer().handleConnectionException(serverConn.getServer(), throwable,
        !(throwable instanceof ReadTimeoutException));
  }

  public VelocityServer getServer() {
    return server;
  }

  @Override
  public void disconnected() {
    serverConn.getServer().removePlayer(serverConn.getPlayer());
    if (!serverConn.isGracefulDisconnect() && !exceptionTriggered) {
      if (server.getConfiguration().isFailoverOnUnexpectedServerDisconnect()) {
        serverConn.getPlayer().handleConnectionException(serverConn.getServer(),
            DisconnectPacket.create(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR,
                serverConn.getPlayer().getProtocolVersion(),
                    serverConn.getPlayer().getConnection().getState()), true);
      } else {
        serverConn.getPlayer().disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
      }
    }
  }

  @Override
  public void writabilityChanged() {
    Channel serverChan = serverConn.ensureConnected().getChannel();
    boolean writable = serverChan.isWritable();

    if (BACKPRESSURE_LOG) {
      if (writable) {
        logger.info("{} is not writable, not auto-reading player connection data", this.serverConn);
      } else {
        logger.info("{} is writable, will auto-read player connection data", this.serverConn);
      }
    }

    playerConnection.setAutoReading(writable);
  }
}
