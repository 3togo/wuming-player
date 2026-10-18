# Category filter default regression (2026-10-01)

After recovering the guarded provider loader, `免费分享` still showed content
only in its recommendation tab. Category traces contained `extend={type=}` for
`tv_hot` and `show_hot` and `extend={area=, sort=}` for `hot_gaia`. The plugin
reported missing `items` or `subject_collection_items`; the device's HTTP cache
included a malformed `/api/v2/subject_collection//items` request returning 404.

`Filter.getInit()` normalized absent `init` fields to an empty string. Both
`MobileFolderFragment` and `LeanbackFolderFragment` explicitly use null to mean
"do not send this filter", so normalization made these null checks ineffective
and overrode provider defaults with empty values. The getter now preserves null.
An explicitly declared empty string remains empty, allowing legitimate "All"
values. Copy and parcel serialization continue to preserve the raw value.

The JVM contract test now distinguishes missing defaults, explicit empty defaults,
and explicit nonempty selections. This deliberately corrects the former unit
assertion for normalized `getInit()`; the existing parcel restoration test already
expected null for an absent default.

The mobile and leanback filter contract tests and mobile build passed. The updated normal app was
installed with data preserved on authorized device `192.0.2.10:43263`.
The new requests contain `extend={}`. The previously empty hot movie and TV tabs
display titles and thumbnails under `免费分享`; a subsequent hot variety check
under `豆豆┃片单` also displays titles and thumbnails. Device screenshots and logs are retained outside the repo
under `/tmp/tv-empty-tabs-20261001/`. Source-specific transport/parser failures in
other configurations remain separate from this filter-default correction.
