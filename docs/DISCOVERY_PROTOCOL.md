# Backend resume protocol, version 1

Implementation is in progress. This document specifies the currently implemented
transport for the future Nekopurr adapter; data handoff is not implemented yet.

## Connection

Connect to Purroxy's ordinary listener, default TCP port 25565. Send the eight
ASCII bytes `PURROXY` followed by LF. Then begin TLS 1.3 on that connection.
Purroxy requires a client certificate signed by its configured backend CA, and
pins the leaf certificate SHA-256 fingerprint to the backend identity policy.
The backend must verify Purroxy's server certificate and hostname. If the proxy
listener requires HAProxy protocol, that deployment must supply its required
HAProxy header before the discriminator, just as for player connections.

Within TLS, send UTF-8 JSON objects, one per LF-terminated line. Maximum frame size
is 65,536 bytes, with at most 20 control messages per second per connection. TLS
negotiation times out after five seconds. The listener's read timeout applies to
idle transport. Never send the control protocol through a player plugin channel.

## Resume

The first object must have `type: resume` and `version: 1`:

```json
{
  "type": "resume",
  "version": 1,
  "resume": {
    "serverId": "hub-1",
    "incarnation": "86a8ce12-3dcc-46f4-b5f6-1db1ce8be122",
    "host": "10.0.0.11",
    "port": 25565,
    "group": "Hub",
    "map": "none",
    "safeLimit": 50,
    "hardLimit": 100,
    "region": "US-West"
  }
}
```

The formatted example must be serialized onto one line for transmission. Generate
a new incarnation UUID for each backend process start, but preserve it across control
reconnections. `hardLimit` is the actual backend `server.properties` max-players;
100 is only the example value above. `host`/`port` are the Minecraft endpoint
reachable from Purroxy, not the configured Purroxy destination. Group/region/server
IDs normalize to lowercase; map names retain case.

The reply is `{"type":"registered","session":"<uuid>","heartbeatSeconds":5,
"leaseSeconds":20}` on one line. Registration starts in REGISTERING state; receiving
this reply does not allow players to connect yet.

## Heartbeats

```json
{"type":"heartbeat","session":"<uuid>","sequence":0,"state":"READY","players":12,"admitted":[]}
```

Send every five seconds with strictly increasing nonnegative sequence numbers for
this session. The valid backend states are REGISTERING, READY, SLEEPING, WAKING and
DRAINING. SUSPECT is proxy-owned. READY means the backend has a usable destination
world and accepts arrivals. Purroxy also verifies the advertised Minecraft endpoint
with an asynchronous ping before honoring READY. A later heartbeat completes this
readiness transition after the probe succeeds.

`players` is the authoritative backend count. `admitted` is reserved for future
backend-coordinated admission UUIDs and should currently be empty. The proxy holds
local reservations through connection completion and observes its live physical
connection counts so delayed backend counts cannot reopen a full instance.
Sleeping instances must have zero players and pending admissions. Continue sending
heartbeats while sleeping; receipt must not depend on paused world ticks.

The lease expires 20 seconds after its last accepted update, using a monotonic
clock. A detected control disconnect stops admission immediately. Expired sessions
must reconnect and authenticate; a late heartbeat cannot revive them. Reconnect
with the same resume/incarnation can preserve existing physical connections.
Changed resumes/incarnations require old connections to be retired first.

DRAINING and disconnected/expired entries are removed once empty with no held
reservations. Direct and group connection requests acquire slots after plugin
redirection and recheck them at backend join before disconnecting the source.
Abandoned slots expire after 30 seconds. DRAINING backends receive evacuation
attempts through the fallback resolver, retried at most every five seconds per
player. The retiring session receives `{"type":"retired","session":"<uuid>"}`
before its control connection closes. Complete failure recovery and live shutdown
verification remain outstanding; this is not yet production shutdown coordination.

## Wake requests

Purroxy sends `{"type":"wake","session":"<uuid>","request":"<uuid>"}` to a
sleeping backend when ready load exceeds 70% of safe capacity, or a requested group
has no READY instance. Keep the JVM/control loop running during sleep. The adapter
must validate the session, handle duplicate request IDs idempotently, resume its
world work, and send WAKING followed by READY once the requested map is usable.
No player traffic is routed to a sleeping/waking backend. READY still requires the
proxy's reachability probe. The proxy waits up to 60 seconds for readiness, then
backs off that session for 30 seconds and reports the failure to admins.

No spare means connections continue through safe-limit overflow up to actual hard
limits. Admins receive rate-limited capacity alerts with backend counts/limits and
a Pterodactyl action, plus a recovery notification when ready capacity is sufficient.

## Configuration and remaining work

The generated `purroxy-network.toml` defaults discovery off. Configure certificates,
groups and per-backend fingerprints/endpoints before enabling it. Configuration is
currently loaded at startup; atomic runtime policy reload remains pending.

`/server <group>` now reserves an eligible backend using safe-capacity preference
and overflow fallback; existing connection events may redirect/cancel the request.
`/region <region|auto>` saves a UUID-based preference in `purroxy-regions.properties`
with asynchronous atomic writes. Groups appear in command suggestions and the
clickable list. Runtime tests with actual backends are still outstanding.

Further work includes protocol/capability fields, initial/forced-host/kick fallback
live verification, bounded group retries and cancellation of superseded requests,
the backend wake implementation, map preparation, drain recovery and player-state handoff.

When discovery is enabled, `fallback` and `forced-hosts` in `purroxy-network.toml`
own login/kick fallback routing. Their entries name known groups or provisioned
backend identities. Lookups use fresh membership and exclude already attempted
physical destinations; an unavailable member does not stop iteration over the group.
