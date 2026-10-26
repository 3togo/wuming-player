# Public source and private extensions

The public checkout owns the shared application, its Android UI flavors, desktop,
core libraries, build scripts, tests and reviewed dependencies. It builds by itself.
`gradle/edition-defaults.json` defines the public identity. A consumer supplies
`edition.json` and an additive `gradle/edition-extension.gradle` to configure its
identity, optional Android SDK dependencies and packaging. `EditionSettings` is
generated as a resource in base-core; Android and desktop share the same protocol
policy and helper identities. Existing desktop native-protocol limitations remain.

`AppExtension` supplies Android extractor groups (before and after Push/Strm),
episode parsers, startup work, cache cleanup and server processes. The default has
no SDK additions. Configured providers must load successfully; missing providers
are errors, never an implicit downgrade. R8 retains their constructors. Server
extensions run after the existing access check. Live configuration retains its
opaque `core` JSON so an installed extension can interpret it without changing the
public database schema or copying the Live bean.

Portable link parsing, session ownership, runtime leases and cache budget policies
live in the public `libraries/*-core` modules. Private SDK calls and Android storage
ports stay in the consuming edition. The TV delayed-seek lifecycle fix is shared
by both editions. No Java retention contract or cloud playback route is changed.

The private repository maintains an exact public commit in `public-source.lock.json`
and only its extension sources, assets, policy and launcher. Its launcher exports
that Git revision into an ignored `.build/workspace`, adds the private inputs and
runs these same Gradle scripts. Generated source copies are disposable build inputs;
there is one maintained implementation. There are no source patches or replacement
copies of shared controllers/build files. Only named data files (catalogs, update
settings and feeds) may override the public defaults. Additional resource files
and a manifest-only Android library contribute private strings and protocol intents
without replacing UI-flavor or verification manifests.

The normal launcher accepts a local public repository containing the locked commit,
or fetches it into a private cache. It does not export uncommitted/ignored public
files. Explicit `--working-public` mode supports coordinated development and cannot
produce release artifacts. Upgrading the lock is a deliberate change, followed by
the shared test suite and both editions' builds. Publish the public dependency
commit before distributing a private lock that references it.

The private launcher serializes preparation/builds, preserves unchanged timestamps
and build outputs, removes obsolete extension inputs, and refuses edited generated
source. Maintenance edits to its named catalog/configuration files are copied back
to `extensions/`, with conflicts reported. Edit shared code in the public checkout,
private code in `extensions/`, and never maintain generated copies.

Public provenance checks reject private distribution markers. Private validation
retains its four hash-pinned SDK artifacts and disabled feeds, while verifying the
composed input hashes and inherited provenance inventory. Publication/installation
are separate actions; offline builds do not establish live SDK or device parity.

See [public refactor validation](shared-editions-validation.md) for completed checks.
