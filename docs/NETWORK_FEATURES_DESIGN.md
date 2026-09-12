# Purroxy network features design

Status: proposed, September 7, 2026. No runtime implementation in this change.

**This is the original design record and is deliberately not updated.** Much of it has
since been built and some of it was built differently — notably, the ordinary switch is
no longer the only path, and entity-ID rewriting was avoided by negotiating the id with
the backend rather than by accepting a client reset. For current behaviour see
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[HANDOFF_IMPLEMENTATION.md](HANDOFF_IMPLEMENTATION.md),
[DETACHED_CONFIGURATION.md](DETACHED_CONFIGURATION.md) and
[SEAMLESS_SCOPE.md](SEAMLESS_SCOPE.md).

All proxy functionality described here belongs in the Purroxy jar. Seamless
handoff additionally requires backend cooperation; a future Nekopur integration
can supply that directly in the backend jar. This design does not change Nekopur.

User terminology: Nekopurr sends its "resume" to Purroxy. The backend repository
is currently named `Nekopur`; the requested standalone configuration filename is
`Nekopurr.yaml`. This explicit configuration decision supersedes the earlier plan
to place these settings in Purpur configuration. This document remains design-only.

## Existing foundation

Purroxy already inherits Velocity's concurrent server registry, registration
events, asynchronous connection requests, and configuration reload.

- `proxy/.../VelocityServer.java`: `reloadConfiguration()` adds servers and
  replaces changed addresses, but does not unregister entries deleted from config.
- `proxy/.../server/ServerMap.java`: runtime registration/unregistration and
  case-insensitive lookup already exist. Unregistration is not evacuation.
- `proxy/.../command/builtin/ServerCommand.java`: `/server` resolves one literal
  server name; it has no group selection.
- `proxy/.../connection/client/ConnectedPlayer.java`: fallback iteration caches
  server names. Dynamic removal needs missing entries skipped and routing refreshed.
- `proxy/.../connection/backend/TransitionSessionHandler.java`: disconnects the
  old backend at join, before completing the new connection.
- `proxy/.../connection/client/ClientPlaySessionHandler.java`: the ordinary switch
  sends join/respawn packets, accepting a client reset to avoid entity-ID rewriting.
- `proxy/.../connection/backend/ConfigSessionHandler.java`: modern protocol
  configuration and resource-pack negotiation are part of switching.

The paths above abbreviate `src/main/java/com/velocitypowered/proxy` as
`proxy/...` after the module prefix. Purroxy uses JDK 25 and has existing JUnit
tests; the workspace's plugin-specific Java/test conventions do not apply here.

## 1. Backend discovery and automatic synchronization

Server membership is driven by backend handshakes and leases. There are no manual
server add/remove commands and no manually maintained discovered-server list.
Either a backend plugin or a built-in Nekopur adapter implements the same versioned
protocol. The adapter connects to Purroxy when the backend starts, advertises its
Minecraft endpoint and assigned network game mode, and keeps its status synchronized.

Here, `network-game-mode` means a routing category such as `hub` or `gamelobby-x`;
it is separate from a player's survival/creative game mode. The backend deployment
assigns this value in adapter configuration. Purroxy validates the assignment
against that backend identity's allowed categories and existing proxy groups.
Normalize case for lookup, so `Hub` matches `hub`. An unknown group rejects routing
registration and reports `UNKNOWN_GROUP` to the backend, proxy console, and online
admins with `purroxy.notifications.discovery`. Include authenticated server identity
and attempted group; rate-limit repeated alerts. Do not create the group implicitly.

### Control connection and registration

The configured Purroxy endpoint defaults to port 25565, as requested. Design a
versioned control handshake on that same proxy listener: a bounded initial protocol
discriminator selects ordinary Minecraft handling or the authenticated TLS control
path. Keep malformed/slow control handshakes isolated from player connections and
test compatibility with the existing connection pipeline before implementation.
Do not bind a second socket to the same address/port or silently require 25580.
Backend identities are mutually authenticated. The backend initiates a persistent
connection, so discovery works with zero online players. This is not a player-carried
plugin-message channel. The backend's advertised Minecraft endpoint is separate
from the configured Purroxy destination, even when both use port number 25565.

The initial handshake carries protocol version, stable server ID/display name,
process incarnation ID, advertised Minecraft host/port, network game mode, map,
player safe limit, region, Minecraft protocol version, readiness, actual player
count, hard admission capacity, and supported capabilities. This is its resume.
Purroxy authenticates the identity, checks allowed endpoints/categories, validates
port range and names, and returns a session ID, lease generation, heartbeat interval,
and lease duration. Suggested initial timings: heartbeat every 5 seconds, lease
expiry after 20 seconds; these are tunable design defaults.

An advertised host is necessary across machines or NAT; a port alone is insufficient.
Reject wildcard bind addresses as advertised endpoints. Bind endpoint authorization
to backend identity to prevent registrations from directing the proxy to arbitrary
hosts. Verify Minecraft reachability asynchronously before marking a backend ready.
Successful reachability alone does not establish identity or readiness.

Heartbeats renew the lease and report readiness/load; sequenced updates carry
metadata changes. Reject stale sessions, replayed updates, duplicate live identities,
and name collisions. Serialize registry changes and publish immutable routing
snapshots. Session generations ensure a delayed disconnect cannot remove a newer
registration. Reconnects use bounded exponential backoff with jitter and a full
state advertisement; Purroxy restart rebuilds discovery from fresh handshakes.

### Lifecycle and removal

Lifecycle: `REGISTERING -> READY -> DRAINING -> REMOVED`, with `SUSPECT` for lost
control connectivity. Only READY instances with valid leases accept new routing.
On a detected control disconnect, mark SUSPECT immediately; lease expiry handles
silent failures. Existing Minecraft connections remain usable while connected,
but new admissions stop. Reauthentication and a fresh readiness check restore READY.

For graceful shutdown the adapter sends DRAIN before backend teardown. Purroxy
stops admissions, cancels/rechecks pending admissions, and evacuates players through
the shared group/fallback resolver. A group destination excludes the source.
Purroxy acknowledges drain completion only after players and pending admissions
reach zero; the backend can then withdraw and stop. The adapter must initiate this
before a blocking shutdown hook, while the backend can still process transfers.
An abrupt stop or exhausted shutdown grace period follows crash recovery semantics;
graceful evacuation cannot be guaranteed after the process has stopped.

On lease expiry, remove routing membership automatically. Retain an internal
retired record for existing connections until they leave, and attempt evacuation
without silently kicking healthy connections. If the Minecraft connection also
fails, use ordinary kick fallback or disconnect with a clear reason if none exists.
Unregister the physical server when its connections and pending admissions are
gone. Never retain a stale record as an eligible route.

Changing the advertised endpoint or network game mode requires drain and a new
registration generation. Do not change the meaning of an occupied server in place.
A restarted process cannot replace a still-live incarnation with the same identity;
first retire the old session and resolve its connected players.

### Configuration ownership and compatibility

`purroxy-network.toml` stores discovery listener/trust settings, allowed categories,
group selection policies, fallbacks and forced hosts. Adapter configuration stores
backend identity, advertised endpoint and assigned category. Discovered instances,
leases, observed health and reservations are transient; never restore them as READY
from disk. Diagnostic history may be retained separately.

Without discovery enabled, preserve Velocity behavior. In discovery mode, move
fallback and forced-host policy to the network config explicitly; static Velocity
entries may coexist as static-owned servers but never become discovered group
members automatically. Reject duplicate names across static, discovered, and
third-party plugin-owned entries. Reload does not import or delete live discoveries.
An empty group is valid during startup and reports temporary unavailability.

Validate configuration reloads as a complete snapshot; invalid policy keeps the
previous snapshot active. Revoked identities/categories immediately lose admission
and enter retirement/drain. Preserve Velocity registration events and API behavior.
A central admission gate covers commands, fallback, plugin/API requests, and
in-flight connection commit. Do not block a player's Netty event loop on probes,
control I/O, or evacuation. Log registration, expiry, rejection and drain outcomes
without credentials. An optional read-only status view shows discovery state.

## 2. Grouped destinations

Use logical destinations such as `hub` and `gamelobby-x` that resolve to physical
backends. These are routing groups, unrelated to Minecraft's clickable web links.

Proposed proxy policy (illustrative TLS paths contain no credentials):

```toml
format-version = 1
fallback = ["hub"]

[discovery]
enabled = true
share-player-listener = true # Uses the proxy listener, default port 25565.
certificate-file = "purroxy.crt"
private-key-file = "purroxy.key"
backend-ca-file = "backends-ca.crt"

[backend-identities.hub-1]
allowed-game-modes = ["hub"]
allowed-endpoints = ["10.0.0.11:25565"]

[groups.hub]
network-game-mode = "hub"
strategy = "least-safe-limit-utilization"
transfer-mode = "normal"

[forced-hosts]
"play.example.com" = ["hub"]
```

Requested backend settings in standalone `Nekopurr.yaml` (proposed key spelling):

```yaml
purroxy:
  ip: 127.0.0.1 # Replace with the reachable Purroxy IP or hostname.
  port: 25565

resume:
  network-gamemode: Hub
  map: none
  player-safe-limit: 50
  region: US-West
```

`0.0.0.0` is an example bind address, not a usable configured Purroxy destination;
reject it with a specific configuration error. The resume defaults above follow
the user's requested values. Credentials and stable identity are provisioned
separately; they are not inferred from a claimed resume name. Advertise the actual
Minecraft port from `server.properties`, with an explicit reachable-host/port
override for NAT. Do not confuse that port with `purroxy.port`.

The adapter checks that its advertised port maps to its actual listening service;
deployments using NAT provide the proxy-reachable mapping. Stable identity is
provisioned separately from per-process incarnation and negotiated session IDs.

`/server hub` selects an eligible hub; `/server hub-2` remains an explicit
physical destination. Tab completion and the clickable list include permitted
groups. Membership is derived automatically from authenticated READY registrations
whose assigned network game mode matches the group's policy. There are no member
add/remove commands. A new hub instance joins `hub` without editing its member list;
identity provisioning still controls who may join. No nested groups initially.

Rules:

1. Server and group names share a case-insensitive namespace; collisions fail.
2. Within the preferred region when available, choose the lowest safe-limit
   utilization, including pending reservations, with rotating ties. For equal
   safe limits this is least-players selection. See capacity policy below.
3. Filter non-READY, lease-expired, unhealthy, and hard-capacity-exhausted members.
   Prefer members below their safe limit; use overflow fallback as defined below. Health uses
   bounded asynchronous probes with backoff; a ping is advisory, not admission.
   Unknown servers receive bounded probes before becoming group candidates.
4. Reserve capacity during selection and release it on every completion, timeout,
   disconnect, or cancellation. Backend kicks remain authoritative; optional
   configured capacity limits are local to this proxy unless coordinated later.
5. If already on an eligible member, `/server hub` reports that rather than
   shuffling the player. Drain excludes the source explicitly.
6. On transport failure try another unvisited member, under a total deadline and
   attempt bound. Never retry plugin cancellation or an admission/policy rejection.
   Direct server requests never silently select another server.
7. Resolve fresh membership for each request and recheck eligibility at commit.
   A new player request cancels the previous routing operation.
8. Login, forced hosts, kick fallback, drain, and `/server` share the resolver.
   Keep the existing `getServer(name)` API physical-only; expose a separate
   Purroxy destination API rather than fake `RegisteredServer` instances.

Retain `velocity.command.server` semantics. Optional per-destination permissions
can be added without enabling new access restrictions by default. Existing
connection events still see the selected physical backend. Recheck a plugin's
redirected target for drain/capacity and seamless compatibility.

### Map policy

`map: none` leaves the ordinary default overworld/world and plugin-managed world
behavior intact. It does not force a world named literally `world`, nor teleport
every player on join. It does not disable compatibility checks for hub handoffs.

For `map: Christmas`, the backend resolves `Christmas` inside its world container,
uses the loaded world or loads the existing valid world, prepares the landing
chunks, and directs arrivals there. A directory alone does not prove readiness.
Do not generate a new map silently when missing. Proposed failure policy: report
`MAP_UNAVAILABLE`, alert admins, and keep the instance out of automatic routing
until the requested world is usable. Reject traversal/absolute paths.

Use a normal warp/transfer to that world's spawn for a different map; preserve
coordinates and orientation only for a verified compatible same-map handoff.
Resolve the destination before client activation to avoid a brief appearance in
the wrong world. Coordinate plugin world hooks through an explicit adapter/event;
do not fight plugins with repeated teleports. Map changes drain the old routing
generation, prepare the replacement, and publish readiness only after success.

### Safe limit and waking spare servers

`player-safe-limit: 50` is a positive automatic-routing target, not the hard player
maximum. Track effective load as authoritative backend players plus reservations
not yet reflected in that count; acknowledge admissions to avoid double counting.
At load 50, prefer another READY instance below its safe limit for automatic requests
such as `/server hub`. If none is available, continue automatic connections to the
least-utilized eligible instance above its safe limit, up to its hard admission
limit. This overflow fallback also applies while a spare is still waking or its
wake attempt has failed; an unavailable spare must not unnecessarily block arrivals.
Explicit `/server hub-1` and authorized forced transfers may also exceed 50, but still
respect readiness, access checks and the backend's hard admission limit (for example
100 from `server.properties`). Reaching the safe limit does not kick existing users.

When the sole READY instance in a group exceeds 70% of its safe limit, request
wake for the next eligible sleeping instance. With limit 50, load 35 does not
trigger; load 36 does. Prefer a compatible spare in the same region. This is an
early wake threshold, not the cutoff for routing to the existing instance.

State flow for a spare: `SLEEPING -> WAKING -> READY`. Waking is idempotent, with
one outstanding request per capacity need, deadlines and retry backoff. A wake
acknowledgment is not readiness: wait for its fresh resume, usable map, and admission
readiness before routing players. Keep sleep records distinct from expired/dead
registrations. If no spare exists or waking fails, notify admins and continue
connections using overflow fallback when necessary. Reserve slots and recheck hard
capacity at admission, including concurrent requests. Only report capacity-related
unavailability when all otherwise eligible instances are at their hard limits.
For example, one READY hub with safe limit 50 and hard limit 100 continues receiving
automatic arrivals at 50 through 99 players; a request at effective load 100 fails
or selects another eligible instance. No existing player is kicked to make room.

Notify the proxy console and online admins with `purroxy.notifications.capacity`
when the wake threshold is crossed without an available spare, waking fails, or
overflow routing begins. Include group/region, current load, safe/hard limits and
the action to start another instance in Pterodactyl. Deduplicate and rate-limit
alerts per capacity incident, with recovery notification once sufficient READY
capacity returns. Do not broadcast an alert for every connecting player.

Admins provision/start additional servers through Pterodactyl. On startup, their
Nekopurr resume automatically adds them to routing once READY. Purroxy only wakes
already-running sleeping instances; automatic provisioning, panel/Wings integration,
and modifications to Wings are outside the current scope.

The requested initial rule covers the sole READY instance. Proposed extension for
larger groups: wake one spare when aggregate eligible load exceeds 70% of aggregate
safe capacity, counting in-progress wake requests to avoid a startup storm. Region
preferences should also consider whether a region has spare capacity before
scaling elsewhere. Automatic scale-down/sleep is outside the initial scope.

Confirmed wake mechanism: Nekopurr stays running in sleep mode with worlds paused.
Its independent control loop retains the authenticated connection and renews its
lease while reporting SLEEPING, so it can receive WAKE without world ticks. Do not
schedule wake processing exclusively on the paused tick loop. A sleeping instance
is a known spare, not a READY route; its registration can exist without accepting
players. Wake resumes world processing and prepares the configured map/landing
chunks before reporting READY. Direct selection of a sleeping instance may request
wake and wait under a deadline, but never connects a player into a paused world.

Only empty instances with zero pending admissions/handoffs may enter sleep. Define
how backend tasks/plugins pause before implementing sleep; discovery heartbeats
and wake handling must remain live. A control disconnect still expires its lease;
after reconnect it advertises its actual sleep/readiness state rather than becoming
READY automatically. A stopped/crashed process is unavailable, and starting stopped
processes through a host agent is outside this design. No shell commands supplied
by a resume are executed.

### Region preference

Resume region defaults to `US-West`. Purroxy stores a player's optional preferred
region by UUID and exposes a proposed `/region <region|auto>` preference command.
First select members below their safe limit, preferring the chosen region within
that set. If no member has safe headroom, use overflow fallback, again preferring
the chosen region. Tell the player when a fallback region is selected. Region
preference never bypasses hard capacity, readiness or permissions. `auto` initially selects without a region
preference; region labels and proxy-to-backend latency are not measurements of
player-to-region ping. Automatic ping-based regional choice needs separate measurement.
An explicit server selection takes precedence over region preference.

## 3. Coordinated and seamless transfers

Two separate capabilities must be validated: moving authoritative player data,
and retaining the client's rendered world. A normal proxy connection already
keeps the client connected to the proxy, but that alone does not remove loading UI.

### Supported scope

First implement coordinated data handoff using the normal visual switch. Then
prototype seamless mode for identical hub replicas using a native vanilla 26.2
client and 26.2 Nekopurr backends. The user confirmed 26.2 as the current backend
version. Legacy/modded clients, active protocol translation, and Bedrock bridges
use normal switching until separately qualified.

ViaVersion/ViaBackwards compatibility is a separate test matrix. ViaBackwards
5.10.0 introduced 26.2 server support; its 5.11.0 release notes include fixes for
26.2 block rendering and registry compatibility. These establish ordinary protocol
support, not support for Purroxy's proposed seamless handoff. Translation changes
the client-visible representation; the design must account for translator state
as well as backend state before bypassing normal join/configuration resets.

Initially allow seamless negotiation only on the qualified native 26.2 path.
Installing Via plugins does not itself prove that translation is active: inspect
the actual client/backend protocol path and translator placement (proxy or backend).
Native connections with Via installed require their own regression test. Unknown
or translated paths use normal transfers in preferred mode, or fail before commit
in required mode. Resume capabilities must include backend translator presence;
the proxy must also inspect its local connection integration rather than relying
only on backend claims. Do not infer native clients from backend protocol alone.

Before expanding support, test each client version, exact Via builds and deployment
placement for repeated same-map transfers, entities, chunks, inventory, chat and
registry/resource-pack state. Matching translator versions on both backends is
necessary configuration evidence, not proof of seamless compatibility. Discovery,
group routing and sleep/wake do not depend on enabling seamless translation.

Seamless mode requires an authenticated backend capability handshake and matching
protocol, registry/tag data, dimension definition, resource-pack state, map revision,
and coordinate system. Configure a shared world identity: matching world names
alone is insufficient. Identical static hubs are the first target; continuously
replicating changing worlds, entities, or block edits is out of scope.

Proposed modes: `normal`, `seamless-preferred`, and `seamless-required`.
Preferred mode falls back before commit if compatibility fails; required mode
reports failure and leaves the player on the source. Neither mode promises to
keep a disconnected source alive after a crash.

### Backend handoff contract

Add a versioned backend adapter, ideally built into Nekopur in a later, separate
module task. A plugin adapter could support coordinated data handoff on Paper,
but may lack the internals required for seamless mode.

Extend the discovery control connection with separately negotiated handoff
capabilities; discovery support alone does not imply seamless support. Bind every
transfer to player UUID, source, destination, unique transfer ID, expiry, and an
ownership generation. Do not accept handoff instructions from client plugin messages.

Proposed flow:

1. Reserve destination capacity and preload its landing chunks while the source
   continues serving the player.
2. Freeze source mutations briefly, close interactive containers, and export a
   versioned snapshot. Source remains the owner until commit.
3. Destination validates and stages the snapshot without ticking or saving the
   player as an active owner. It acknowledges readiness and compatibility.
4. Record the ownership transition durably, fence the source's writes, activate
   the destination, and switch packet routing under a bounded commit window.
5. Acknowledge destination activation, release source resources and the reservation.

Before commit, abort discards staged data and unfreezes the source. After commit,
the destination owns the player; do not blindly reactivate a stale source. Lost
acknowledgments query the durable transfer record and retry idempotently. Crash
recovery must fence old writers before player rejoin. A durable journal and
backend enforcement are required; a timeout alone cannot prevent duplicate items.
Start with one coordinating proxy; multiple proxies need shared ownership storage.

Initial snapshot scope: world identity, position/yaw/pitch, velocity, game mode,
inventory/armor/offhand/selected slot, health, food/saturation, XP, effects, and
flight state. Explicitly define exclusions and cross-world conversion rules.
Persistent custom plugin data requires versioned adapter participation; arbitrary
plugin memory cannot be transferred by the proxy. Shared economies/permissions
remain owned by their existing services. Reject vehicles, open transactions, or
unsupported state until their handoff semantics are implemented.

For the requested hub-to-hub case, the first profile preserves world identity,
coordinates, yaw/pitch and the client state necessary for continuity between matching
hub replicas. Purroxy coordinates an authoritative source snapshot and destination
application; it does not guess coordinates from incomplete packet observation.
Gameplay inventory/XP transfer is opt-in per profile, never inferred from sharing
`Network Gamemode: Hub`. Identical map names alone do not prove identical content.
The desired visible result is remaining at the same location without a loading
screen; a normal cross-map warp is a separate path. The referenced video has not
been inspected, so its protocol/version/backend requirements are not assumed.

### Client continuity prototype

Do not simply suppress respawn packets in the existing switch. Prototype a
separate negotiated path that keeps the client in its current play world while
preparing the destination backend independently. It must reconcile player entity
ID, other entities, chunks/light, inventory window/state IDs, teleport confirms,
keepalives, scoreboard/tab list, boss bars, abilities, and signed-chat state.
Either the backend adapter preserves IDs or every relevant packet is translated.
Unknown packets must trigger rejection before commit, not silent forwarding.

The current transition handler disconnects the source early; a new coordinator
must keep it available until the negotiated handoff commits. Bound staged packets
and timeouts so a slow backend cannot consume unbounded memory.

Acceptance requires actual client recordings showing no loading screen for the
supported pair, preserved position/data, and no ghost entities or stale chunks.
Until that succeeds, seamless mode remains experimental and disabled by default.
Backend cooperation is necessary for this design but does not itself prove that
every client version can avoid a loading screen.

## Implementation order and verification

1. Discovery protocol, registry, backend adapter contract, policy migration, drain,
   and admission gate. Implement the adapter in a separate module task. Test invalid
   certificates/endpoints/categories, duplicate identities, stale updates, expiry,
   plugin-owned collisions, removal during connection, and proxy/backend restarts.
   Smoke-test zero-player startup registration, heartbeat loss, reconnect and shutdown.
2. Group resolver and all routing entry points. Test ties/reservations, unavailable
   members, cancellation, redirects, category changes, stale membership, fallback
   loops, and policy reloads. Verify new READY instances join groups automatically.
   Verify safe-limit loads 35/36/49/50, automatic overflow at 50/99 and rejection at
   hard capacity 100, direct requests above 50 and at hard capacity, concurrent slot
   reservations, duplicate wake signals, no-spare/waking/failed-wake cases, alert
   deduplication and recovery, return to normal routing after a new instance becomes
   READY, region fallbacks, unknown
   group alerts, missing maps, and map readiness before first arrival. Test the shared
   default-25565 listener with ordinary clients and malformed control connections.
3. Backend contract and coordinated handoff with normal switching. Inject crashes
   before/after commit and lost acknowledgments; verify one owner and no duplicated
   inventory, lost snapshots, or permanently frozen players.
4. One-version seamless prototype, then a documented compatibility matrix. Test
   repeated transfers, resource-pack/registry mismatch, client disconnects, latency,
   packet loss, entities, chunks, containers, chat, and interrupted handoffs.

Each implementation phase runs relevant JUnit tests and Gradle quality checks on
JDK 25, followed by live proxy/backend verification. This design-only change needs
no jar build and makes no claim that runtime behavior has been tested.

The first supported protocol target is 26.2. Before phase 3, settle whether every
backend can run Nekopur and which custom player-data providers require handoff support.
These do not block implementing registry management and grouped routing.

## References

- [ViaBackwards releases](https://github.com/ViaVersion/ViaBackwards/releases)
  document 26.2 support and subsequent translation fixes; no claim here treats
  those releases as verification of Purroxy's custom transfer path.

- Local source files listed above are the basis for current behavior.
- [Velocity configuration](https://docs.papermc.io/velocity/configuration/)
  documents existing server, fallback, and forced-host settings.
- [Player information forwarding](https://docs.papermc.io/velocity/player-information-forwarding/)
  covers connection identity forwarding; the proposed gameplay snapshot is a
  separate backend contract.
- [Plugin messaging](https://docs.papermc.io/velocity/dev/plugin-messaging/)
  documents message-source handling relevant to avoiding client impersonation.
