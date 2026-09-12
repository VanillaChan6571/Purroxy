# Seamless (no-reconfiguration) transfers

Status as of 2026-09-12: **implemented, unqualified.**
The switch completes without sending the client `JoinGame` or `Respawn`. No live client
has confirmed it. This document was written as scoping and is kept as the record of what
the work required; each section below now says what landed and what did not.

"Seamless" here means one specific thing: when a player switches backends, the
client never re-enters the configuration phase and never shows a loading screen.
The coordinated *data* handoff (position, orientation, velocity) already works
and is unrelated to this document.

## Verdict (original, and what happened)

The original verdict was **substantial and risky** — the scaffolding then in the tree
solved only the smallest part of the problem, proving two backends negotiated
identically, and none of the parts that keep a client alive across a switch.

That assessment held. The remaining parts were built: entity-ID negotiation across the
handoff protocol, a proxy-assigned player id block on the backend, source-side entity
teardown, and the conditional skip of `JoinGame`/`Respawn`. The client-version ambiguity
is now closed by consulting ViaVersion's original-protocol API when it is installed.
Registry identity in production and live-client behavior remain qualification risks.

Ownership of a player remains a separate concern from client continuity: the proxy
decides, and Nekopur enforces through a generation token and a durable journal.

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

All five now have an implementation. 1 and 3 are `SeamlessConfiguration` and the
detached handler; 2 is `tryDetachedConfiguration` returning before `doSwitch()`; 4 is the
`requestedEntityId` negotiation plus the backend id block; 5 is split between the proxy
(tab list, header/footer, bundle, boss bars) and Nekopur (entities). Only 3 remains a
standing production risk, because it depends on the hubs staying identical rather than on
code.

## What exists today

| Class | State |
|---|---|
| `SeamlessConfiguration` | Complete state machine. Fingerprints CONFIG as ordered `(packet class, SHA-256 of encoded payload)` pairs, fingerprints client-significant JoinGame state, and replays the client's known-packs reply against a second backend. Tags compare through a canonical encoding, so pure map-order differences no longer reject. |
| `ObservedConfigSessionHandler` | Wired in `LoginSessionHandler`, but only for backends in a `seamless-preferred`/`seamless-required` group (`DiscoveryService.captureSeamlessBaseline`). Records the baseline; forwards everything normally. |
| `SeamlessConfigSessionHandler` | **Wired in production** at `LoginSessionHandler.java:259`, through `tryDetachedConfiguration`. |
| `ClientPlaySessionHandler.handleBackendJoinGame` | Skips `JoinGame`/`Respawn` only when the destination took the detached path and both its entity ID and remaining JoinGame state match what the client already holds. |
| `HandoffCoordinator` | Carries `requestedEntityId` on `stage`, records what the destination reserved, and records `trackedEntities` from the fence reply. |

The whole proxy suite is 281 tests, 0 failures on JDK 25.0.3. The reset-skip predicate,
JoinGame equivalence, visible fallback and pre-JoinGame arrival approval now have direct tests.

Two hard limits are baked in:

- `Capture` is version-gated to **exactly protocol 776 (26.2)**
  (`SeamlessConfiguration.java:80`, and again at `:168` for replay). Any other client version can never
  produce a baseline.
- Equivalence is **byte-identity of the wire encoding**, including the `mc:brand`
  plugin message. Two backends running different server software or different
  patch releases can never match.

The capture cost is real but no longer wasted: every backend CONFIG packet is SHA-256
hashed and fully copied, and registry sync payloads are the largest packets in the
handshake. `captureSeamlessBaseline` restricts that to groups that actually opt into a
seamless mode, so ordinary `normal`/`off` groups do not pay it.

## Blockers

### 1. Entity IDs — **RESOLVED** (Nekopur `4f0f4e491`, Purroxy `b763a9f1`)

The predicted fix was the one taken: Nekopur accepts a *requested* entity ID. The proxy
sends the client's current id as `requestedEntityId` on `stage`; the destination reserves
it before the player's connection opens and reports back the id it will use.

The load-bearing addition was not in the request but in the allocator. Players are now
minted from a proxy-assigned block near the top of the int range
(`PairingStore.PLAYER_ID_BASE` 1,900,000,000, spaced 10,000,000 per backend) instead of
the world's shared entity counter, which vanilla burns through at one id per mob, arrow
and dropped item. Without that, a requested id is usually already taken by an NPC.

The proxy never has to trust the reservation: `handleBackendJoinGame` compares the
arriving `JoinGame` entity id against `lastKnownEntityId` and resets the client if they
differ, so a destination that cannot honour the request degrades instead of corrupting.

### 2. Source-server client state — **RESOLVED** (Purroxy `b763a9f1`, Nekopur `b8e5d2f9d`)

Handled, but split differently than this section assumed. The proxy-side pieces landed
where they were predicted: tab list in `handleBackendJoinGame`, header/footer and the
bundle session in `TransitionSessionHandler`, boss bars through `preparePlayReset()`
plus per-UUID removal on the detached branch.

The entity teardown did **not** become a proxy-side ledger. Nekopur's source clears its
own tracked entities at fence, from the entity tracker, so the set is exact rather than
enumerated by the proxy — including other players, whose entities are in the client's
table too. It reports the removed ids as `trackedEntities` on the fence reply.

`HandoffCoordinator.sourceEntities(UUID)` stores that list and is currently never read.
It exists so the proxy can take removal over if a source dying between fence and switch
turns out to matter in practice.

### 3. Registry byte-identity is brittle in production — **STILL OPEN** (severity: high)

`RegistrySyncPacket` carries the network-id to resource-key tables the client uses
to decode PLAY traffic: biome ids in chunk sections, `dimension_type`,
`chat_type`, `damage_type`, enchantment ids. One extra entry shifts ids and
produces wrong biome tint, wrong world height, broken chat, or a hard decode
failure. Byte-equality is the *correct* strictness here.

Realistic divergence sources between two same-build hubs: any datapack difference
including load order, and differing patch releases (Known Packs carries exact
version strings). Two hubs cloned from one image are fine; one hub where someone
dropped in a pack is silently incompatible.

### 4. ViaVersion client-version ambiguity — **RESOLVED FOR ELIGIBILITY**

This network runs ViaVersion 5.12.0, ViaBackwards 5.11.0, ViaRewind 4.1.3 and
Legacy-Support.

- Via rewrites the handshake protocol version to the backend's. Purroxy now detects an
  installed ViaVersion plugin and reflectively asks its API for the player's original
  protocol. Only original protocol 776 is eligible; unavailable inspection fails closed.
- If registries ever diverge and Via is translating a client newer than the
  backend link, **ViaVersion sends `START_CONFIGURATION` to the client itself**.
  The proxy cannot promise "the client never sees a reconfiguration" while a
  translator sits in the path.
- For pre-1.20.2 clients there is no config phase on the wire at all; ViaBackwards
  folds the registry into the legacy `JOIN_GAME`. Skipping reconfiguration
  suppresses the only packet that resets Via's per-connection registry and
  entity-tracker state, and the resulting corruption is silent.

Those translated-client cases are now routed through the visible path. ViaVersion is
still part of the live compatibility matrix for native 26.2 clients and is not declared
qualified by this code check alone.

### 5. Entity-ID negotiation at arrival — **RESOLVED**

The structural correction below was right, and the work it identified is done: Nekopur
honours a requested entity ID at arrival (blocker 1). The capability-fingerprint half is
**not** done — `HandoffCapabilities.matches()` still compares only the four strings, so
two replicas still cannot prove they are configuration-identical before commit. That is
what blocker 3 relies on the per-switch byte comparison to catch instead.

**Corrected.** An earlier revision of this document claimed the backend's
configuration phase had to be bypassed. That is wrong. `PrepareSpawnTask` runs in
the *backend server's* configuration phase for the connection from the proxy; the
player's client is a separate TCP connection. The backend's CONFIG phase is
structurally invisible to the client, and `SeamlessConfigSessionHandler` already
exists to absorb it on a detached connection without forwarding. The backend needs
no new admission path for this.

What the backend needed was the ability to honour a *requested* entity ID at
arrival, which is the other half of blocker 1, and which now exists.

## Prior art

The demonstration this was inspired by was located: a July 2026 Reddit post by
`/u/BENZOOgataga` crediting "Lodjo28". **The author explicitly declines to
disclose the technique**, and the post never states a Minecraft version — the
1.21.11 attribution could not be corroborated. No public open-source
implementation of PLAY-preserving backend switching was found. Everything about
*how* it might work is inference from the protocol, not observation of a working
system.

## Staged plan — outcome

The plan was not followed in order. Stages 2 and 3 landed together and stage 1 was
skipped, so the question stage 1 existed to answer cheaply — *do the two hubs ever
actually produce byte-identical negotiations in production?* — is still unanswered, and
is now answered only by whether live switches succeed or fall back.

**Stage 0 — resolve a stranded fence through ownership, not expiry. STILL OPEN.**
`BackendHandoff.isFrozen()` (`BackendHandoff.java:266`) still freezes a `FENCED` source
unconditionally, while `EXPORTED` self-releases at its deadline. The source is fenced
*before* the destination connection opens, so a proxy crash in that window still strands
a player frozen on the source. Live today in `normal` mode, and now also on the seamless
path.

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

**Stage 1 — instrument the fallback, wire nothing. SKIPPED.**
The diagnostic half exists (`ObservedConfigSessionHandler` logs `Seamless check:` lines
with the first differing packet), but it was never run as a standalone soak before the
switch was wired. The divergence-rate evidence it would have produced does not exist.

**Stage 2 — client-state teardown ledger. LANDED, differently.** See blocker 2.

**Stage 3 — backend PLAY admission + entity ID negotiation. LANDED, partially.**
Entity ID negotiation is done. The `HandoffCapabilities` configuration fingerprint is
not, so replicas still cannot prove identity before commit.

**Stage 4 — staff-gated canary. STILL OPEN.**
Nothing gates the seamless path by permission. It applies to every player in a
`seamless-preferred` group whose switch qualifies. If a canary is wanted, it has to be
added; the `purroxy.admin.wake` pattern is still the model, and the cohort — not the
backend — is the thing to canary on a two-hub network.

## Kill criteria

These were written before the work landed. Two now read as *roll back* rather than
*abandon*, since the code exists and `seamless-preferred` can be set back to `normal`
per group at any time. Retreat rather than push through if any of these hold:

- Stage 1 shows the two hubs do not produce byte-identical negotiations in
  ordinary operation, and the cause is not a one-off config mistake.
- The network keeps ViaVersion on the proxy for clients below the backend version.
  Blocker 4 makes the feature undeliverable for those players, and fails silently.
- Entity ID negotiation cannot be added to Nekopur's arrival path, i.e. blocker 1
  has no owner.
- Ghost entities or tab-list duplication still occur in live testing — that means the
  teardown is incomplete in ways neither side enumerates. This is now the first thing
  live testing should look for, because the teardown was split across two repositories
  and no test exercises the seam.

## What cannot be known without a live client

Unchanged by the implementation. Everything below is still open.

- Whether a real client tolerates PLAY-preserving switching at all in practice. No
  public implementation exists to learn from.
- Whether Via's per-connection state survives. Only testable with real legacy clients.
- The true divergence rate between the hubs as datapacks and builds drift.

This repository's tests cannot stand up a `VelocityServer`, let alone a Minecraft
client. Everything above stage 1 is unverifiable here.

## Provenance

Updated 2026-09-11 against `ab25cb90` (Purroxy) and `b8e5d2f9d` (Nekopur); resolved
blockers were re-checked against the source rather than against commit messages.

Originally produced by a six-dimension investigation with an adversarial verification pass.
`existing-code` was reviewed and found **sound**. `velocity-switch-path` was
reviewed and found **partly-wrong** — three claims were refuted, notably that the
proxy forwards the config stream verbatim (it rewrites brand and reconstructs
resource packs and cookies through events). `protocol-research`, `divergence`,
`via-and-backend` and `rollout` were **not** adversarially reviewed; treat their
specific claims as leads rather than settled fact. The stage 0 fence bug and the
26.2 version gate were verified by hand against the source.
