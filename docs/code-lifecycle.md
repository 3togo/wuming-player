# Code lifecycle and isolation

TVBox separates code by operational status rather than by age.

| Status | Location | Production build | Required treatment |
| --- | --- | --- | --- |
| Production | Included Gradle modules | Built and tested | Normal release gates |
| Compatibility | Included Gradle modules | Built and tested | Removal criteria and migration coverage |
| Incubating | `isolated/incubator/` | Forbidden | Owner, risks, validation and graduation criteria |
| Archived | `isolated/archive/` | Forbidden | Decision record and deletion condition |
| Frozen vendor source | `isolated/vendor-snapshots/` | Source forbidden; AAR may be active | No routine migration; record artifact status and provenance gaps |

## Current classification

The included production modules are `app`, `desktop`, `libraries:catalog-core`, `libraries:cloud-core`, `libraries:epg-core`,
`libraries:live-core`, `libraries:media-core`, `libraries:playback-core`, `libraries:search-core`,
`libraries:source-core`, `libraries:user-library-core`, `catvod`, `chaquo`, and `quickjs`.

The obsolete Hook AAR and its frozen snapshot were removed during the final public
provenance cleanup. No isolated vendor snapshot is active. Cling/Seamless protocol
sources are built from `vendor/cling`; their Android adapters are separately built
from `vendor/cling-android`. These are frozen, licensed production vendor sources,
with pinned origins, original file hashes and documented local patches; routine
language migration does not apply to them. Shared protocol source is checked for
Android imports by `verifyGenericLibraryBoundaries`.
ForceTech, JianPian, Thunder and TVBus were removed from the public distribution on
2026-10-18 at the maintainer's request because their redistribution permissions
were unresolved. Their artifacts, frozen snapshots, native facades, extraction and
download paths are deleted rather than archived. See [the removal record](vendor-sdk-removal.md).
ARM32 remains a supported build target; removing these engines does not remove TV ABI support.

The former custom SRT/VTT/ASS parser (`isolated/incubator/subtitle-preprocessing/`) was deleted on
2026-09-14: Media3 remains the production subtitle decoder and no preprocessing feature was adopted.
If a platform-neutral preprocessing need appears, it must be designed from scratch against the
current architecture.

Unused charset/BOM and MD5 helpers (`isolated/archive/2026-unused-text-helpers/`) were deleted on
2026-09-14: they had no production callers, duplicated JDK facilities, and no migration need was
identified. Git remains the authoritative history for all three removals.

Legacy OpenDrive identifiers, legacy TLS behavior, database migrations, old Android API fallbacks,
and deprecated facades which still have callers are compatibility code. They stay in production
and keep regression coverage until explicit removal conditions are satisfied.

## Moving unfinished work

1. Confirm that no release path depends on the code.
2. Remove production registrations, manifests, resources and dependency edges.
3. Move the complete non-production source slice—not isolated fragments—to
   `isolated/incubator/<feature>/`.
4. Add the feature's `LIFECYCLE.md` and retain focused tests or a validation harness beside it.
5. Run `./gradlew check` to prove the isolated tree is not connected to production.

## Archiving abandoned work

1. Record the decision and verify that compatibility/data migration does not require the code.
2. Move the slice to `isolated/archive/<year>-<feature>/` with its `LIFECYCLE.md`.
3. Delete secrets, credentials, generated binaries and redistributability-unclear artifacts rather
   than archiving them.
4. Prefer permanent deletion once historical value or migration obligations expire; Git remains
   the authoritative source history.

## Enforcement

`verifyCodeLifecycleIsolation` runs through the root `check`, every Android `preBuild`, and module
`check` tasks. It fails when:

- an included Gradle project is located below `isolated/`;
- an included project links an Android, Java, Kotlin, resource, asset, JNI, or similar source set
  into `isolated/`;
- isolated native source is included as a production project;
- a required production AAR is missing or its recorded SHA-256 changes without review; or
- an artifact named as incubating, experimental, prototype, abandoned, or archived is placed in
  `app/libs` for automatic packaging.
