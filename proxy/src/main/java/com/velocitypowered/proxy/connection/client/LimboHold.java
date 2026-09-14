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

import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Holds a player in the proxy when no backend will take them, instead of dropping them to the
 * multiplayer screen.
 *
 * <p>This is only practical because a seamless switch already keeps the client in PLAY while its
 * backend changes underneath: "no backend attached for a while" is the same shape as "between
 * backends". The client keeps rendering the last world the server sent it, which is frozen but
 * honest - it is genuinely the most recent thing anyone told it.
 *
 * <p>The proxy takes over the two jobs the missing backend was doing: sending keep-alives so the
 * client does not time itself out, and saying what is going on. Everything else the client sends is
 * dropped on the floor, because there is nowhere for it to go. The hold is bounded: waiting forever
 * in a frozen world is worse than an honest disconnect, so when the window expires the player is
 * released with the reason they would have received in the first place.
 */
public final class LimboHold implements AutoCloseable {

  private static final Logger logger = LogManager.getLogger(LimboHold.class);

  /** Well inside a vanilla client's own timeout, so it never decides the server has gone away. */
  private static final long KEEP_ALIVE_MILLIS = 8_000;
  /** A wait can run for minutes, so the standing notice is paced not to become spam. */
  private static final long NOTICE_MILLIS = 15_000;

  /** Staff who may bypass the queue, so whoever can end an outage is not stuck behind it. */
  public static final String BYPASS_PERMISSION = "purroxy.limbo.bypass";

  private record Held(ConnectedPlayer player, Component reason, long since, long[] lastKeepAlive,
                      long[] lastNotice, boolean priority) {
  }

  private final Map<UUID, Held> held = new ConcurrentHashMap<>();
  private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "purroxy-limbo");
    thread.setDaemon(true);
    return thread;
  });
  private final long holdMillis;
  private final int releasesPerTick;

  /**
   * Starts the ticker that keeps held players alive and looks for somewhere to put them.
   *
   * @param holdSeconds how long a player may wait for a backend; zero disables holding
   * @param releasesPerSecond how many players may be sent at a returning backend each second
   */
  public LimboHold(int holdSeconds, int releasesPerSecond) {
    this.holdMillis = TimeUnit.SECONDS.toMillis(holdSeconds);
    this.releasesPerTick = Math.max(1, releasesPerSecond);
    if (holdSeconds > 0) {
      ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }
  }

  /**
   * Takes a player who would otherwise be disconnected, if holding them is enabled and they are far
   * enough along to have a world to look at.
   *
   * @return whether the player is now held rather than needing to be disconnected
   */
  public boolean hold(ConnectedPlayer player, Component reason) {
    if (holdMillis <= 0 || !player.isActive()
        || !(player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler)) {
      // Before PLAY there is no world on screen, so a hold would just be a black screen with a
      // countdown. Those players are better served by the real reason straight away.
      return false;
    }
    long now = System.currentTimeMillis();
    // Resolved once, here, rather than every tick: a permission lookup can reach a database,
    // and nobody's staff status changes during the outage they are sitting in.
    boolean priority = player.hasPermission(BYPASS_PERMISSION);
    if (held.putIfAbsent(player.getUniqueId(),
        new Held(player, reason, now, new long[] {0}, new long[] {0}, priority)) != null) {
      return true;
    }
    logger.info("{} is held in the proxy: no backend would take them. Holding up to {}s{}.",
        player.getUsername(), holdMillis / 1000, priority ? ", bypassing the queue" : "");
    return true;
  }

  /**
   * Tells everyone waiting that something is happening on their behalf, out of band from the
   * standing notice. Progress is worth interrupting for; the absence of it is not.
   */
  public void announce(Component message) {
    if (held.isEmpty()) {
      return;
    }
    for (Held entry : Map.copyOf(held).values()) {
      if (entry.player().isActive()) {
        entry.player().sendMessage(message);
      }
    }
  }

  /** Stops holding a player, whether they left, were released, or found a server. */
  public void release(UUID player) {
    held.remove(player);
  }

  public int size() {
    return held.size();
  }

  private void tick() {
    try {
      long now = System.currentTimeMillis();
      // Longest wait first, so a queue that forms during an outage drains in the order it
      // formed rather than by whoever the map happens to iterate first.
      List<Held> waiting = held.values().stream()
          .sorted(java.util.Comparator.comparing((Held entry) -> !entry.priority())
              .thenComparingLong(Held::since)).toList();
      int released = 0;
      for (int place = 0; place < waiting.size(); place++) {
        Held entry = waiting.get(place);
        ConnectedPlayer player = entry.player();
        if (!player.isActive()) {
          held.remove(player.getUniqueId());
          continue;
        }
        if (now - entry.since() >= holdMillis) {
          held.remove(player.getUniqueId());
          logger.info("{} was held for {}s without a backend becoming available; releasing them.",
              player.getUsername(), holdMillis / 1000);
          player.disconnect(entry.reason());
          continue;
        }
        keepAlive(player, entry, now);
        notice(player, entry, now, place + 1, waiting.size());
        // Rate limited on purpose. A hub that has just come back would be knocked straight
        // over again by everyone who was waiting for it arriving at once.
        // Staff are not counted against the rate limit: the point of the limit is to protect a
        // returning hub from a crowd, and the people who can fix it are not the crowd.
        if ((entry.priority() || released < releasesPerTick) && player.tryLeaveLimbo()) {
          released += entry.priority() ? 0 : 1;
          held.remove(player.getUniqueId());
          logger.info("{} left the proxy hold for an available server ({} still waiting).",
              player.getUsername(), waiting.size() - released);
        }
      }
    } catch (RuntimeException failure) {
      logger.error("The proxy hold failed a tick; players in it stay held.", failure);
    }
  }

  private void keepAlive(ConnectedPlayer player, Held entry, long now) {
    if (now - entry.lastKeepAlive()[0] < KEEP_ALIVE_MILLIS) {
      return;
    }
    entry.lastKeepAlive()[0] = now;
    // The client answers this and the reply is dropped: forwardKeepAlive finds no backend and
    // does nothing. All that matters is that the client keeps hearing from someone.
    KeepAlivePacket alive = new KeepAlivePacket();
    alive.setRandomId(ThreadLocalRandom.current().nextLong());
    player.getConnection().write(alive);
  }

  private void notice(ConnectedPlayer player, Held entry, long now, int place, int waiting) {
    if (now - entry.lastNotice()[0] < NOTICE_MILLIS) {
      return;
    }
    entry.lastNotice()[0] = now;
    player.sendMessage(message(waiting, place, entry.priority()));
  }

  /** The standing notice shown while a player waits, with their place in the queue. */
  static Component message(int waiting, int place, boolean priority) {
    String rule = "====================================================";
    return Component.text()
        .append(Component.text(rule, NamedTextColor.DARK_GRAY)).append(Component.newline())
        .append(Component.text("Currently if you are seeing this message. Only proxy is living at"
            + " the moment. You will be reconnected to your last server or available hub when"
            + " possible.", NamedTextColor.YELLOW))
        .append(Component.newline()).append(Component.newline())
        .append(Component.text("Current Connected Limbo Players: ", NamedTextColor.GRAY))
        .append(Component.text(waiting, NamedTextColor.WHITE))
        .append(Component.newline())
        .append(Component.text("Your place in the queue: ", NamedTextColor.GRAY))
        .append(priority
            ? Component.text("priority access, not queued", NamedTextColor.GREEN)
            : Component.text(place + " of " + waiting, NamedTextColor.WHITE))
        .append(Component.newline())
        .append(Component.text(rule, NamedTextColor.DARK_GRAY))
        .build();
  }

  @Override
  public void close() {
    ticker.shutdownNow();
  }
}
