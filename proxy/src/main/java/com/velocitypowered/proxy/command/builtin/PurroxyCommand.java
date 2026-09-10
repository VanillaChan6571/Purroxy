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

package com.velocitypowered.proxy.command.builtin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.network.discovery.PairingStore;
import java.io.IOException;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Administrative control over the shared backend challenge and enrolled backend credentials. */
public final class PurroxyCommand {
  private static final String PERMISSION = "purroxy.admin.pairing";

  private PurroxyCommand() {
  }

  /** Creates the pairing administration command, available only while discovery is running. */
  public static BrigadierCommand create(VelocityServer server) {
    return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("purroxy")
        .requires(source -> source.hasPermission(PERMISSION) && server.getDiscovery() != null)
        .then(BrigadierCommand.literalArgumentBuilder("status")
            .executes(context -> backends(context.getSource(), server)))
        .then(BrigadierCommand.literalArgumentBuilder("pairing")
            .executes(context -> status(context.getSource(), server))
            .then(BrigadierCommand.literalArgumentBuilder("list")
                .executes(context -> status(context.getSource(), server)))
            .then(BrigadierCommand.literalArgumentBuilder("rotate")
                .executes(context -> rotate(context.getSource(), server)))
            .then(BrigadierCommand.literalArgumentBuilder("revoke")
                .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                    .suggests((context, builder) -> {
                      String prefix = builder.getRemainingLowerCase();
                      server.getDiscovery().enrolledBackends().stream()
                          .filter(name -> name.startsWith(prefix)).sorted().forEach(builder::suggest);
                      return builder.buildFuture();
                    })
                    .executes(context -> revoke(context.getSource(), server,
                        StringArgumentType.getString(context, "server").toLowerCase(Locale.ROOT))))))
        .build());
  }

  private static int backends(CommandSource source, VelocityServer server) {
    var backends = server.getDiscovery().status();
    if (backends.isEmpty()) {
      source.sendMessage(Component.text("No backends have registered.", NamedTextColor.YELLOW));
      return Command.SINGLE_SUCCESS;
    }
    for (var backend : backends) {
      StringBuilder line = new StringBuilder(backend.name())
          .append("  ").append(backend.group()).append('/').append(backend.region())
          .append("  ").append(backend.host()).append(':').append(backend.port())
          .append("  ").append(backend.state())
          .append(backend.leaseValid() ? "" : " (lease expired)")
          .append("  players ").append(backend.players())
          .append('+').append(backend.reservations()).append(" reserved")
          .append("  safe ").append(backend.safeLimit())
          .append("  hard ").append(backend.hardLimit());
      if (backend.wakeAgeSeconds() >= 0) {
        line.append("  wake pending ").append(backend.wakeAgeSeconds()).append("s");
      }
      if (backend.sleepPending()) {
        line.append("  sleep requested");
      }
      if (backend.managedSleep()) {
        line.append("  (proxy-managed spare)");
      }
      boolean usable = backend.leaseValid() && backend.state().equals("READY");
      source.sendMessage(Component.text(line.toString(),
          usable ? NamedTextColor.GREEN : backend.leaseValid() ? NamedTextColor.YELLOW : NamedTextColor.RED));
    }
    source.sendMessage(Component.text(
        "Only READY backends with a valid lease accept players.", NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int status(CommandSource source, VelocityServer server) {
    if (!server.getDiscovery().pairingEnabled()) {
      source.sendMessage(Component.text(
          "Backend pairing is off; discovery is using pinned certificates.", NamedTextColor.YELLOW));
      return 0;
    }
    var enrolled = server.getDiscovery().enrolledBackends().stream().sorted().toList();
    source.sendMessage(Component.text("Challenge file: purroxy-pairing/"
        + PairingStore.CHALLENGE_FILE, NamedTextColor.YELLOW));
    source.sendMessage(Component.text("Enrolled backends (" + enrolled.size() + "): "
        + (enrolled.isEmpty() ? "none" : String.join(", ", enrolled)), NamedTextColor.YELLOW));
    return Command.SINGLE_SUCCESS;
  }

  private static int rotate(CommandSource source, VelocityServer server) {
    if (!server.getDiscovery().pairingEnabled()) {
      source.sendMessage(Component.text("Backend pairing is not enabled.", NamedTextColor.RED));
      return 0;
    }
    try {
      server.getDiscovery().rotateChallenge();
    } catch (IOException failure) {
      source.sendMessage(Component.text("Unable to publish a new challenge: "
          + failure.getMessage(), NamedTextColor.RED));
      return 0;
    }
    source.sendMessage(Component.text("Published a new purroxy-pairing/" + PairingStore.CHALLENGE_FILE
        + ". The previous challenge can no longer enroll a backend; enrolled backends are unaffected.",
        NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int revoke(CommandSource source, VelocityServer server, String name) {
    boolean revoked;
    try {
      revoked = server.getDiscovery().revokeBackend(name);
    } catch (IOException failure) {
      source.sendMessage(Component.text("Unable to revoke " + name + ": "
          + failure.getMessage(), NamedTextColor.RED));
      return 0;
    }
    if (!revoked) {
      source.sendMessage(Component.text("No enrolled backend is named " + name + ".", NamedTextColor.RED));
      return 0;
    }
    source.sendMessage(Component.text("Revoked " + name
        + ". It must present a valid challenge to enroll again.", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }
}
