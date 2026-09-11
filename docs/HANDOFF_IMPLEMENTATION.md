# Coordinated hub-position handoff

Purroxy now prepares a handoff before opening the destination Minecraft connection.
The authenticated control connection exports the source snapshot, stages it on the
destination, fences the source, persists the ownership decision, and commits the
destination. A successful Minecraft connection releases the source and marks the
proxy journal complete. Failed release acknowledgments are retried every five
seconds while this proxy process remains running.

The profile transfers coordinates, rotation and velocity between explicitly matching
world identities and map revisions. It does not transfer inventory, XP, advancements,
plugin data or arbitrary world changes. Both replicas must already contain the same
map. Backend implementation and wire details are in Nekopur's
[`HANDOFF_PROTOCOL.md`](../../Nekopur/docs/HANDOFF_PROTOCOL.md).

## Enabling coordinated transfers

Enable authenticated discovery first. In each participating backend's
`Nekopurr.yaml`, enable `handoff.enabled`, set the same `handoff.world-identity` and
`handoff.map-revision`, and retain the `hub-position` profile. In the proxy's
`purroxy-network.toml`, set `handoff-mode = "normal"` inside the target group table.
Handoff is disabled by default.

An unavailable or incompatible handoff capability uses ordinary routing in normal
and seamless-preferred modes. An unresolved prior transaction is recovered before
ordinary routing is allowed, even if the group's mode was subsequently disabled.

`seamless-preferred` currently performs the coordinated handoff with the normal
visible client transition. `seamless-required` rejects the request: client continuity
is not implemented or qualified. Advertising matching map metadata is insufficient
to enable it.

## Failure behavior

- Before commit, the proxy persists an abort and attempts to roll back both backends.
  If the source fence cannot be confirmed cleared, the client is disconnected for
  recovery rather than allowed to continue on an uncertain owner.
- After commit, connection failure or a lost acknowledgment never rolls back ownership.
  Reconnect routing is pinned to the recorded destination until completion.
- A proxy restart converts unfinished preparation records to abort decisions. Committed
  decisions remain committed and are resumed on reconnect. Pending source-release
  retries held in memory are also recovered through that reconnect path after restart.
- Journals are local to one coordinating proxy. Multi-proxy ownership arbitration is
  not supported. Keep both proxy and backend journal directories on persistent storage.
- RPC queues are bounded and sends are paced per authenticated backend session. Queue
  saturation fails preparation; timeout after commit retains the committed decision.

## Verification and remaining work

Coordinator tests cover successful ordering, lost fence/commit/release acknowledgments,
recovery after reopening the proxy journal, cancellation, backend session replacement,
failed destination connection, replica mismatch, and disabled-mode recovery gating.
These use simulated backend replies. They do not establish live client compatibility.

The September 8, 2026 verification passed 241 proxy tests, including 11 coordinator
and journal cases, plus main/test Checkstyle and jar packaging under JDK 25.0.3.
The matching Nekopur backend build passed its 17 focused network/journal tests and
produced its runnable bundler jar. Neither result substitutes for live player testing.

The native 26.2 implementation now includes a backend-only configuration negotiation
engine and session handler. Normal backend negotiations capture bounded SHA-256
fingerprints of registries, active features, tags, known-pack offers, brand and
report/link metadata. The client's actual known-pack selection is copied for replay.
Capturing is active only for the preferred/required seamless discovery modes and native 26.2.
Unsupported configuration exchanges invalidate eligibility without changing ordinary
packet handling. Resource-pack changes, cookies, custom handshakes and unknown packets
are not silently acknowledged.

The detached handler validates the destination against that baseline, answers backend
keepalives/pings, replays the selection, and acknowledges finish in CONFIG before
switching the backend codecs to PLAY. It has a ten-second deadline, rejects mismatches,
and has no client connection to which it could forward configuration packets.

The subsequent configuration implementation passed 252 proxy tests, including 11 new
configuration tests, main/test Checkstyle and jar packaging. New cases cover exact
matching, registry/tag mismatch, early finish, duplicate selection, unsupported protocol
or plugin exchanges, buffer ownership, oversized capture, acknowledgment order,
timeout, cancellation and preservation of ordinary packet dispatch.

The handler is now installed for eligible committed `seamless-preferred` transfers;
see [detached configuration wiring](DETACHED_CONFIGURATION.md). The matching path
skips `doSwitch()` and keeps the client in PLAY during backend configuration.
It still uses JoinGame/Respawn to reset client state and adopt the destination's
entity ID, so terrain loading can remain. This is not full screen-free continuity.
Mismatches retry ordinary configuration once, retaining committed player ownership.
`seamless-required` remains unavailable. Native client testing and qualification of
proxy configuration plugins/translators are still necessary.

Live two-backend tests with a native 26.2 client, injected process/control failures,
and repeated transfers remain required. ViaVersion/ViaBackwards continuity is not
qualified. The current jar must not be presented as a completed loading-screen-free
transfer implementation.
