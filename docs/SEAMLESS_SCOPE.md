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

The whole proxy suite is 317 tests, 0 failures on JDK 25.0.3. The reset-skip predicate,
JoinGame equivalence, visible fallback, pre-JoinGame arrival approval, committed-owner recovery
and the canonical registry comparison all have direct tests.

Two limits shape what can ever match:

- Eligibility is decided in one place, `SeamlessProtocols`. Protocol 776 (26.2) is qualified and
  always eligible. Anything else from 764 (1.20.2) upward is a canary: off unless an operator
  lists it in `seamless-canary-protocols`, and eligible only because they have qualified it
  themselves. Below 764 nothing is eligible however it is configured. Protocol 775 (26.1.x)
  has been taken through a live two-hub switch in both directions.
- Equivalence is **byte-identity of the wire encoding**, including the `mc:brand`
  plugin message, with one deliberate exception. For `RegistrySyncPacket` the acceptance check
  is a canonical SHA-256 that ignores NBT compound-key order and nothing else: registry
  identifier, entry count and order, data-presence flags, tag types and values, list order and
  array contents all still have to match exactly. A payload that cannot be canonicalised safely
  - malformed, truncated, duplicate-keyed, or of an unknown tag type - produces no canonical
  digest at all and so falls back to byte identity. Two backends running different server
  software or different patch releases still cannot match.

  This exception exists because two servers were observed describing `minecraft:worldgen/biome`
  with identical values in a different key order, which no client can observe across a seamless
  switch: none of that configuration is forwarded, and a registry's numeric ids come from entry
  order rather than from key order inside an entry.

The capture cost is real but no longer wasted: every backend CONFIG packet is SHA-256
hashed and fully copied, and registry sync payloads are the largest packets in the
handshake. `captureSeamlessBaseline` restricts that to groups that actually opt into a
seamless mode, so ordinary `normal`/`off` groups do not pay it.

## 1.20.2 canary (764)

Added 2026-09-15. The implemented floor is now 764. Add it to the root
`seamless-canary-protocols` list, restart the rebuilt proxy, and reconnect to capture a baseline.
Accepted canaries log a WARN at startup; this is an opt-in experiment, not automatic qualification.
Unknown protocol numbers and versions without the configuration path or packet mappings remain
blocked. A warning alone cannot supply missing encoders or a configuration phase.

764 uses the legacy no-known-packs capture and byte-identical whole-registry comparison introduced
for 765. Its PLAY ids are add entity `0x01`, experience orb `0x02`, remove entities `0x40`,
objective `0x5A`, team `0x5C`, tags `0x70`, checked against
[ViaVersion's 1.20.2 enum](https://github.com/ViaVersion/ViaVersion/blob/master/common/src/main/java/com/viaversion/viaversion/protocols/v1_20to1_20_2/packet/ClientboundPackets1_20_2.java).
Live 764 switching has not yet been verified. The user confirmed successful 1.20.3 and 1.20.4
switching; the supplied 765 log shows both hub directions keeping PLAY and clearing 14 entities.

## Extending below 26.1 — the 766-776 band

Extended 2026-09-15. The band `SeamlessProtocols` will admit is now 765 (1.20.3/1.20.4) through 776
(26.2), one entry per protocol number:

| 765 | 766 | 767 | 768 | 769 | 770 | 771 | 772 | 773 | 774 | 775 | 776 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1.20.3/4 | 1.20.5/6 | 1.21/.1 | 1.21.2/3 | 1.21.4 | 1.21.5 | 1.21.6 | 1.21.7/8 | 1.21.9/10 | 1.21.11 | 26.1.x | 26.2 |

Two bands cannot be split, because Velocity gives each one enum constant: 773 covers 1.21.9 *and*
1.21.10, 767 covers 1.21 *and* 1.21.1. Qualifying either qualifies both patch releases.

**765 canary added 2026-09-15.** The implemented floor is now 1.20.3/1.20.4 (765).
Set `seamless-canary-protocols = [765]` at the TOML root (or append 765 to the existing list).
A proxy rebuild/restart and fresh client login are required to collect a baseline.
This path captures the complete configuration without a known-packs exchange and compares the
whole-registry packet byte for byte. It deliberately does not use the 1.20.5 registry parser;
registry ordering differences therefore fall back to a visible switch. Protocol 766+ still
requires its known-packs selection. Protocol 765 remains unqualified pending live round trips.

The 765 PLAY ids are add entity `0x01`, experience orb `0x02`, remove entities `0x40`,
objective `0x5C`, team `0x5E`, and tags `0x74`, verified against
[ViaVersion's 1.20.3 packet enum](https://github.com/ViaVersion/ViaVersion/blob/master/common/src/main/java/com/viaversion/viaversion/protocols/v1_20_2to1_20_3/packet/ClientboundPackets1_20_3.java).

**What the comparison did *not* need.** The configuration packet classes carry no version branches
at all, and every fingerprint already encodes at the negotiated protocol, so the machinery spans
the original 766-776 band unchanged. The 765 extension changes the known-packs requirement
and uses byte equality for its older registry format. What was 26.2-specific was the gates around it.

**What changed to make the band expressible:**

- `SeamlessProtocols` holds a qualified *set* rather than one constant, plus a `FLOOR` and a table
  of the clientbound PLAY `update_tags` packet id per protocol. A protocol with no id in that table
  cannot be made eligible, because the invalidation below would silently never fire.
- `BackendPlaySessionHandler` no longer watches for 26.2's packet ids. **This was a live defect, not
  only an obstacle to extending**: the PLAY-phase baseline invalidation was gated on
  `protocol == MINECRAFT_26_2`, so 775 — already running as a canary — had none at all, and a
  backend changing tags mid-PLAY left a stale baseline for the next switch to compare against.
  Worse, two of the three ids it watched (`0x88`, `0x89`) were unreachable: report details and
  server links are *registered* in PLAY from 1.21, so they decode by type and never arrive at
  `handleUnknown`. They are now caught by type, and PLAY tags by a per-protocol id. Nothing is read
  at all when there is no baseline left to invalidate.
- `CanonicalRegistry` refused only below 1.20.2, but the shape it parses is the 1.20.5 one. 1.20.2
  and 1.20.3/4 carry every registry as one named root tag; walking that as an identifier and entry
  list could produce a digest that means nothing. It now refuses below 1.20.5.
- A canary entry that names no protocol, or one below the floor, is logged and dropped instead of
  silently ignored, and the resolved set is reported once at startup. A non-numeric entry names
  itself in the error rather than surfacing as a cast failure.

**The PLAY `update_tags` ids**, since the whole invalidation depends on them being right. Taken
from the vanilla clientbound ladder and cross-checked against the one neighbour this proxy itself
registers — `custom_report_details` sits exactly two ids above `update_tags` at every version in
the band, and `StateRegistry`'s own PLAY registrations (0x7A at 1.21, 0x81 at 1.21.2, 0x86 at
1.21.9, 0x88 at 26.1) agree with the table on all four:

| 766 | 767 | 768-772 | 773-774 | 775-776 |
|---|---|---|---|---|
| 0x78 | 0x78 | 0x7F | 0x84 | 0x86 |

1.20.5 has no `custom_report_details` to check against; there the ladder simply ends one packet
later, at `projectile_power`.

**Verified here:** 402 tests, 0 failures on JDK 25.0.3, with Checkstyle and Spotless clean. New
coverage spans the whole band: the floor cannot be configured past, an eligible protocol always has
a tags id, the canonical digest is identical at every protocol in the band, a capture replays
against itself at each one, a listed protocol takes the detached path and one below the floor does
not, and the tags id of one version is not mistaken for another's.

**Not verified here, and not verifiable here — this is the whole of the remaining investigation:**

1. **Where ViaVersion actually sits.** The refusal log already prints all three numbers. On a
   downlevel client, if client / proxy-negotiated / destination-link are the same number, Via is
   translating on the backend and this works as designed. If the client number differs from what
   the proxy negotiated, Via is rewriting the handshake on the proxy and `LoginSessionHandler`'s
   original-protocol check will refuse every downlevel client by design — that refusal is
   load-bearing and must not simply be deleted. Read the logs before soaking anything.
2. **Whether a Via-translated 26.2 backend still produces a capturable configuration phase** at
   each band: known-packs offer, client reply, at least one registry sync, active features, tags,
   finish. Arm the observer without listing the protocol, and read the `Seamless check:` lines.
   This is the standalone soak that was skipped for 776 and 775. Do not skip it a third time.
3. **Whether ViaBackwards' output is byte-stable across the two hubs.** Byte identity now compares
   Via's translation on hub-1 against Via's translation on hub-2, so both hubs must run identical
   Via builds and identical Via configuration. This divergence source does not exist at 776.
4. **Whether the registry-identifier prefix assumption holds** at 774 and 766. A wrong prefix read
   is safe — it degrades to byte identity — but every switch would then fall back.
5. **Whether ViaBackwards ever sends `START_CONFIGURATION` to the client itself** on this path. For
   766+ the client has a real configuration phase, so this is less likely than the pre-1.20.2 case,
   but a loading screen appearing while the proxy logs a seamless outcome is exactly that symptom.
6. **Config packets outside the allow-list.** `SeamlessConfiguration.supported()` does not admit
   `CodeOfConductPacket` (1.21.9+) or `DialogClearPacket`/`DialogShowPacket` (1.21.6+). Any of them
   in the destination's configuration phase invalidates the capture and forces the visible path —
   safe, but it means a hub with a code of conduct never gets a seamless switch. Code of conduct
   would need its accept reply stored and replayed the way known-packs already is.

**Soak order: 774, then descending.** One protocol at a time; 775's soak is the only precedent and
it was a single number. Both directions, several round trips, then the things only a human eye
catches — no loading screen, chat, tab list, boss bars, inventory display, held item, titles,
effects, NPC entities, and no ghost entities from the source. Then deliberately diverge one test
hub and confirm exactly one logged fallback. Promote into the qualified set in `SeamlessProtocols`
only after that, in its own commit.

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

- **Corrected 2026-09-14 by measurement.** This document previously stated that Via rewrites
  the handshake protocol version to the backend's, so that the proxy sees the backend version.
  It does not. With a 26.1.2 client the proxy logs client protocol 775, proxy-negotiated 775
  and destination link 775: Velocity negotiates the client's own protocol throughout, and Via
  translates on the backend link. Purroxy still asks Via's API for the original protocol and
  requires it to equal what was negotiated, so a translated connection is never taken for a
  native one; unavailable inspection fails closed. Eligibility is no longer limited to 776 -
  see `SeamlessProtocols` and `seamless-canary-protocols`.
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

Partly answered on 2026-09-14 by a live two-hub network. The rest is still open.

- ~~Whether a real client tolerates PLAY-preserving switching at all in practice.~~ **Answered:**
  native 26.2 and translated 26.1.2 clients both switch hubs with no JoinGame, no Respawn, no
  reconfiguration and no loading screen, in both directions.
- ~~Whether Via's per-connection state survives.~~ **Answered for 775:** a translated 26.1.2
  client completes the detached negotiation and keeps its world across repeated switches. Not
  answered for any older family, and ViaBackwards' emulated config phase remains the risk.
- The true divergence rate between the hubs as datapacks and builds drift.

This repository's tests cannot stand up a `VelocityServer`, let alone a Minecraft
client. Everything above stage 1 is unverifiable here.

## Provenance

Updated 2026-09-14 against `7122ac65` (Purroxy) and `be2bf4934` (Nekopur). Resolved blockers
were re-checked against the source rather than against commit messages, and the claims marked
answered above were observed on the live network rather than reasoned from the code.

Originally produced by a six-dimension investigation with an adversarial verification pass.
`existing-code` was reviewed and found **sound**. `velocity-switch-path` was
reviewed and found **partly-wrong** — three claims were refuted, notably that the
proxy forwards the config stream verbatim (it rewrites brand and reconstructs
resource packs and cookies through events). `protocol-research`, `divergence`,
`via-and-backend` and `rollout` were **not** adversarially reviewed; treat their
specific claims as leads rather than settled fact. The stage 0 fence bug and the
26.2 version gate were verified by hand against the source.

## Protocol 763 debugging session

Set the root `seamless-capture-player` to the testing player's UUID, restart the rebuilt proxy,
and reconnect with 1.20.1. Visit hub-1, switch to hub-2, then back after each hub has finished
loading. Collect `Legacy PLAY debug` lines. Protocol 763 remains outside the seamless canary band;
adding it to `seamless-canary-protocols` is unnecessary for diagnostics and still warns/refuses.

The selected player's JoinGame is compared before normal transfer handling: registry NBT equality,
full encoded JoinGame equality excluding entity id, and retained entity id. Tags (`0x6E`) and enabled
features (`0x6B`) are observed during PLAY and compared byte for byte with the previous backend's
last observed values. IDs were enumerated from the provided decompiled 1.20.1 client
`ConnectionProtocol`: the bundle packet takes `0x00` and the 110 `addPacket` calls follow it.
Initial observations say `no baseline`. Missing packets never imply equality. Output is capped at
32 matching packet observations per backend visit, with a 512 KiB payload comparison limit.

These checks do not authorize suppression. Normal JoinGame/Respawn transfers still run. Matching
registry NBT alone does not imply identical wire encoding; chat sessions, entity cleanup, initial
packet ordering, and other client state still require implementation and validation. The observer
keeps only two packet hashes per backend visit plus bounded JoinGame comparison state and clears
on disconnect or capture selection changes. Packet payloads are not printed.

### First live run, 2026-09-15

A 1.20.1 client on hub-1, then `/server hub-2`, both hubs freshly started. What the run established:

- **JoinGame is identical across hubs apart from the entity id.** 37908 bytes encoded,
  `registrySemanticMatch=true` and `fullJoinExceptIdMatch=true`. The handoff coordinator had
  already reserved the source's entity id at the destination, so `idKept=true` as well. This is
  the result the legacy path depends on and it held.
- **Enabled features matched byte for byte** (60 bytes, one packet per visit).
- **Tags were not observed**, and the packet the run labelled `enabled-features` was
  `teleport_entity`. Its `previousBackendMatch=false` was per-entity movement differing between
  two different worlds, which carries no compatibility meaning. The id table was wrong: `0x68`
  and `0x6B` instead of `0x6B` and `0x6E`. Corrected the same day; **the tags comparison still
  has no live evidence behind it.**

The mistake is worth keeping in view: a wrong id does not fail loudly here, it silently compares
whatever else happens to occupy that number and reports a confident `false`. Any id added to this
observer has to be enumerated from the decompiled registration order, not recalled.

### Second live run, 2026-09-15 — corrected ids

hub-1 → hub-2 → hub-1, both hubs freshly started, ids corrected.

- JoinGame held again in both directions: 37908 bytes, `registrySemanticMatch=true`,
  `fullJoinExceptIdMatch=true`, `idKept=true`.
- Enabled features matched in both directions (60 bytes).
- **Tags are 33689 bytes on every visit and the wire digest differs every time.** Identical length
  with a differing hash is the signature of a reordered map, not different tags.

That is the same phenomenon the configuration path already handles at 766+: `fingerprint` compares
`TagsUpdatePacket.encodeCanonical`, and `ObservedConfigSessionHandler` reports a canonical match
with a differing wire digest as "matched after normalizing tag-map order". The PLAY form is the
same wire shape, so the observer now decodes it through the same encoder and reports
`orderNormalisedMatch` beside the raw `previousBackendMatch`. A payload that will not decode, or
that leaves trailing bytes, reports `unavailable` - never a match.

**Not yet established:** whether the reorder is per-backend or per-observation. The observer only
compares against the previous backend, so hub-1's first visit was never compared against its own
second visit. Until that is known, a reorder cannot be attributed to the two hubs differing.

### Third live run, 2026-09-15 — order normalisation confirmed

hub-1 → hub-2 → hub-1. `update-tags` reported `previousBackendMatch=false,
orderNormalisedMatch=true` in **both** directions, with 33689 bytes every time. Tags agree; only
their map order differs, exactly as at 766+.

**All three observable preconditions for a 763 legacy path now hold:**

| Check | Result |
|---|---|
| JoinGame excluding entity id | byte-identical, both directions |
| Entity id retention | kept (handoff coordinator reserves it at the destination) |
| Enabled features | byte-identical, both directions |
| Tags | identical after order normalisation, both directions |

## Why 763 is still blocked: the chat session

The data preconditions being met does not make the switch safe, and the blocker is now specific
rather than a caveat. From the decompiled 1.20.1 `ClientPacketListener`:

- `handleLogin` (the JoinGame handler) sets `chatSession = null`, replaces `lastSeenMessages` with
  a fresh `LastSeenMessagesTracker(20)` and `messageSignatureCache` with a default, then - only if
  the connection is encrypted - calls `prepareKeyPair().thenAcceptAsync(this::setKeyPair)`.
- `setKeyPair` sends `ServerboundChatSessionUpdatePacket`, **but only when `chatSession == null`
  or the key pair changed**. The null assignment in `handleLogin` is what makes that condition
  true.

So the client's re-announcement of its chat session is *caused by* the JoinGame this path would
suppress. Suppress it and `chatSession` stays non-null, `setKeyPair` is never reached, and the
destination never learns the player's session. The proxy cannot cover for it today either:
`StateRegistry` registers `SessionPlayerChatPacket` and `SessionPlayerCommandPacket` serverbound
but not the chat-session update, and `RemoteChatSession` exists only inside the clientbound
`UpsertPlayerInfoPacket`. Nothing retains the client's session for replay.

Two distinct problems follow, and the second is the hard one:

1. **The destination has no public key for the player.** Addressable: register the serverbound
   chat-session update, retain the last one per player, and replay it to the destination before
   releasing its traffic.
2. **The signature chain and index space desync.** Suppressing JoinGame deliberately leaves the
   client's `lastSeenMessages` and `messageSignatureCache` intact, while the destination starts
   empty. The first signed message then carries indices the destination cannot resolve, which is a
   chat validation disconnect rather than a degraded experience. There is no packet that resets
   client chat state without also resetting the world.

Until (2) has an answer, a 763 legacy path is only safe where signed chat is not in play - secure
chat disabled, or chat routed so the destination never validates a chain it did not start.

### Why this network is the case where it is not in play

Signature verification is off on Neko Network by policy - messages are not verified for privacy
reasons, and moderation is handled by a plugin instead. That is exactly the configuration (2) is
safe in, and the decompiled client says why precisely rather than approximately:

- `lastSeenMessages.addPending` has one caller, `markMessageAsProcessed`, and it runs only for a
  **non-null signature**.
- `messageSignatureCache.push` happens only in `handlePlayerChat`.
- `handleSystemChat` (`0x64`) and `handleDisguisedChat` (`0x1B`) mutate **neither**.

So if no signed `player_chat` (`0x35`) is ever delivered, both trackers stay at their initial
state for the whole session and a suppressed JoinGame leaves nothing stale. Problem (2) does not
need solving here, it needs *confirming*. Problem (1) likewise stops mattering: with secure
profiles unenforced a player with no chat session chats unsigned and the destination accepts it.

**This is a property of the deployment, not of the protocol.** One plugin routing chat through the
signed path repopulates the trackers and the desync returns. The observer therefore counts
`player_chat` per backend visit and reports `playerChatOnPreviousVisit` on the next transfer.
Absence is the load-bearing direction: presence does not prove a signature was attached, but
absence proves the trackers never moved. Any real legacy path must gate on this, not assume it.

### Fourth live run, 2026-09-15 — the invariant does not hold as stated

The first chat run reported `playerChatOnPreviousVisit=0`, but no chat had been sent, so the zero
was vacuous. Repeated with actual messages: **`player_chat` (`0x35`) is delivered on both hubs**,
`playerChatOnPreviousVisit=1` in both directions. Chat here is not routed as system chat.

That settles the coarse question and raises the precise one. `ClientboundPlayerChatPacket` carries
a **nullable** signature - the 1.20.1 record reads sender UUID, VarInt index, then
`readNullable(MessageSignature::read)` - and it is the signature, not the packet, that feeds
`markMessageAsProcessed` and so the client's `lastSeenMessages`. An unsigned `player_chat` leaves
both trackers untouched and the invariant survives; a signed one does not.

The observer now reads that presence flag from its bounded prefix and reports `ofThoseSigned` and
`unreadable` alongside the count. An unreadable prefix is never counted as unsigned.

### Fifth live run, 2026-09-15 — signatures are present

`signaturePresent=true` on both hubs; `ofThoseSigned=1, unreadable=0` on the transfer following
each chat. **Chat on this network is signed, and the client's trackers do accumulate.**

The expectation was wrong for a specific reason worth writing down: *not enforcing* signatures is
not the same as *not producing* them. `enforce-secure-profile=false` means the server does not
**require** a client key. A client that has one still signs its outbound messages, and Paper still
relays that signature to the other clients, so `player_chat` arrives signed regardless of the
server's verification policy. Privacy policy governs whether signatures are checked, not whether
they exist on the wire.

So problem (2) is real here after all. Suppressing JoinGame would leave the client holding a
populated `lastSeenMessages` and `messageSignatureCache` while the destination starts empty; the
first chat message after the switch carries indices the destination cannot resolve, and vanilla
answers that with a `chat_validation_failed` disconnect. It would strike exactly the players who
talk before switching, and never appear in a silent switching test.

Three ways out, none of which is a proxy fix:

1. **Nekopur `afterArrival` resync** - realign or reset the arriving player's chat chain instead
   of disconnecting. The hook already exists and already reconciles mob effects for precisely this
   "client kept its own world" case.
2. **Stop propagating signatures** - have the moderation plugin consume chat and re-emit it as
   system chat. Removes the desync at the source, and matches a policy that already declines to
   verify signatures. Costs the client-side "reportable" indicator, which this network does not
   use.
3. **Gate at runtime** - fall back to a visible switch for any player who has received signed
   chat. Safe by construction, but it degrades exactly the active players the feature is for.

### Sixth live run, 2026-09-15 — tracing the chat frame across the handoff

"None of the solutions are at the proxy" was wrong. `ChatQueue.ChatState` is a deliberate **mirror
of the client's secure-chat frame**: `ClientPlaySessionHandler.handle(JoinGamePacket)` discards it
with the comment "the client will do this too", and `discardChatQueue`'s contract names the reset
condition as "whenever the client resets its own 'last seen' state". The proxy already rewrites
forwarded offsets through `updateFromMessage`/`accumulateAckCount`. Two findings from the trace:

**`discardChatQueue` never fires on a server switch.** No trace line appeared across two switches.
Backend JoinGame reaches `TransitionSessionHandler.handle(JoinGamePacket)` and goes to
`handleBackendJoinGame`; the client handler's `handle(JoinGamePacket)` is a *serverbound* handler
and a client never sends one. So the mirror is not reset at a switch - it re-converges only
because the client resets itself on JoinGame and sends an empty acknowledged set, which
`updateFromMessage` copies in. A seamless path therefore has nothing to suppress: the work is the
opposite one, actively translating a retained client frame into the destination's empty one.

**No drift, but the sample never left the trivial regime.** At each boundary the mirror held the
source hub's frame (`acknowledgedBits=1`), and the first packet after arrival pulled it back to 0.
`forwardedOffset` equalled `clientOffset` every time, because `delayedAck` stayed 0 throughout -
and **no `clientAcknowledgement` packet was seen at all**, since the client only sends one once
its offset exceeds 64. Signed commands do carry the frame (`/store` forwarded `clientOffset=1`).

So the rewrite machinery that a handoff would depend on - held-back acknowledgements,
`DUMMY_LAST_SEEN_MESSAGES` substitution past `WINDOW_SIZE` - **was never executed**. The absence of
drift here is not evidence that it survives a handoff; it is evidence that nothing was stressed.
A verdict needs a run with enough chat volume to push the window past 64.


### Seventh live run, 2026-09-15 - unsigned delivery, first case passes

`chat.disguise-outgoing` enabled on both hubs (Nekopur `sendPlayerChatMessage` routes through
`sendDisguisedChatMessage`). AzukiChanny on 776 received chat on hub-2 for eight minutes, then
`/server hub-1`:

- `Seamless switch to hub-1: keeping the client's world, no reset sent.`
- Chatted twice after arrival, `clientOffset=0, forwardedOffset=0`. **No kick.**
- Their `acknowledgedBits` stayed 0 for the whole hub-2 visit. Before the change the same
  account climbed 6 -> 16 -> 20 (saturating at `WINDOW_SIZE`).

The histogram shows the mechanism rather than inferring it: on hub-1 at 776, `0x21 x100(max
391B)` - `disguised_chat` - and **no `0x41` at all**, where `0x41` is `player_chat`. Chat is
being delivered, and it is being delivered without a signature, so neither side's history
advances and a destination starting with an empty validator has nothing to reject.

The proxy gate agreed independently: it counts *delivered signed* chat, counted none, and so did
not decline the transfer. That is a second signal from a different mechanism.

**Not yet established.** ViaVersion translation of disguised chat to 763 is unconfirmed -
VanillaChanny chatted throughout with `acknowledgedBits=0` and no errors, but no post-change
histogram exists for that connection, so what their client actually receives has not been seen.
Signed commands across a transfer, acknowledgements during transfer (needs offset > 64),
repeated round trips, deletion, formatting and filtering all remain untested. The mitigation
gate stays up until they pass.


**ViaVersion translation confirmed (same session).** VanillaChanny on 763 crossed hub-2 -> hub-1
after the same eight minutes of chat: `playerChatOnPreviousVisit=0, ofThoseSigned=0`, and the
histogram reads `0x1B x100(max 468B)` with **no `0x35`**. At 763 `0x1B` is `disguised_chat` and
`0x35` is `player_chat`, so ViaVersion translates the disguised form down intact - 100 messages on
both sides of the translation, matching AzukiChanny's `0x21 x100` at 776. The 763 client received
every message and its tracker never moved.

**One matrix item is now unreachable rather than untested.** The client only emits
`ServerboundChatAckPacket` once its offset exceeds 64, and offset only grows from *signed*
deliveries. With none arriving, that packet cannot occur, so "acknowledgements during transfer"
has no condition left to test. That is the hazard being removed rather than passed, and it should
be read that way: if signed delivery is ever re-enabled on a hub, the case returns untested.


### Eighth live run, 2026-09-15 - 763 seamless, both directions

With 763 listed in `seamless-canary-protocols`, a native 1.20.1 client took hub-1 -> hub-2 -> hub-1:

```
Seamless switch to hub-2: keeping the client's world, no reset sent.
Seamless switch to hub-1: keeping the client's world, no reset sent.
```

Both with `idKept=true, registrySemanticMatch=true, fullJoinExceptIdMatch=true`, and
`playerChatOnPreviousVisit=0, ofThoseSigned=0` so the chat gate did not decline. Tags came back
`orderNormalisedMatch=true` in both directions, as they had all session.

**The encoder risk cleared itself.** The teardown wrote `remove_entities` with the client still in
PLAY and cleared 14 then 15 entities without an exception, which is what the 763 `0x3E` mapping
existed to make safe. `set_player_team` `0x5A` is separately corroborated: it appears in the
histogram as `0x5A x5(max 99B)`, the form the backend actually sends at 763.

**One id remains unexercised.** Every capture reported `0 objectives and 0 teams`, so
`set_objective 0x58` has never been written or observed - it rests on enumeration alone. It is
encode-only and written mid-switch, so if it is wrong the failure is an encoder exception that
drops the player, not a silent no-op. A hub that shows a scoreboard objective to a 763 player who
then switches seamlessly is the untested case.

763 differs from every other protocol here in what proves equivalence: there is no configuration
phase, so `matchesSeamlessJoin` compares the destination's JoinGame against the one actually
installed in the client, and the registry rides inside it. Tags and features arrive in PLAY and
are forwarded normally. `VelocityServerConnection.isLegacySeamlessArrival()` stands in for
`isDetachedConfiguration()`, which can never be true below 1.20.2.


### Ninth live run, 2026-09-15 - set_objective exercised

A debug sidebar objective was added to Nekopur (`debug.scoreboard`, default off,
`org.nekopur.network.DebugScoreboard`) purely so the teardown would have an objective to clear.
Nothing on this network displayed a scoreboard, which is why every earlier capture read
`0 objectives and 0 teams` and left `set_objective` resting on enumeration alone.

With it enabled on both hubs:

- 763, hub-2 -> hub-1: `cleared 15 entities (...), 1 objectives and 0 teams`, then
  `Seamless switch to hub-1: keeping the client's world, no reset sent.` No kick.
- 776, hub-2 -> hub-1: `cleared 14 entities (...), 1 objectives and 0 teams`, same result.

**The id is now observed, not inferred.** The 763 histogram for that visit carries `0x58 x1(max
39B)`, appearing exactly when the objective was introduced, alongside `0x51 x1(max 13B)`
(`set_display_objective`) and `0x5B x1(max 23B)` (`set_score`). `0x58` is what the table claimed
and what the backend actually sent, and the teardown wrote it to a client in PLAY without an
encoder exception.

Every clientbound id the 763 seamless path depends on now has live corroboration:
`add_entity 0x01`, `remove_entities 0x3E`, `set_objective 0x58`, `set_player_team 0x5A`,
`player_chat 0x35`, `enabled_features 0x6B`, `update_tags 0x6E`.

**Team teardown is deliberately left untested.** `0x5A` is corroborated as an incoming packet,
but the teardown has never written it, because nothing on this network registers a team - so the
write path cannot execute and testing it now would only prove the test fixture works.

That trigger is likelier than it sounds, and it is not a decision anyone will consciously make:
scoreboard plugins routinely register teams for nametag prefixes and colours, so the first such
plugin deployed to a hub activates this write path on its own. At that point an untested
encode-only write becomes live, and it fails the way a wrong id does - dropping the player
mid-switch rather than glitching. `debug.scoreboard` is kept in the build precisely so that case
can be reproduced deliberately rather than discovered in production.

Also untested: signed commands across a seamless switch, message deletion, and plugin chat
formatting or filtering.
