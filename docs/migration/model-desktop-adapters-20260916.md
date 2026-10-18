# Danmaku and desktop casting adapters converted to Kotlin

Date: 2026-09-16. Continues the bounded migration described in the [opportunity review](kotlin-opportunity-review-20260916.md).

## Implemented

Converted three production Java files, previously 250 lines combined:

- `app/src/main/java/com/fongmi/android/tv/bean/Danmaku.kt`
- `desktop/src/main/java/com/fongmi/tv/desktop/cast/DesktopRenderingControl.kt`
- `desktop/src/main/java/com/fongmi/tv/desktop/cast/DesktopAvTransport.kt`

Updated `PlaySpec.kt` to call the converted bean's explicit accessors. Its selection/copy policy is unchanged.

Danmaku remains an overridable mutable bean with its original Gson field names, nullable backing state, name fallback, URL-based equality/hashCode, constructor, getters/setters and static factories. Explicit `open` factory declarations preserve non-final JVM static bridges, allowing Java subclasses to declare their own factories. A Java compilation/runtime regression guards this compiler-sensitive behavior. This is deliberately not a data class.

Desktop adapters retain inherited UPnP action signatures, checked exception declarations, synchronized methods, input validation order, error codes, event suppression, URI-change behavior, and player ownership. The existing network service and process implementations were not changed. Casting logic remains in its existing desktop adapter layer; no shared-core behavior or Android UI navigation was changed.

## Tests and compatibility

Added ten Java regression tests:

- Five Danmaku tests cover serialized field names and raw nulls, fallback names, mutable selection, URL-only equality, Java overrides, null-returning subclass behavior, and subclass static factories.
- Five desktop adapter tests cover volume clamping and rejected writes, mute and repeated event publication, instance/channel validation including null inputs, metadata limits, error ordering, stopped URI replacement, actions/position/state transitions, shutdown, and discovery of all four rendering actions by the UPnP annotation binder.

Existing PlaySpec selection-isolation tests and DesktopCastingTest transport/binding tests also ran.

| Final validation | Result |
| --- | --- |
| Mobile ARM64 verification JVM suite | 837 passed, 1 skipped |
| Leanback ARM64 verification JVM suite | 844 passed, 1 skipped |
| Desktop JVM suite | 183 passed, 5 skipped |
| **Total** | **1,864 passed, 7 skipped; zero failures/errors** |
| Android production compilation | All six mobile/leanback/leanback-lite ARM64/ARM32 verification variants passed |
| Original Java baseline | 5 Danmaku tests and 11 desktop adapter/casting tests passed against separately compiled originals |
| JVM API comparison | All three classes have no missing or changed existing public/protected declarations or descriptors |
| Architecture/lifecycle | All three boundary tasks passed |
| Formatting/whitespace | ktlint 1.8.0 and git diff --check passed |

The API comparison includes generics, static methods, checked exceptions, synchronization, visibility and overridability. It permits final methods only where the original declaring class was already final. The original-source test runs are additional checks and are not added to the full-suite total. This is a focused callable API comparison, not proof of every reflective or runtime contract.

No device tests were run. JVM validation does not establish physical DLNA interoperability or Android URI/server behavior. No UI/focus changes, database changes, dependency upgrades, or performance improvements are claimed.

## Remaining inventory

The Android app now has **266 production Java files / 64,574 lines**. Desktop has **four production Java files**: CastPlayer, MpvCastPlayer, DesktopCastService and DesktopUpnpConfiguration. The current audit and CSV are updated; earlier delivery reports retain their historical counts. AdaptiveWindowController and the audit's coverage/path-tooling findings remain separate follow-ups.

All pre-existing working-tree changes were preserved. Machine-readable results are in [verification.json](../../PUBLIC_PREPARATION.md).
