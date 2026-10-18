# EPG core

`epg-core` owns the platform-neutral programme-guide model, secure streaming XMLTV parsing,
time-window and catch-up URL policy, and conservative channel-name fallback matching. Stable
provider/channel IDs must be tried before name matching.

`XmlTvStreamReader` is an Android/JVM-compatible SAX visitor for selective imports.
It emits raw timestamps so adapters preserve their timezone and invalid-date policies.
Callbacks are provisional until a pass succeeds. File input supports gzip directly;
caller-supplied streams remain caller-owned. The default budget is 256 MiB expanded
input, two million channel/programme records, 64 Ki characters per retained text
field/attribute, 256 aliases per channel, and depth 32. DTD declarations and external
entities fail closed. Consumers must also bound aggregate retained output.

The Android adapter uses two passes so channel aliases declared after programmes
still resolve, and publishes only completed results. It retains existing guide dates.
`XmlTvParser` remains the separate JVM StAX API for consumers needing full guide models.

Android JSON parsing, downloads, caching, timezone presentation and UI remain in `app`.
Fuzzy matching deliberately rejects conflicting channel numbers and requires a caller-selected
minimum score; it must never silently replace an explicit provider mapping.
