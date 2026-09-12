# Configuration divergence: recovered investigation and next test

Updated 2026-09-10; re-checked 2026-09-11. This documents the observer's diagnostics.
Seamless switching has since been activated for `seamless-preferred` groups — see
[DETACHED_CONFIGURATION.md](DETACHED_CONFIGURATION.md) — but the observer described here
is still the *normal*-path recorder and still performs a visible switch.

## Recovered work

Claude's cached workflow is outside the repository:

`C:/Users/VanillaChanny/.claude/projects/D--GitHub-MC-NekoNetwork/711e2482-34b9-4f29-a62f-030b3300feab/workflows/wf_7a965bd7-36c.json`

It contains completed registry, active-feature, known-pack and brand investigations.
The tag investigation and four review passes failed. These are research results,
not captures of the production hubs' network traffic. No newer packet-specific
production failure was found in the searched project logs or user transcript entries.
The cached scripts do not need to be executed to read these results.

## Current diagnostic

The observer still performs a normal, visible server switch. A mismatch now reports
the expected and received packet type, payload length and abbreviated SHA-256 hash.
Registry packets also identify the registry. The numbered data-packet position
excludes keepalives and pings; the observer's outer packet counter includes them.

The native 26.2 `ClientboundRegistryDataPacket.STREAM_CODEC` writes the registry
identifier before its list of entries and their NBT. Reading that bounded prefix
does not require NBT parsing. Diagnostics do not consume the live packet's buffer
or log raw NBT, plugin payloads or player data. Hash prefixes are diagnostic only;
matching still uses the full SHA-256 digest.

Unknown packets and differing client known-packs replies cannot be reported as
identical. A matching backend negotiation is still not proof that the client sees
identical state after proxy plugins or a protocol translator have processed it.

## Packet-by-packet checks

| Mismatch | Verified source behavior | Useful comparison |
| --- | --- | --- |
| RegistrySyncPacket | Registry identifier followed by ordered entries with optional NBT. Known-pack selection affects omitted NBT. | Read the named registry in the log; compare actual server jar hashes, enabled datapacks and plugin registry changes. Check client selection before attributing missing NBT to datapack differences. |
| TagsUpdatePacket | Maps of registry names to tag names to ordered numeric entry lists. Server serialization iterates maps. | Compare enabled datapacks and tag modifications. Wire order can differ without differing map contents; this requires structural diagnosis before relaxing any check. Preserve registry IDs and entry-list order. |
| ActiveFeaturesPacket | An array of enabled feature identifiers. | Compare the worlds' enabled features and actual loaded world configuration, then packet-modifying plugins. |
| KnownPacksPacket | Ordered namespace, ID and version triples. Nekopur compares the reply list with the offered list using equality; a different reply causes full registry data to be sent. | Compare offers and client replies, jar versions and selected built-in packs. Do not replace the ordered comparison with set equality. |
| Brand PluginMessagePacket | Backend brand payload is captured before Purroxy rewrites the displayed brand. | Compare Purpur's `settings.server-mod-name` and brand-modifying plugins. A different brand is not itself evidence of incompatible registry IDs. First distinguish a changed payload from changed packet ordering. |

Source checks used the local 26.2 files `ClientboundRegistryDataPacket`,
`ClientboundUpdateTagsPacket`, `TagNetworkSerialization`, `SynchronizeRegistriesTask`,
Purpur's `PurpurConfig`, and Purroxy's packet codecs and `ConfigSessionHandler`.
The 17:12:06 live test identified TagsUpdatePacket at data position 32: both
payloads were 35,203 bytes, but their wire hashes differed. This establishes the
first differing packet, not whether its contents or only map ordering differed.

Tag comparison now sorts the registry-name and tag-name map keys in a separate
comparison encoding. Numeric IDs, member order, duplicates, empty tags and empty
registry maps remain significant. The ordinary wire encoder is unchanged. Registry
data, known-pack offers and other packet types still require exact payload matches.
Tag hashes in mismatch messages now describe the canonical comparison encoding.

When only tag-map ordering differs, the observer reports:

`matched this client's configuration after normalizing tag-map order.`

This result is emitted only if the complete observed negotiation and client
known-packs selection also match. Changed tag contents still reject the attempt.

## Next live evidence

Use the rebuilt Purroxy jar and perform hub-1 to hub-2 and hub-2 to hub-1 switches
with the same native 26.2 client. Save the `Seamless check:` lines from the proxy
console or its `logs/latest.log`. Record whether Via or packet plugins are active.
No changes to world files are necessary to gather this evidence.

The result determines whether to fix content drift, account for harmless wire
ordering, or correct the capture.

Ownership-safe backend admission, entity-ID handling and client-state reconciliation —
listed here as prerequisites for activation — have since landed. This evidence was never
gathered first, so the production divergence rate between the hubs is still unknown and
is now observed only as seamless switches succeeding or falling back.
