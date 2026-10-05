# Purroxy

Purroxy is a Minecraft proxy fork of [Velocity](https://github.com/PaperMC/Velocity) that adds dynamic backend discovery, group and region routing, and coordinated hub/lobby transfers. It is licensed under GPLv3.

## What it does

- Discovers and registers authenticated backends without maintaining a static server list.
- Routes players to server groups using region preferences, readiness, and available capacity. `/server <group>` selects a backend; `/region <region|auto>` selects a preferred region.
- Coordinates backend wake, sleep, drain, and admission handling. A configurable limbo hold keeps players connected to the proxy while a backend becomes available.
- Transfers a player's position, rotation, and velocity between matching hub replicas using a coordinated handoff.
- Attempts seamless transfers between compatible replicas: the client stays in PLAY without a JoinGame/Respawn reset or loading screen. If compatibility checks fail, `seamless-preferred` falls back to an ordinary visible transfer.

The hub-position profile does **not** synchronize inventories, XP, plugin data, or arbitrary world changes. Participating replicas must already contain the same map.

## Backend support

Use [Nekopur](https://github.com/VanillaChan6571/Nekopur) for backend discovery and coordinated/seamless handoff support. Purroxy's custom handoff requires cooperating backends; stock Paper, Purpur, or Velocity support alone does not provide it.

Enable authenticated discovery, enroll the backends, and configure matching map IDs and revisions. Both hubs should use matching Nekopur builds, datapacks, registries, and client-significant configuration.

See [coordinated handoff setup](docs/HANDOFF_IMPLEMENTATION.md) for pairing, backend settings, and recovery behavior.

## Seamless version checklist

A checked version below means **implemented and eligible**, subject to the compatibility checks. It does not mean every version has completed live qualification. Only **26.2** is currently qualified by default; every other listed protocol requires explicit canary opt-in and live testing.

- [x] **26.2** — protocol **776**; qualified, no canary opt-in required.
- [x] **26.3** — protocol **777**; canary opt-in. Includes post-effects comparison and preservation of unchanged login effects.
- [x] **26.1 / 26.1.1 / 26.1.2** — protocol **775**; canary opt-in.
- [x] **1.21.11** — protocol **774**; canary opt-in.
- [x] **1.21.9 / 1.21.10** — protocol **773**; canary opt-in.
- [x] **1.21.7 / 1.21.8** — protocol **772**; canary opt-in.
- [x] **1.21.6** — protocol **771**; canary opt-in.
- [x] **1.21.5** — protocol **770**; canary opt-in.
- [x] **1.21.4** — protocol **769**; canary opt-in.
- [x] **1.21.2 / 1.21.3** — protocol **768**; canary opt-in.
- [x] **1.21 / 1.21.1** — protocol **767**; canary opt-in.
- [x] **1.20.5 / 1.20.6** — protocol **766**; canary opt-in.
- [x] **1.20.3 / 1.20.4** — protocol **765**; canary opt-in.
- [x] **1.20.2** — protocol **764**; canary opt-in.
- [x] **1.20 / 1.20.1** — protocol **763**; canary opt-in. Successful 1.20.1 round trips are documented, but the family remains outside the default qualified set.
- [x] **1.16.4 / 1.16.5** — protocol **754**; canary opt-in.

**Unsupported for seamless transfers:** 1.17–1.19.4 (protocols 755–762), versions below 1.16.4, and protocols absent from this build's eligibility table. Adding an unsupported protocol to the canary list does not enable it. This checklist describes seamless eligibility, not ordinary proxy login compatibility.

### Canary opt-in

In `purroxy-network.toml`, add protocol numbers at the **TOML root**, before any table headers. For example, to test 26.3:

```toml
seamless-canary-protocols = [777]

[discovery]
enabled = true

[groups.hub]
handoff-mode = "seamless-preferred"
```

This is a partial configuration example; retain the rest of your discovery/pairing and group settings. Multiple canaries can be listed, for example `[763, 775, 777]`. Canary opt-in is network-wide and disabled by default.

On participating Nekopur backends, enable transfers, use matching `transfers.map-id` and `transfers.map-revision`, and set `transfers.sync-arrival-position: false` for the seamless path. Restart the proxy and reconnect clients to capture a fresh configuration baseline.

Test repeated transfers in both directions, chat, entities/NPCs, inventory display, effects, and failure recovery. Configuration differences, changed entity IDs, unsupported translation, or unproven chat continuity can cause a visible fallback. `seamless-required` currently rejects requests; use `seamless-preferred`.

See [detached configuration and live checks](docs/DETACHED_CONFIGURATION.md) and [seamless scope and testing history](docs/SEAMLESS_SCOPE.md). Runtime eligibility is defined in [SeamlessProtocols.java](proxy/src/main/java/com/velocitypowered/proxy/connection/backend/SeamlessProtocols.java).

## Building

Use **JDK 25** and the Gradle wrapper. To build the runnable proxy jar:

```sh
./gradlew :velocity-proxy:shadowJar
```

On Windows, use `gradlew.bat`. The runnable artifact is `proxy/build/libs/velocity-proxy-<version>-all.jar`; the plain jar does not include the runtime dependencies.

To run the proxy and API tests:

```sh
./gradlew :velocity-proxy:test :velocity-api:test
```

`./gradlew build` runs the full build cycle, including checks.

## Running

Copy the `-all.jar` to the proxy directory and launch it with JDK 25:

```sh
java -jar velocity-proxy-<version>-all.jar
```

Purroxy generates `velocity.toml` and `purroxy-network.toml`. Discovery and handoff are disabled by default; configure them before expecting dynamic routing or seamless transfers.

Purroxy remains under active development. Canary support and passing automated tests do not replace live verification on your network.
