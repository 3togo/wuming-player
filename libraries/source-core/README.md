# Source core

`source-core` owns platform-neutral content-source contracts, catalogue selection and performance
ranking policy. Clients map their own persisted/runtime source models through `SourceAccess`.

The library does not parse Android configuration objects, load spiders, access Room, or execute
providers. Those are platform adapters and remain outside this module.

`ProviderHealthPolicy` gives provider adapters a shared recovery policy: three consecutive
failures open a 60-second cooldown, with a reset after 45 minutes without a failure. A quiet window,
successful call, clock rollback, or expired cooldown resets the failure history. Late failures
during a cooldown do not prolong it or count toward the next cycle. Time is supplied by the
host; the policy does not own scheduling or persistence.

`TvBoxConfigPayloadDecoder` handles the platform-neutral plain-JSON and image-plus-Base64 TVBox
configuration envelopes. JSON mapping, network loading and spider execution remain host adapters.

`SiteSpeedSamplePolicy` and `SitePersistencePolicy` give performance trackers the shared measurement,
flush cadence and merge rules: a sample below two seconds records success without a speed, writes are
rate-limited to a five-minute interval, a key is dropped after three consecutive write failures, and
stored history is adopted only by a record that has no requests yet. Scheduling, the mutable per-site
records and storage remain host adapters.

`CompanionProtocol` is the single versioned contract for the Android companion server and desktop
client. It owns endpoint paths, pairing-token shape, and required-parameter normalization; HTTP,
pairing state, provider handles and playback relays remain platform adapters.

`packages` owns bounded archive inspection, dependency/integrity diagnostics, package-scoped host
identity and version/selection/rollback policy. `SourcePackageStorage` is the persistence port. `FilePackageStorage` is its shared filesystem
implementation, so both platforms store immutable version directories and the atomic selection
index identically: only the storage root differs, plus an optional owner-only directory mode for
hosts whose store lives outside an app-private sandbox. Importing or inspecting a package never
executes providers. `ConfigResourceResolver` resolves configuration resource fields without global
replacement of provider code. See `docs/source-packages.md` for the import UI and storage contract.
