# Network implementation status

The full scope remains `NETWORK_FEATURES_DESIGN.md`, including native 26.2
seamless transfers. Older-client translation support is deferred by user request.

## Implemented foundations

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

Discovery is disabled by default. The service is partially integrated, not production
ready: group retries, full drain failure handling and the backend
adapter remain unfinished. See `DISCOVERY_PROTOCOL.md` for the implemented wire format.

## Verification

The latest integrated Gradle run compiled the proxy and passed 230 tests, including
22 discovery tests, under JDK 25.0.3. Main/test Checkstyle and Spotless application
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
3. Implement Nekopurr adapter in its own module change: standalone `Nekopurr.yaml`,
   actual hard capacity from server.properties, maps, live sleep/control and wake.
4. Coordinated data handoff and native 26.2 seamless path, then live integration
   testing. No client continuity or backend behavior has been verified yet.
