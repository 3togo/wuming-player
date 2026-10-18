# Production-package heap profile — 2026-09-04

Real-device heap analysis found and fixed a native-binder retention of a destroyed
`PlaybackService`. Repeat playback also exposed and fixed mutable detail-cache
state shared between screens. The final phone completed ten playback/exit cycles
without retained destroyed Activities, playback services or players. The physical
Skyworth TV additionally exposes firmware-owned view retention, detailed below.

## Scope and method

- Physical Xiaomi 22021211RC (`munch`), Android 14/API 34, mobile ARM64 app.
- Physical Skyworth `skyT963`, Android 9/API 28, Leanback ARMv7 app.
- Profiled the real `com.fongmi.android.tv` package and its existing data. These
  installed builds permit heap dumps; this is **not** a release/R8 benchmark.
- Saved both original APKs, checked matching signing certificates, and installed
  the patched builds with `adb install -r`. No app data was cleared. APK and fix
  source SHA-256 records identify the final binaries beyond the shared Git label
  `acc7d877e`, since this checkout contains uncommitted changes.
- Captured GC-assisted HPROFs at settled Home baselines, active playback, and
  after cycles five and ten. Each run checks PID continuity and foreground
  Activity. Native, graphics and Java PSS come from post-capture `dumpsys meminfo`.
- Offline Shark 2.14 analysis uses Android lifecycle leaking-object filters,
  standard reference matchers, GC-root traces and retained-size calculation.
  No LeakCanary runtime was injected. Histograms separately count actual heap
  instances and Activities whose `mDestroyed` field is true.
- Playback uses a generated H.264/AAC 640×360 MP4 served locally through an ADB
  reverse. Every counted start reaches VideoActivity and MediaSession PLAYING;
  screenshots confirm decoded test-pattern frames on both physical devices.
  Repeated starts use the same URL to exercise cache/history restoration.

Raw heaps, screenshots, logs and local measurement JSON are under
`app/build/reports/production-heap/2026-09-04/` (Git-ignored). They remain local
because production heaps can contain account/application data. The generated
fixture can leave an ordinary playback-history entry; existing history was not
deleted. Original APK backups are `mobile-original-0.apk` and `tv-original-0.apk`.

## Heap-driven fixes

### Destroyed playback service held by a native binder

Before the fix, `patched-mobile-playback-active-analysis.txt` reports:

```text
JNI global root
  → PlaybackService$LocalBinder.this$0
  → PlaybackService (not held by ActivityThread)
```

The destroyed service retained **404,321 bytes (about 395 KiB), 931 objects**.
The non-static binder implicitly owned its service. It is now a static binder
holding a weak service reference, explicitly cleared at destruction. Teardown
also clears released player fields. The shared playback binder and TV renderer
binder follow this rule, and their clients tolerate a cleared service or a late
connection to a finishing/destroyed screen.

The final phone's active heap has exactly one live service, PlayerManager,
ExoPlayer and VideoActivity. All four counts return to zero after cycles five
and ten. Native-held binder shells remain (five at both checkpoints), but they
no longer retain a service or player graph; their combined shallow instance
size is 180 bytes at each checkpoint.

### Cached detail graph reused by the next screen

The initial repeated-playback run stalled on its second start before making a
media request. `SiteApi` returned the same cached Result/Flag/Episode graph;
the old screen's activated flag caused the new screen to skip episode setup.
The cache now owns a Parcel byte snapshot and returns an independent graph per
hit. Parsed episode routes and metadata survive through the existing Parcelable
contract; UI selection and list mutations cannot contaminate another screen.
Parcel objects are recycled on both serialization and restoration paths.

`DetailCacheOwnershipInstrumentedTest` exercises ten independent screen copies
with selection, list and metadata mutation. `PlaybackServiceBinderInstrumentedTest`
holds a binder while the real isolated service is destroyed and verifies that
the binder can no longer return it. Both passed on the physical phone.

## Completed phone measurements

PSS values below are KiB. Comparisons within a run share one process and app data.
Different runs have different warm-up/cache residency, so their totals are not
a controlled percentage-improvement claim.

| Run / checkpoint | Java PSS | Native PSS | Total PSS | Destroyed Activities | Detected app leaks |
| --- | ---: | ---: | ---: | ---: | ---: |
| Original installed build, navigation 5 | 19,832 | 130,840 | 326,650 | 0 | 0 |
| Original installed build, navigation 10 | 19,820 | 128,672 | 323,907 | 0 | 0 |
| Intermediate refactor, navigation 5 | 16,076 | 75,644 | 253,129 | 0 | 0 |
| Intermediate refactor, navigation 10 | 16,132 | 74,976 | 252,981 | 0 | 0 |
| Final fixes, playback baseline | 15,284 | 57,824 | 205,288 | 0 | 0 |
| Final fixes, playback 5 | 17,816 | 41,000 | 198,861 | 0 | 0 |
| Final fixes, playback 10 | 17,700 | 39,812 | 198,219 | 0 | 0 |

Each navigation cycle visits Search, Favorites and History. Home has 221 views
at every settled checkpoint. Final playback heap objects decline from 532,588
at cycle five to 529,773 at cycle ten. This evidence shows warm-up followed by
stable/declining memory for the exercised phone flows, with successful teardown.
The intermediate navigation binary predates the final cache and binder fixes.

## Physical TV findings

Original installed TV navigation completed ten cycles through Search, Favorites,
Settings and VOD, with one live HomeActivity and zero destroyed Activities.
Native PSS was 74,680 → 75,000 KiB and total PSS 223,722 → 224,647 KiB between
cycles five and ten. A known Android 9 `ViewGroup$ViewLocationHolder.mRoot` pool
path retained a detached dialog hierarchy: Shark reports **150,753 bytes** at
both checkpoints, separately classified as a library leak.

Playback on the updated TV revealed a different firmware path:

```text
ActivityThread.sCurrentActivityThread
  → ActivityThread.skyPatch
  → com.skyworth.patch.SkyActivityPatch.mSurfaceViewMap
  → HashMap key: detached SurfaceView
  → View.mContext: destroyed VideoActivity
```

This is visible in `final-tv-playback-valid-baseline-analysis.txt` after the first
playback exit. Shark labels it an application leak because its default matcher
list does not recognize this vendor implementation; the retaining root is
Skyworth firmware. It reports three traces in the same group, totaling 2,039,706
retained bytes; the displayed SurfaceView branch retains about 680 KiB.

The final `final-tv-playback-measured` run completed **ten successful playback
and exit cycles in PID 25573**. Its baseline follows earlier fixture playback,
so it already includes one firmware-retained screen. The active snapshot has
zero destroyed Activities; after exits the firmware again holds one, rather than
accumulating ten screens.

| Final TV checkpoint | Java PSS (KiB) | Native PSS (KiB) | Total PSS (KiB) | Destroyed Activities | Heap objects |
| --- | ---: | ---: | ---: | ---: | ---: |
| Settled baseline | 14,648 | 34,496 | 161,232 | 1 | 404,054 |
| Active playback | 24,016 | 38,036 | 184,428 | 0 | 406,492 |
| Playback 5 | 14,768 | 36,340 | 168,760 | 1 | 403,717 |
| Playback 10 | 14,600 | 36,520 | 170,481 | 1 | 404,026 |

The same firmware leak group totals 2,023,080 and 2,023,290 bytes at cycles five
and ten. Settled view counts remain 281. PlaybackService and PlayerManager counts
are zero at both checkpoints; one ExoPlayer Java object and one SurfaceView remain
alongside the firmware-retained screen. This is bounded retention in the measured
run, **not a leak-free TV result**. No firmware-private-field manipulation or
unvalidated rendering-path workaround is included in the app changes.

The original TV baseline also contains a 4,465-byte Android `Message.obj` library
leak classification; the later navigation checkpoints instead show the fixed-size
ViewLocationHolder path described above. Failed TV attempts include a short
decoder-start timeout and an input switch to the Skyworth HDMI Activity. Their
labels are retained as rejected attempts, not counted cycles. The final run uses
a bounded 25-second startup check and confirms all ten starts reached PLAYING.

After capture, the local media server and both task-created ADB reverse mappings
were removed, remote heap temporary files were removed, and the TV input screen
was restored. The updated production-package apps remain installed with data
preserved; the original APK backups remain available locally.

## Validation and reproducibility

The following command passed against the final fixes:

```sh
./gradlew :app:verifyAndroidMemory :app:assembleMobileArm64_v8aDebug \
  :app:assembleLeanbackArmeabi_v7aDebug --offline --console=plain
```

This includes 503 JVM tests per Android mode, five QuickJS JVM
tests, compilation for all three modes and both ARM ABIs, and isolated mobile/TV
application and test APK builds. The two new device regressions passed.
Evidence: `final-build.log`, `final-device-regressions.txt`,
`final-apk-sha256.json`, and `final-fix-source-sha256.json` in the local report folder.

See `scripts/heap-profile/README.md` for capture, analysis and measurement commands.
Per-run `*-exercise.jsonl` records every validated navigation/playback step and
dump. `*-analysis.txt` records root paths, `*-classes.tsv` records instance counts,
and `*-meminfo.txt` records PSS. Rejected navigation/startup/input-interrupted
attempts retain separate labels and are excluded from completed-cycle results.

This is a short repeat-cycle production-package profile, not an hours-long soak,
all-provider/account exercise, TV Lite device run, native allocation backtrace
profile, or proof that every object type is leak-free. Native/graphics PSS is
sampled, not attributed to allocator call stacks. Firmware retention remains a
separate observed limitation rather than being hidden by the passing phone tests.
