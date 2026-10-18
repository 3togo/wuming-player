# UI resilience and consistency test

For routine validation and the two standard optional smoke levels, see
[the test strategy](test-strategy.md). This page describes additional exploratory tools.

`scripts/test-ui-resilience.py` crawls the pages reachable from the installed
application and creates an evidence bundle containing screenshots, UI hierarchy
files, logcat, a page graph, and a Markdown/JSON summary. The summary also records
device and application identity, cold/warm `am start -W` timing, and process PSS/RSS
after each measured launch so performance work can be compared against an explicit
device-specific baseline.

## Safe test

Connect and unlock a phone, tablet, TV, or emulator, then run:

```bash
./scripts/test-ui-resilience.py \
  --apk debug/mobile-arm64_v8a-YYMMDD_HHMM.apk
```

When multiple devices are connected, pass `--serial SERIAL` or set
`ANDROID_SERIAL`. Device type is detected automatically; `--mode mobile` and
`--mode tv` override detection.

The default run:

- performs three cold starts;
- tests background/foreground recovery and critical memory pressure;
- tests portrait and landscape on mobile, restoring the original rotation;
- crawls up to 40 distinct reachable UI states, five levels deep;
- skips editable, checkable, long-click, and potentially destructive controls;
- monitors the application for crashes, ANRs, failed starts, and process death;
- records three cold-start samples, one background/foreground sample, and memory;
- reports unlabelled controls, touch targets smaller than 48 dp, missing TV
  focus, off-screen controls, and inconsistent sizes for reused control IDs.

It does not clear application data. Installing an APK uses `adb install -r`.

Reports are written below `app/build/reports/ui-resilience/`. A resilience
failure returns exit code 1. UX findings are warnings unless `--fail-on-ux` is
used, in which case they return exit code 2.

## Device-specific performance comparison

Compare a run with an earlier `summary.json` from the same package, mode, device
model, and Android SDK:

```bash
./scripts/test-ui-resilience.py \
  --serial SERIAL \
  --baseline app/build/reports/ui-resilience/PREVIOUS/summary.json \
  --fail-on-performance
```

Cross-device baselines are skipped because their startup and memory values are
not comparable. The defaults flag regressions above 20% for median cold-start
total time and 15% for median cold-start PSS. Override these with
`--max-time-regression-percent` and `--max-memory-regression-percent`. Without
`--fail-on-performance`, comparisons remain informational; with it, a regression
returns exit code 3.

## Connected TV smoke test

This is a release-readiness integration check, not an acceptance baseline. Product rules and
deterministic navigation policy are accepted by `./gradlew unitAcceptanceBaseline`; this check is
reserved for Android window focus, input delivery, rendering, and other device-only behavior.

For a quick, non-destructive check of a physical TV or TV emulator, run:

```bash
./scripts/smoke-test-connected-tv.sh
```

The script automatically selects the connected Leanback device and its supported
32-bit or 64-bit ARM build. It preserves application data, installs the matching
debug APK, and checks cold start, foreground focus, DPAD navigation, background
recovery, forced-process restart, crashes, and ANRs. Screenshots, UI hierarchy,
launch timing, and logcat are saved below `app/build/reports/tv-smoke/`.

With multiple TVs attached, use `--serial SERIAL`. To test an existing build,
use `--apk path/to/app.apk`; `--no-build` reuses the newest matching output APK.

## Broader exploratory test

On a disposable test profile or emulator, broaden the crawl with:

```bash
./scripts/test-ui-resilience.py \
  --allow-risky-actions \
  --max-pages 100 \
  --max-depth 8 \
  --max-actions-per-page 25 \
  --fail-on-ux
```

`--allow-risky-actions` may change settings, submit fields, or activate controls
whose labels suggest deletion, logout, purchase, or reset. Do not use it with
valuable application data or accounts.

## Coverage limits

The crawler covers pages reachable from the launcher with the currently loaded
configuration and account state. Pages requiring unavailable provider data,
credentials, permissions, media, or special deep links need deterministic
fixtures or dedicated instrumentation tests. Review the captured-page table in
`summary.md` rather than treating a successful exit as proof that every
conditional page was reached.
