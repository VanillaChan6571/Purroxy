# Scoping: finishing seamless (no-reconfiguration) transfers

Status: **scoping only — nothing here is implemented.**

"Seamless" here means one specific thing: when a player switches backends, the
client never re-enters the configuration phase and never shows a loading screen.
The coordinated *data* handoff (position, orientation, velocity) already works
and is unrelated to this document.

## Verdict

**Substantial and risky.** Not a wiring exercise. The scaffolding in the tree
(`SeamlessConfiguration`, `SeamlessConfigSessionHandler`, `ObservedConfigSessionHandler`)
solves the smallest part of the problem — proving two backends negotiated
identically — and none of the parts that actually keep a client alive across a
switch. The blockers below are implementation requirements, not proofs of
infeasibility; note also that four of the six investigations behind this document
were never adversarially reviewed, so several are leads rather than settled facts.

Ownership of a player is a separate concern from client continuity, and is
largely already built: the proxy decides, and Nekopur enforces through a
generation token and a durable journal. That machinery removes a stale session.
It does not preserve the client's world view — that is the work below.

## What seamless actually requires

A client leaves PLAY only when the server sends `start_configuration`, `login`
(play) or `respawn`. Each clears the level and draws the loading screen. So a
seamless switch means sending none of them, which forces the proxy to:

1. absorb the destination's configuration phase itself, forwarding nothing to the client
2. keep the client's `ClientPlaySessionHandler` and PLAY state intact
3. guarantee the destination's registries, tags and feature flags are *byte-identical*
   to what the client already holds — there is no protocol mechanism to update
   synchronized registries or feature flags while in PLAY
4. reconcile entity IDs, including the player's own
5. tear down every piece of source-server client state by hand

Points 1 and 3 are what the existing code addresses. Points 2, 4 and 5 are not
started.

## What exists today

| Class | State |
|---|---|
| `SeamlessConfiguration` | Complete state machine. Fingerprints a CONFIG negotiation as ordered `(packet class, SHA-256 of encoded payload)` pairs and replays the client's known-packs reply against a second backend. |
| `ObservedConfigSessionHandler` | Wired in production (`LoginSessionHandler.java:162`) whenever discovery is enabled. Records the baseline. Forwards everything normally. |
| `SeamlessConfigSessionHandler` | Written and unit-tested. **Never instantiated outside tests.** |

11 tests pass in `SeamlessConfigurationTest`. The handler is un-wired, not
untested — the qualification harness already exists, which lowers the cost of
stage 1 below.

Two hard limits are baked in:

- `Capture` is version-gated to **exactly protocol 776 (26.2)**
  (`SeamlessConfiguration.java:74-76`). Any other client version can never
  produce a baseline.
- Equivalence is **byte-identity of the wire encoding**, including the `mc:brand`
  plugin message. Two backends running different server software or different
  patch releases can never match.

There is also a live cost with no benefit: with discovery enabled, every backend
CONFIG packet is SHA-256 hashed and fully copied on every switch, for a consumer
that does not exist. Registry sync payloads are the largest packets in the handshake.

## Blockers

### 1. Entity IDs — not solvable proxy-side (severity: high)

The client keeps the source's player entity ID; the destination addresses it by
another. Status effects, held-item sync, mounting and anything targeting the
player by ID either no-op or land on an unrelated entity. Velocity deliberately
has no entity-rewriting layer, so this cannot be fixed in the proxy without
building one. The alternative is for Nekopur to accept a *requested* entity ID at
arrival, which it currently cannot do.

Recovery for the player: relog. The proxy cannot detect it.

### 2. Source-server client state — guaranteed breakage (severity: high)

Everything `doSwitch()` currently delegates to the config round trip has no
seamless equivalent: tab list, player-list header/footer, boss bars, the
`spawned` flag, bundle session, title reset. Skip the round trip and the player
keeps frozen copies of the source's players, NPCs and armour stands, a doubled
tab list, and the source's boss bars pinned permanently.

Guaranteed on every seamless switch, not probabilistic. It is also the largest
body of work that is *entirely proxy-side and unit-testable*, which makes it the
natural first real stage.

### 3. Registry byte-identity is brittle in production (severity: high)

`RegistrySyncPacket` carries the network-id to resource-key tables the client uses
to decode PLAY traffic: biome ids in chunk sections, `dimension_type`,
`chat_type`, `damage_type`, enchantment ids. One extra entry shifts ids and
produces wrong biome tint, wrong world height, broken chat, or a hard decode
failure. Byte-equality is the *correct* strictness here.

Realistic divergence sources between two same-build hubs: any datapack difference
including load order, and differing patch releases (Known Packs carries exact
version strings). Two hubs cloned from one image are fine; one hub where someone
dropped in a pack is silently incompatible.

### 4. ViaVersion makes the promise unkeepable (severity: high for this network)

This network runs ViaVersion 5.12.0, ViaBackwards 5.11.0, ViaRewind 4.1.3 and
Legacy-Support.

- Via rewrites the handshake protocol version to the backend's, so the
  `MINECRAFT_26_2` gate **cannot see a translated client** when Via is on the proxy.
- If registries ever diverge and Via is translating a client newer than the
  backend link, **ViaVersion sends `START_CONFIGURATION` to the client itself**.
  The proxy cannot promise "the client never sees a reconfiguration" while a
  translator sits in the path.
- For pre-1.20.2 clients there is no config phase on the wire at all; ViaBackwards
  folds the registry into the legacy `JOIN_GAME`. Skipping reconfiguration
  suppresses the only packet that resets Via's per-connection registry and
  entity-tracker state, and the resulting corruption is silent.

### 5. Entity-ID negotiation at arrival (severity: medium)

**Corrected.** An earlier revision of this document claimed the backend's
configuration phase had to be bypassed. That is wrong. `PrepareSpawnTask` runs in
the *backend server's* configuration phase for the connection from the proxy; the
player's client is a separate TCP connection. The backend's CONFIG phase is
structurally invisible to the client, and `SeamlessConfigSessionHandler` already
exists to absorb it on a detached connection without forwarding. The backend needs
no new admission path for this.

What the backend does need is the ability to honour a *requested* entity ID at
arrival, which is the other half of blocker 1. Separately,
`BackendHandoff.capabilities()` carries no registry/tag/feature fingerprint, so two
replicas cannot prove they are configuration-identical before commit —
`HandoffCapabilities.matches()` compares four strings.

## Prior art

The demonstration this was inspired by was located: a July 2026 Reddit post by
`/u/BENZOOgataga` crediting "Lodjo28". **The author explicitly declines to
disclose the technique**, and the post never states a Minecraft version — the
1.21.11 attribution could not be corroborated. No public open-source
implementation of PLAY-preserving backend switching was found. Everything about
*how* it might work is inference from the protocol, not observation of a working
system.

## Staged plan

Each stage must leave the tree shippable and be independently verifiable.

**Stage 0 — resolve a stranded fence through ownership, not expiry.**
`BackendHandoff.isFrozen()` freezes a `FENCED` source unconditionally, while
`EXPORTED` self-releases at 25s. The source is fenced *before* the destination
connection opens, so a proxy crash in that window strands a player frozen on the
source. Live today in `normal` mode.

**Do not add an expiry to `FENCED`.** A timeout would let the source unfreeze while
the destination may already be committed, producing two backends that both believe
they own the player — worse than one frozen player, and it would defeat the
property the fence exists to provide. Nekopur already stamps a monotonic generation
into the player's saved data (`BackendHandoff.GENERATION_KEY`) and resolves
ownership in `prepareArrival(player, savedGeneration)`; that fencing token is the
correct mechanism.

The real gap is liveness, not safety: nothing resolves the fence if the
coordinating proxy never returns. The fix belongs in explicit ownership
resolution — the proxy as authority, queried on reconnect or by an operator —
not a timer.

**Stage 1 — instrument the fallback, wire nothing.**
Make `seamless-preferred` attempt a baseline comparison on a detached backend
connection and log the outcome, while still performing a normal visible switch.
This answers the only question that matters cheaply: *do your two hubs ever
actually produce byte-identical negotiations in production?* If they don't,
stages 2+ are moot. A day, low risk, no player-visible change.

**Stage 2 — client-state teardown ledger.**
Build the seamless equivalent of everything `doSwitch()` delegates: tab list,
header/footer, boss bars, titles, bundle state, `spawned`. Proxy-side and
unit-testable against the existing harness. Multi-day, medium risk. Still off.

**Stage 3 — backend PLAY admission + entity ID negotiation.**
Nekopur gains an arrival path outside `PrepareSpawnTask` and the ability to honour
a requested entity ID; `HandoffCapabilities` gains a configuration fingerprint so
replicas can prove identity before commit. Multi-day, high risk, touches the patch
pipeline.

**Stage 4 — staff-gated canary.**
Enable behind a permission, matching the `purroxy.admin.wake` pattern. A two-hub
network cannot canary half of itself, so canary the *cohort*, not the backend. Any
failure must fall back to a normal visible switch before the source is fenced.

## Kill criteria

Abandon rather than push through if any of these hold:

- Stage 1 shows the two hubs do not produce byte-identical negotiations in
  ordinary operation, and the cause is not a one-off config mistake.
- The network keeps ViaVersion on the proxy for clients below the backend version.
  Blocker 4 makes the feature undeliverable for those players, and fails silently.
- Entity ID negotiation cannot be added to Nekopur's arrival path, i.e. blocker 1
  has no owner.
- Stage 2 lands and ghost entities or tab-list duplication still occur in testing —
  that means the teardown ledger is incomplete in ways the proxy cannot enumerate.

## What cannot be known without a live client

- Whether a real client tolerates PLAY-preserving switching at all in practice. No
  public implementation exists to learn from.
- Whether Via's per-connection state survives. Only testable with real legacy clients.
- The true divergence rate between the hubs as datapacks and builds drift.

This repository's tests cannot stand up a `VelocityServer`, let alone a Minecraft
client. Everything above stage 1 is unverifiable here.

## Provenance

Produced by a six-dimension investigation with an adversarial verification pass.
`existing-code` was reviewed and found **sound**. `velocity-switch-path` was
reviewed and found **partly-wrong** — three claims were refuted, notably that the
proxy forwards the config stream verbatim (it rewrites brand and reconstructs
resource packs and cookies through events). `protocol-research`, `divergence`,
`via-and-backend` and `rollout` were **not** adversarially reviewed; treat their
specific claims as leads rather than settled fact. The stage 0 fence bug and the
26.2 version gate were verified by hand against the source.
