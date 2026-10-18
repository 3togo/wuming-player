# Kotlin migration stabilization — 2026-09-16

## Outcome

Follow-up: [TV validation](tv-stabilization-20260916.md) subsequently passed all 26 selected tests on the Android 9 TV. The scope and limits below describe the earlier phone run.

Found and fixed a runtime crash from an earlier Java-to-Kotlin conversion and two Java generic-signature compatibility changes. No further production classes were converted.

The corrected mobile verification build passed **17 selected instrumentation tests in one run** on the authorized Xiaomi phone `192.0.2.10:41565` (Android 14/API 34, ARM64), in **27.155 seconds**, with zero failures or skips. All six application verification variants compile. JVM suites pass **1,629 tests**, with two skips.

This establishes focused mobile correctness. It does not establish TV runtime behavior, real-provider playback/casting compatibility, performance gains, or correctness of every migrated class. Chaquopy was disabled for these builds.

## Fixes

### TextScale resource tag crash

`TextScale.kt` used `const val TAG_SCALED = R.id.tag_text_scaled`. The compiled class contained `TAG_SCALED = 0` and passed zero to `View.setTag`, which Android rejects. The player-settings recreation test exposed the crash despite successful compilation and previous JVM tests.

Changed the alias to a runtime `val`. Bytecode now reads `R$id.tag_text_scaled` with `getstatic` during initialization. Added `textScaleUsesPackagedResourceTagAndDoesNotScaleTwice` to `ResourceLifecycleInstrumentedTest`; it checks the packaged tag, repeated apply, and the reset path on Android.

Searched the app's remaining Kotlin sources for other `const val` resource references; this was the only direct match.

### Episode dialog Java overrides

The Kotlin `episodes` methods in EpisodeGridDialog and EpisodeListDialog emitted `List<? extends Episode>` rather than the original `List<Episode>`. Ordinary Java callers compile with that widening, but an existing Java subclass declaring the original override can encounter an erasure/name clash.

Added `@JvmSuppressWildcards` to both methods. `EpisodeDialogJavaContractTest` includes Java subclasses with the original signatures and checks the reflected generic arguments. Both flavors now emit the original generic method signatures.

## Review scope

Independently compiled the saved Java sources from the most recent 12-file and 28-file batches against each flavor's classpath, without replacing workspace sources. Compared public/protected declared JVM members for 34 mobile and 37 leanback public types, including public nested types. No missing descriptors were found. The two generic-signature differences above were corrected. Remaining reported `final` method differences are within classes that were already final in Java, so they do not newly prevent subclassing.

This was a focused callable-interface comparison, not a complete ABI/nullability proof. Reviewed cancellation, weak references, view attachment and teardown alongside the device tests.

## Device evidence

All attempts remain in [measurements/stabilization-20260916](../../PUBLIC_PREPARATION.md):

| Attempt | Result |
| --- | --- |
| Initial focused lifecycle run | 9 passed in one run |
| Initial broader UI run | 1 passed; player-settings recreation crashed in TextScale; remaining 5 did not complete |
| Final combined run after fixes | **17 passed**, zero failures/skips |

The final run covers:

- Packaged text-scaling tag and repeated apply.
- Fragment view work cancellation and retained-adapter detachment.
- Seek listener attach/detach and player cleanup.
- Wallpaper reattachment without accumulating children.
- Closed UI scope rejection of late work.
- WebView child destruction and late-creation rejection.
- History/subtitle dialog teardown and cancelled/late danmaku responses.
- Home recreation; history, search and collection rotation/layout behavior.
- Player settings recreation/cold relaunch and usable labeled touch targets.

See [exact selections](../../PUBLIC_PREPARATION.md), [raw final output](../../PUBLIC_PREPARATION.md), and [machine-readable results including failed attempts](../../PUBLIC_PREPARATION.md).

Installed `無名(testing)` and `無名(test runner)` using `adb install -r`; no uninstall or data clear was used. Kept the device awake without repeatedly foregrounding a different activity during UI tests. Restored `stay_on_while_plugged_in` from 15 to 15 after each attempt and returned to Home. A post-run spot check confirmed the temporary test user-agent was not left behind. The existing UI tests restore their changed preferences in finally blocks.

## Local checks

- Mobile, leanback and leanback-lite Java/Kotlin verification compilation, each ARM64 and ARM32.
- Mobile verification app and instrumentation APK assembly.
- Mobile JVM suite: 812 tests, 811 passed, one skipped.
- Leanback JVM suite: 819 tests, 818 passed, one skipped.
- Generic-library, desktop and lifecycle boundary checks.
- TV navigation source check: 121 layouts, 282 explicit focus targets, 26 action contracts and 150 Java/Kotlin listener checks.
- Changed Kotlin formatting and `git diff --check`.

## Recommendation

Keep bulk conversion paused. The runtime crash demonstrates that language migration is not inherently low-risk even when builds pass. TV remote/runtime validation remains the next gap; this session authorized only the phone. Further conversions should accompany useful maintenance and targeted validation.
