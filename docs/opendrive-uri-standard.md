# OpenDrive URI standard

Status: universal catalog rollout. Canonical URIs are playable, eligible HTTP inputs and resolved
built-in provider files are stored as history identities, and legacy IDs remain readable.

## Stable identity

The canonical form is:

```text
opendrive://<catalog-authority>/<resource-kind>/<resource-id>
```

Examples:

```text
opendrive://local/asset/550e8400-e29b-41d4-a716-446655440000
opendrive://local/collection/favorites
opendrive://home/live/news-1
```

The authority identifies the catalog that issued the ID. It is not a cloud provider. The URI
contains no provider name, manifest version, URL, credential, format, cache policy, or playback
session data. Those details can therefore evolve without changing bookmarks and history keys.

The resource kinds defined by the first contract are `asset`, `collection`, and `live`. Canonical
URIs are lowercase where required, contain exactly two path segments, and have no query or
fragment.

## Resource manifest

`MediaResource` stores stable identity and manifest metadata. One resource can own several
ordered `MediaSource` rows. Each source contains:

- a reverse-domain adapter ID, such as `org.opendrive.http`;
- an adapter-owned locator and locator schema version;
- a content/source fingerprint for idempotent imports;
- an optional opaque credential-vault reference;
- priority and enabled state.

Secrets never belong in `MediaResource` or `MediaSource`. Provider metadata and tokens are stored
under opaque `vault:` references using AES-GCM keys held by Android Keystore. HTTP URLs containing
query parameters remain transient because their ownership and refresh semantics are unknown.

Catalog resources record last access and an explicit pin bit. Maintenance retains history
references and pinned resources, removes unreferenced entries after 90 days, caps ordinary catalog
growth at 2,000 resources, and relies on the source foreign-key cascade. Backup manifests include
resources, sources, and vault references but never the vault payload or encryption key.

## Processing boundaries

```text
external input -> bounded decoder -> manifest -> catalog URI
catalog URI -> source resolver -> ephemeral playback target -> existing player
```

Decoders do no network I/O. Resolvers use provider APIs, authentication, refresh, proxy, manifest
parsing, and cache policy. `OpenDrivePlaybackSession` refreshes expiring targets and advances to the
next ordered source after a consumer-reported failure. The same short-lived target contract feeds
the existing player, HLS purifier/proxy, cache, cast, and download paths.

Legacy `opendrive-file:`, `opendrive-folder:`, and `opendrive-share:` identifiers remain readable by
the built-in engine. When a file resolves successfully, its opaque handle is cataloged and its
capability-bearing provider metadata is retained only in the encrypted vault, so subsequent
canonical playback survives process restart.

`opendrive-deferred:` is a bounded, process-local handoff used while a detail page is open. It
contains no provider metadata and is materialized only when its episode is selected. It is not a
durable identifier and must never be stored as the canonical history or catalog identity.

## Safe rollout gates

1. Keep ordinary and legacy production playback on the established path while the new contracts
   mature. **Complete.**
2. Observe equivalence for direct HTTP playback inputs without changing the URL or headers sent to
   the player. **Active.**
3. Add provider adapters whose locators survive process restart; credentials use vault references.
   **Complete for the six first-party cloud adapters and the generic OpenList/WebDAV connector.**
4. Accept explicitly supplied canonical URIs through the built-in OpenDrive route, fail malformed
   or missing resources closed, and retain the untouched legacy route. **Complete.** Automatic
   identity encoding is limited to targets whose URL and headers are reproduced byte-for-byte;
   query-credentialed and header-dependent targets remain transient.
5. Migrate history lazily only after a canonical source resolves successfully. **Complete.** The
   original catalog key, VOD ID, episode name, and episode URL remain available as fallback data.
   A missing or failed canonical resource is retried once through that original route.
6. Unify HLS proxying, ad filtering, cache, cast, and download behind resolver/session contracts.
   **Complete.** Existing consumers retain their behavior and receive a common refreshed target.
7. Remove legacy routing only after telemetry shows no unresolved legacy IDs across releases.

Database version 42 was additive: it created only `MediaResource` and `MediaSource`, with a foreign
key cascade from source to resource. Version 43 added nullable `History.mediaUri`. Version 44 adds
only lifecycle metadata and its GC index; existing identities and sources are unchanged. Old
history rows are upgraded opportunistically after successful playback.

`OpenDriveShadowProbe` is wired after the established player result is prepared. It records only
the resolution status and byte-equivalence flag; it does not log media URLs, write the catalog, or
alter player output. `OpenDriveUriResolver` lazily opens the catalog only when an explicit canonical
URI is played, so app startup and legacy playback do not pay the new database cost.

The app checks device storage pressure at startup and activity resume. Below the 5%/256 MiB low
watermark it removes only app-private cache entries, oldest first, until 10%/512 MiB is available.
The live Exo directory is excluded and remains governed by its own LRU; history, catalog, backups,
credentials, and user files are never cleanup targets.
