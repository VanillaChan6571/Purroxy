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
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Lets players save a preferred discovery region, or select auto to clear it. */
public final class RegionCommand {
  private RegionCommand() {
  }

  /** Creates the player-only region preference command. */
  public static BrigadierCommand create(VelocityServer server) {
    return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("region")
        .requires(source -> source instanceof Player && server.getDiscovery() != null)
        .executes(context -> {
          Player player = (Player) context.getSource();
          String preferred = server.getDiscovery().preferredRegion(player.getUniqueId());
          player.sendMessage(Component.text("Preferred region: "
              + (preferred.isEmpty() ? "auto" : preferred), NamedTextColor.YELLOW));
          return Command.SINGLE_SUCCESS;
        })
        .then(BrigadierCommand.requiredArgumentBuilder("region", StringArgumentType.word())
            .suggests((context, builder) -> {
              String prefix = builder.getRemainingLowerCase();
              java.util.stream.Stream.concat(java.util.stream.Stream.of("auto"),
                  server.getDiscovery().regions().stream()).distinct()
                  .filter(region -> region.startsWith(prefix)).sorted().forEach(builder::suggest);
              return builder.buildFuture();
            })
            .executes(context -> {
              Player player = (Player) context.getSource();
              String region = StringArgumentType.getString(context, "region").toLowerCase(Locale.ROOT);
              if (!region.equals("auto") && !server.getDiscovery().regions().contains(region)) {
                player.sendMessage(Component.text("Unknown network region: " + region, NamedTextColor.RED));
                return 0;
              }
              server.getDiscovery().setPreferredRegion(player.getUniqueId(), region)
                  .whenComplete((ignored, failure) -> player.sendMessage(Component.text(
                      failure == null ? "Preferred region saved: " + region : "Unable to save your region.",
                      failure == null ? NamedTextColor.GREEN : NamedTextColor.RED)));
              return Command.SINGLE_SUCCESS;
            })).build());
  }
}
