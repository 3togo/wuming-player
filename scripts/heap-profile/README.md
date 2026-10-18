# Production heap profiling

These tools capture the **installed** `com.fongmi.android.tv` app. They do not
clear app data or install/uninstall packages. The installed app must permit
`am dumpheap` (the profiled production-package builds were debuggable).

Keep raw heaps local: they can contain application/account data. Reports in
`app/build/reports/production-heap/` are excluded from Git by the build-directory
rule. Do not attach raw HPROFs to public issues.

## Capture

Start the app at Home, confirm the device and screen geometry, then run:

```sh
python3 scripts/heap-profile/exercise.py --serial DEVICE --mode mobile \
  --label RUN_NAME --output app/build/reports/production-heap/RUN
```

The phone route uses the observed 2400 × 1080 landscape toolbar coordinates for
Search, Favorites and History. Adapt those coordinates after inspecting the UI
on a different device. TV mode uses remote focus; start on Home's VOD item
(index 0), or supply the observed `--tv-initial-index`. Its cycle visits Search,
Favorites, Settings and VOD. The script validates each resumed Activity and aborts
on an unexpected screen. Navigation assumes no modal dialog is open.

It captures a settled, GC-assisted baseline and snapshots after five and ten
cycles, recording PID continuity, every visited Activity, and post-GC `meminfo`.
An aborted run is evidence of a rejected exercise, not a completed measurement.
Use a fresh label for a retry so the evidence remains distinguishable.

For player teardown, supply `--playback-url` pointing to a controlled progressive
MP4 longer than the full repeated-playback duration. The same URL is reopened
on every cycle, exercising cached detail and history restoration. Each start must
reach VideoActivity and MediaSession PLAYING. This route can create/update the
fixture's ordinary playback-history entry. Capture includes an additional active
player heap. A localhost HTTP server can be reached with
`adb -s DEVICE reverse tcp:18765 tcp:18765`; remove that exact reverse afterward.
The fixture directory should contain only generated media, never heap files.

## Analyze

```sh
scripts/heap-profile/analyze.sh dump.hprof output-prefix > output-prefix-summary.txt
python3 scripts/heap-profile/summarize.py app/build/reports/production-heap/RUN \
  COMPLETED_LABEL ANOTHER_COMPLETED_LABEL > measurements.json
```

Requires Java/Javac (Java 17-compatible output), Python 3, and locally cached
Shark 2.14 (`shark`, `shark-android`, `shark-graph`, `shark-hprof`, `shark-log`),
Kotlin stdlib and Okio JVM jars in the Gradle cache. No network upload occurs.
It writes a class-instance/shallow-size TSV and Shark's full GC-root traces,
retained sizes and separate application/library leak classifications. Class
histograms are not dominator totals; the retained sizes come from Shark analysis.
The built-in Android lifecycle filters are used; no LeakCanary runtime is injected.

Compare like-for-like settled stages within one PID. Separate warm-up growth
from continued growth. An active Activity or service is expected; a destroyed
service held by a JNI binder root is not. Native/graphics PSS is sampled but this
workflow does not produce native allocator backtraces. Zero detected leaks applies
to the exercised flows and these detectors, not every provider or a long-duration
playback soak.
