# Detached configuration wiring

This build adds a live backend-only CONFIG path. It keeps the client in PLAY during
negotiation, then uses Velocity's existing JoinGame/Respawn reset to establish the
new entity ID and world state. **This is not yet a screen-free transfer:** the
reset may show terrain loading. `seamless-required` remains rejected until full
client continuity is implemented and qualified.

## Selection

The destination group must use `handoff-mode = "seamless-preferred"`. Both Nekopur
instances must have compatible enabled `hub-position` handoff capabilities with
matching map identity and revision. Purroxy must have durably committed this
player's handoff to that destination. Discovery registration and a matching
diagnostic log alone do not meet these requirements.

Both the client and backend connection must use native 26.2, the client connection
must be classified as vanilla and still in PLAY, the source must remain active,
and a complete baseline from a successful normal configuration must exist.
Other connections use the existing normal path. The version checks do not certify
third-party translators or packet plugins; native clients must be tested first.

## Packet flow and fallback

After authenticated backend login, Purroxy acknowledges login and installs
`SeamlessConfigSessionHandler` on the destination. It sends client settings and
replays the previously selected known-packs response. Configuration packets are
validated privately. The source connection remains connected until destination
JoinGame arrives. No client `StartUpdatePacket` is sent on the matching path.

A mismatch, unknown exchange, timeout or changed source/client baseline closes the
probe and retries destination login once through ordinary configuration. That
retry does not re-export player data or roll back committed ownership. If normal
login also fails, the existing handoff recovery path handles the committed owner.

At the reset join, the proxy closes any open source bundle, clears the tab list and
header/footer, removes tracked backend boss bars and recreates subscribed proxy
boss bars around the normal reset. The destination JoinGame supplies its real
entity ID; preserving the old ID without resetting is not implemented here.

Normal baselines publish only after the corresponding connection becomes current,
including completion of an in-place reconfiguration. Native PLAY tag/report/link
updates and backend resource-pack requests invalidate old captures. Changes during
detached negotiation force fallback rather than using an obsolete baseline.

## Live verification

Use the new Purroxy jar with existing compatible Nekopur handoff builds. Check:

1. The first login takes the normal configuration path.
2. A hub-1 to hub-2 transfer logs `Detached configuration: ... negotiated with the
   client remaining in PLAY; using the reset join path.`
3. Position and velocity handoff still work. Verify chat, tab list, boss bars,
   inventory display, held items, titles, effects and Citizens entities after
   several round trips. Observe separately whether terrain loading appears.
4. A deliberately different tag or unsupported configuration exchange logs one
   fallback and completes through normal configuration. Test only on test hubs.
5. Reconfiguration/reload, destination failure and reconnect still preserve the
   journal's recorded owner. Never expire a committed source fence to restore it.

Automated tests exercise the handler installation, backend-only known-pack reply,
single retry, protocol/baseline exclusions and invalidation while forwarding PLAY
tags. They do not replace a real client test of the reset join.
