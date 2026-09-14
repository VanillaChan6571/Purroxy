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

The transaction also carries the client's entity ID: `stage` asks the destination to
reserve the id the client already holds, and its reply says which id it will really
use. At `fence` the source removes every entity it had shown that client and reports
those ids. Both exist so the switch can complete without resetting the client; neither
changes the data the profile transfers.

## Enabling coordinated transfers

Enable authenticated discovery first. In each participating backend's
`Nekopurr.yaml`, enable `transfers.enabled`, set the same `transfers.map-id` and
`transfers.map-revision`, and retain the `hub-position` profile. The legacy
`handoff.enabled` / `handoff.world-identity` / `handoff.map-revision` keys remain
readable as aliases. In the proxy's `purroxy-network.toml`, set
`handoff-mode = "normal"` inside the target group table. Handoff is disabled by default.

For the seamless path, use `handoff-mode = "seamless-preferred"` instead, and set
`transfers.sync-arrival-position: false` on the backends — a seamless arrival must not
be teleported back to the position the source froze it at.

An unavailable or incompatible handoff capability uses ordinary routing in normal
and seamless-preferred modes. An unresolved prior transaction is recovered before
ordinary routing is allowed, even if the group's mode was subsequently disabled.

`seamless-preferred` performs the coordinated handoff and, when the destination
negotiates an identical configuration and keeps both the client's entity ID and
client-significant JoinGame state, completes the switch without sending `JoinGame` or
`Respawn` — no loading screen. Any condition failing degrades to the ordinary visible
switch. Before a failed detached CONFIG probe reconnects, the proxy durably marks the
destination arrival visible so its normal position sync is retained. See
[detached configuration and the seamless switch](DETACHED_CONFIGURATION.md).

`seamless-required` still rejects the request outright. It is not a stricter form of
the above: it remains wired to refuse until the path has been qualified against live
clients, and advertising matching map metadata does not enable it.

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
Capturing is active only for the preferred/required seamless discovery modes, and only at a
protocol `SeamlessProtocols` treats as eligible.
Unsupported configuration exchanges invalidate eligibility without changing ordinary
packet handling. Resource-pack changes, cookies, custom handshakes and unknown packets
are not silently acknowledged.

The detached handler validates the destination against that baseline, answers backend
keepalives/pings, replays the selection, and acknowledges finish in CONFIG before
switching the backend codecs to PLAY. Before that acknowledgment, it also persists the
destination's explicit seamless-arrival approval; normal arrival synchronization is the
safe default until this point. It has a ten-second deadline, rejects mismatches, and has
no client connection to which it could forward configuration packets.

The subsequent configuration implementation passed 252 proxy tests, including 11 new
configuration tests, main/test Checkstyle and jar packaging. New cases cover exact
matching, registry/tag mismatch, early finish, duplicate selection, unsupported protocol
or plugin exchanges, buffer ownership, oversized capture, acknowledgment order,
timeout, cancellation and preservation of ordinary packet dispatch.

As of 2026-09-12, `:velocity-proxy:test` passes **281 tests, 0 failures** on JDK 25.0.3.
The suite now directly covers JoinGame equivalence, the `JoinGame`/`Respawn` skip
predicate, and durable visible fallback in addition to configuration and handoff tests.

The handler is installed for eligible committed `seamless-preferred` transfers;
see [detached configuration and the seamless switch](DETACHED_CONFIGURATION.md). The
matching path skips `doSwitch()` and keeps the client in PLAY during backend
configuration. Mismatches retry ordinary configuration once, retaining committed
player ownership. `seamless-required` remains unavailable. Native client testing and
qualification of proxy configuration plugins/translators are still necessary.

Live two-backend tests with a native 26.2 client, injected process/control failures,
and repeated transfers remain required. ViaVersion/ViaBackwards continuity is not
qualified, although an installed ViaVersion now positively gates eligibility on its
reported original client protocol. The loading-screen-free path exists in code and is unverified against a real
client; do not present it as qualified until the checks in
[DETACHED_CONFIGURATION.md](DETACHED_CONFIGURATION.md) have been run on live hubs.
