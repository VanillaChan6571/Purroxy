# Detached configuration and the seamless switch

Updated 2026-09-12.

This build negotiates the destination's CONFIG phase on the backend connection only,
keeping the client in PLAY throughout. When the destination also hands back the entity
ID and client-significant JoinGame state the client already holds, the client is sent
neither `JoinGame` nor `Respawn`, which removes the loading screen. Any weaker outcome
degrades to Velocity's ordinary visible switch rather than breaking the client.

`seamless-required` is still rejected outright in `HandoffCoordinator.prepareActive`.
The switch below is reached only through `seamless-preferred`.

## Selection

The destination group must use `handoff-mode = "seamless-preferred"`. Both Nekopur
instances must have compatible enabled `hub-position` handoff capabilities with
matching map identity and revision. Purroxy must have durably committed this
player's handoff to that destination. Discovery registration and a matching
diagnostic log alone do not meet these requirements.

Both the client and backend connection must use native 26.2, the client connection
must be classified as vanilla and still in PLAY, the source must remain active,
and a complete baseline from a successful normal configuration must exist.
Other connections use the existing normal path. When ViaVersion is installed, Purroxy
also asks its API for the player's original protocol and requires native protocol 776;
if that inspection is unavailable or fails, seamless mode fails closed. Other packet
plugins are still outside this qualification and native clients must be tested first.

## Packet flow and fallback

After authenticated backend login, Purroxy acknowledges login and installs
`SeamlessConfigSessionHandler` on the destination. It sends client settings and
replays the previously selected known-packs response. Configuration packets are
validated privately. The source connection remains connected until destination
JoinGame arrives. Once CONFIG matches, Purroxy durably approves seamless arrival on
the destination before acknowledging configuration finish; until then the backend's
safe default is to retain its ordinary arrival teleport. No client `StartUpdatePacket`
is sent on the matching path.

A mismatch, unknown exchange, timeout or changed source/client baseline closes the
probe. Before retrying destination login once through ordinary configuration, Purroxy
durably marks the committed destination arrival as visible. That preserves its normal
position teleport instead of combining a client reset with a suppressed arrival sync.
The retry does not re-export player data or roll back committed ownership. If either
the marker or normal login fails, the existing recovery path handles the committed owner.

Normal baselines publish only after the corresponding connection becomes current,
including completion of an in-place reconfiguration. Native PLAY tag/report/link
updates and backend resource-pack requests invalidate old captures. Changes during
detached negotiation force fallback rather than using an obsolete baseline.

## Entity IDs

The client keeps the entity ID the source gave it, so the destination must agree to
use the same one or the client has to be reset.

`DiscoveryService.prepareHandoff` reads the id from the live source connection, or
from `ConnectedPlayer.lastKnownEntityId()` when that backend has already died, and
sends it as `requestedEntityId` on the `stage` request. Nekopur reserves it during
staging, before the player's connection opens, and the `stage` reply reports the id
it will actually use. Players are minted from a proxy-assigned block rather than the
world's own entity counter, so an arriving player's id is not already held by a mob,
hologram or NPC. See [`HANDOFF_PROTOCOL.md`](../../Nekopur/docs/HANDOFF_PROTOCOL.md).

`ClientPlaySessionHandler.handleBackendJoinGame` makes the final decision from the
arriving packet, not from the reservation bookkeeping. The reset is skipped only for a
detached destination whose entity ID matches and whose encoded native-26.2 JoinGame
matches the current connection after excluding only that four-byte entity-ID field.
World, dimension, game mode, view/simulation distance and the remaining flags therefore
must all agree. A mismatch takes the visible path.

## Client state on the seamless path

`doSwitch()` is skipped, so its teardown has to happen elsewhere:

| State | Where it is handled |
|---|---|
| Tab list | `handleBackendJoinGame` clears it on the seamless branch |
| Header/footer | `TransitionSessionHandler` clears it on every JoinGame path |
| Bundle session | `TransitionSessionHandler` closes an open source bundle when detached |
| Backend boss bars | Removed per tracked UUID on the detached branch; proxy boss bars re-prepared through `preparePlayReset()` |
| Source entities | Removed by **Nekopur**, at fence, before the destination sends anything |

The source reports the ids it removed as `trackedEntities` on the fence reply.
Purroxy stores them (`HandoffCoordinator.sourceEntities`) but does not currently act
on them; removal is entirely the source's. The accessor exists so the proxy can take
that over if a source dying between fence and switch ever proves to matter.

## Verification

`:velocity-proxy:test` passes 281 tests on JDK 25.0.3. Coverage now includes JoinGame
fingerprinting, the exact reset-skip predicate, visible-fallback marking and bounded
coordinator/journal behavior, plus the requirement that CONFIG finish is held until
arrival approval is durable. These are protocol-level tests, not a live-client result.

## Live verification

Use the new Purroxy jar with existing compatible Nekopur handoff builds. Check:

1. The first login takes the normal configuration path.
2. A hub-1 to hub-2 transfer logs `Detached configuration: ... negotiated with the
   client remaining in PLAY`, the coordinator logs `destination reserved entity id
   N for a client holding N (kept)`, and the switch logs `Seamless switch to ...:
   keeping the client's world, no reset sent.` The first line does not say which path
   was taken — at that point the entity id has not arrived yet. Read the
   `Seamless switch` line for the outcome.
3. A `(changed)` id in that log means the destination could not honour the request;
   the client is then reset and a loading screen is expected. Investigate the block
   assignment rather than the configuration.
4. Position and velocity handoff still work. Verify chat, tab list, boss bars,
   inventory display, held items, titles, effects and Citizens entities after
   several round trips. Confirm no ghost entities from the source remain.
5. A deliberately different tag or unsupported configuration exchange logs one
   fallback and completes through normal configuration. Test only on test hubs.
6. Reconfiguration/reload, destination failure and reconnect still preserve the
   journal's recorded owner. Never expire a committed source fence to restore it.
