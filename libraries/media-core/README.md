# TVBox media core

Artifact: `com.fongmi.tv:tvbox-media-core:0.1.0-SNAPSHOT`

This library owns stable media identity, conservative media-file classification, provider-neutral
manifests, decode/resolve ports and the platform-independent media pipeline. Persistence, HTTP
implementation, credentials and provider runtimes are supplied by platform adapters.

`PrimaryMediaDestination` and `PrimaryMediaAvailability` carry the platform-neutral VOD-before-Live
home navigation contract. Android and desktop remain responsible for their own buttons and screens.
`TvHomeLayoutSpec` carries Leanback's stable action order and responsive toolbar/tile proportions;
platform adapters remain responsible for rendering, focus, icons and navigation transitions.
