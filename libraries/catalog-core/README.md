# Catalogue core

`catalog-core` owns the platform-neutral catalogue, detail, episode and resolved-playback contracts
shared by companion hosts and desktop clients. It deliberately contains no spider ABI, network
transport, authentication, JSON implementation or Android bean.

`CatalogGateway` is the common execution boundary for catalogue consumers. A platform adapter may
back it with the authenticated Android companion today or a desktop-compatible JVM/JS source
runtime later; presentation code must not bind directly to either implementation.

Following the useful separation in FreeBox's `SpiderTemplate`, `CatalogSourceExecutor` represents
one locally executable source and `ExecutorCatalogGateway` routes multiple executors through the
same UI-facing contract. JavaScript and JVM loaders are adapters to this interface rather than new
catalogue implementations.

`CatalogSourceAuditor` exercises source discovery, search, detail and playback resolution through
that same boundary. It supports cancellation between stages, never includes provider identifiers
in reports, and distinguishes an unhealthy source (`FAILED`) from valid playback which requires an
unavailable platform capability (`UNSUPPORTED`).

Provider execution and private credentials remain in the Android companion. Desktop receives an
expiring relayed media URL, never provider cookies or raw authorization headers.
