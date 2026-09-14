# Network implementation status

The full scope remains `NETWORK_FEATURES_DESIGN.md`, including native 26.2
seamless transfers. Older-client support is **no longer deferred**: the eligibility policy now
admits protocols 766 (1.20.5) through 776 (26.2), and each family below 776 is qualified by its
own live soak before being promoted. See [SEAMLESS_SCOPE.md](SEAMLESS_SCOPE.md).

## Implemented foundations

For the newer coordinated hub-position implementation, recovery behavior, opt-in
configuration and remaining seamless work, see [HANDOFF_IMPLEMENTATION.md](HANDOFF_IMPLEMENTATION.md).
The discovery verification history below predates that integration.

- `BackendResume`: immutable endpoint, identity, group, map, region and capacity metadata.
- `CapacityPolicy`: safe-capacity preference, overflow to actual hard capacity,
  regional selection, rotating ties and strict 70% wake decision.
- `DiscoveryRegistry`: sequenced sessions, monotonic leases, stale-session rejection,
  atomic slot reservations, admission reconciliation, same-process reauthentication
  and retirement checks.
- `DiscoveryConfiguration`: opt-in startup policy, backend certificate fingerprints,
  endpoint/group authorization and mutually authenticated TLS configuration.
- `DiscoveryHandshakeDecoder`: separates control handshakes and ordinary Minecraft
  traffic on the same listener without consuming player handshake bytes.
- `DiscoveryService`: wired into startup/shutdown and the network initializer;
  accepts authenticated resumes/heartbeats, publishes physical servers, probes
  readiness, retires empty expired/disconnected entries and reports unknown groups.
- Physical connection requests check discovery readiness after plugin redirection.
- Direct and group requests now acquire capacity reservations through connection
  completion; backend join rechecks them before closing the source. Stale physical
  server references cannot bypass discovery ownership, and abandoned reservations
  expire after 30 seconds.
- `/server <group>` selects members with the capacity policy and appears in command
  suggestions/clickable output. `/region <region|auto>` persists preferences by UUID
  with asynchronous atomic writes; group routing reports regional fallback.
- Login/kick fallback resolves current group membership from network `fallback` and
  `forced-hosts`, excluding failed/current destinations and preserving reservations.
- Maintenance sends deduplicated wake requests above the 70% threshold (or on demand
  with no READY members), prefers the requested/loaded region, waits for READY,
  times out failed wakes and alerts admins about missing capacity and recovery.
- DRAINING backends receive evacuation attempts through the same fallback resolver;
  empty retired sessions receive a `retired` acknowledgment before control closes.

Discovery is disabled by default. Group retries, full drain failure handling and
live integration qualification remain unfinished. The native Nekopur adapter now
exists in its own project. See `DISCOVERY_PROTOCOL.md` for discovery and
`HANDOFF_IMPLEMENTATION.md` for the coordinated transfer integration.

## Verification

The 2026-09-11 run (`ab25cb90`) compiled the proxy and passed **276 tests, 0 failures**
under JDK 25.0.3. The earlier integrated run described below passed 230 tests, including
22 discovery tests. Main/test Checkstyle and Spotless application
also succeeded. Tests include reconnect, configuration/authorization, 200 concurrent
reservations against an 80-player backend limit, direct/group reservation lifetime,
stale server references, stale counts and durable region preferences. Shadow jar packaging
succeeded before the latest routing integration; the current sources were
then recompiled/tested, but that earlier jar is not a final deliverable. No live
backend/client transfer test has been performed.

The Windows Gradle loopback failure was traced to Java's selector pipe attempting
to connect with Unix-domain sockets. A process-local workaround sets
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\GitHub\MC-NekoNetwork\Purroxy\.gradle\no-unix-sockets`
to a nonexistent directory, causing Java to fall back to TCP for its internal pipe.
Keep that directory absent. This enabled Gradle compilation and test workers; no
global Java or system network configuration was changed. Build logs and the earlier
standalone test dependencies/output are under ignored `.gradle/tmp`.

## Next integration work

1. Finish discovery integration: atomic policy reload, protocol/capability metadata,
   coordinated lease retirement and drain lifecycle, transport integration tests.
2. Complete bounded group retries, superseded-request cancellation, and robust
   evacuation failure handling. Verify all connection paths
   against a live backend, including control disconnect during login/configuration.
3. Exercise the existing Nekopur adapter and coordinated handoff with live players:
   source/destination restart, control interruption and repeated hub transfers.
4. Qualify the native 26.2 seamless path. It is implemented — detached configuration,
   entity-ID negotiation and the `JoinGame`/`Respawn` skip are all in
   [HANDOFF_IMPLEMENTATION.md](HANDOFF_IMPLEMENTATION.md) and
   [DETACHED_CONFIGURATION.md](DETACHED_CONFIGURATION.md) — but no client continuity has
   been verified against a real client, and the protocol gate cannot distinguish a
   native 26.2 client from one a translator rewrote to 776. Open items are tracked in
   [SEAMLESS_SCOPE.md](SEAMLESS_SCOPE.md).
