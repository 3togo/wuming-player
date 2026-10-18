# Prepared Android runtime validation

Date: 2026-09-11. Status: all 20 selected tests passed on Android 15 ARM64; see final run below. This is the next evidence gate
for the optimization task; host benchmark wins do not establish Android gains.

## Initial device run

The [explicit 20-test selection](android-runtime-tests.json) has been checked
against current source method names. It covers:

- Android SAX Unicode/entity behavior and DTD/expanded-gzip limits (2 tests).
- Range order, concurrent reads, active-reader closure, slow-range replacement,
  failed replacement, seek/startup, rolling buffers, range metadata and sibling
  cleanup (10 tests).
- Danmaku URI rejection, deferred parsing after response closure, parsed output
  and independent cancellation (4 tests).
- QuickJS failed conversions, HTTP reply wrappers, promises and timers (4 tests).

Tests use generated/fake sources or loopback HTTP. Select these exact methods,
not the entire range class: that class also contains opt-in live-provider tests.
This first pass checks runtime correctness, not real-provider throughput, renderer
smoothness, native heap size or casting media compatibility.

## Packages and installation scope

Prepare ARM64 mobile and leanback variants locally. Choose the appropriate variant
after the authorized device's ABI and UI are known. Each run needs two APKs:
`無名(testing)` is the separate verification application;
`無名(test runner)` is the instrumentation runner that executes its tests.
The normal `無名` package has a different application ID.
Mobile and leanback verification variants share a package ID and are alternatives,
not simultaneous installations. Do not clear data or uninstall existing packages
as an implicit part of this run. If the installed verification signature conflicts,
stop and report it rather than removing user data.

## Execution after device authorization

Use only the user-authorized serial on every device operation. Verify ABI before
choosing an APK. Install the verification app and matching runner, then pass the
comma-separated JSON `tests` list to AndroidJUnitRunner's `class` argument. Retain
instrumentation output and require all 20 tests to execute and pass; skips,
crashes, timeouts or fewer tests are not a successful validation. Record serial,
Android version, ABI, selected variant and exact artifact hashes with results.

No device inventory, connection, installation or execution has occurred for this
preparation. AGENTS.md restricts earlier device permission to other tasks. The user
must identify and authorize a device for this optimization validation first.

## Later measurement gates

After runtime correctness passes, measure Android EPG import allocation/time,
casting full preparation/remux/renderer behavior and disk/RSS high-water,
range CPU versus upstream waits, danmaku parse retention versus frame rendering,
and QuickJS conversion/native heap costs. Define workloads and baselines before
collecting timings. Choose a native prototype only if a material bottleneck remains
in the optimized managed path; the initial correctness pass cannot justify one.

EPG repeated timestamp caching remains deferred: source review found repeated
conversion opportunities but no isolated evidence that a cache's added retention
and lookup work would improve the full import. The existing importer already
skips parsing timestamps for unselected programmes.

## Build result

All four ARM64 verification APKs assembled successfully offline. Package IDs and
launcher labels were checked from packaged APKs with aapt2. [Artifact paths, sizes
and SHA-256 hashes](../../PUBLIC_PREPARATION.md) identify
the exact build. No APK was installed and no instrumentation ran.

## Authorized device runs

The user authorized `172.16.30.109:42787` for this installation and test run.
It reports Android 15 and ARM64 with phone features. The mobile verification app
and matching runner installed successfully without clearing data. Exact initial
hashes and device details are in [run metadata](../../PUBLIC_PREPARATION.md).

The initial selection passed 19 tests but stalled in the final QuickJS HTTP test.
The agent stopped the testing process to investigate; the terminal “Process crashed”
message records that deliberate termination, not a spontaneous crash.
The HTTP test then passed alone (1.492 s) after adding a 120-second timeout and
progress markers to the runner. Full-selection reproduction remains under review.
Raw results are retained in `measurements/android-runtime-20260911/`.

## Offline investigation after phone removal

Two combined runs each recorded 19 passes and stalled in the final HTTP test.
The isolated HTTP run passed. In the diagnostic combined run, progress reached
11 synchronous replies, then stopped. A process inspection showed the verification
process still marked as instrumentation; Android's ordinary cached-app freezer
field was false but Vivo's separate `isFrozen=true` and `allowFreeze=true` fields
were present. The added JUnit timeout also did not fire while the process was
stalled. This supports OEM process suspension as an explanation; it does not prove
that the application/native runtime is free of other defects.

At the user's phone-removal message, device calls stopped. [Outcome summary](../../PUBLIC_PREPARATION.md)
retains status counts and the relevant diagnostic fields without unrelated device
process information. The second run has no successful completion result.

Prepared offline: QuickJS instrumentation now opens the existing isolated
verification `MemoryTestActivity` for the class, keeps its window awake, and closes
it afterward. The normal home activity and device power-management settings are
untouched. The wrapper-count baseline is taken after the foreground host exists.
The HTTP test keeps its 120-second timeout and reply progress markers. This is a
test-environment mitigation pending device rerun, not a verified production fix.

Offline verification completed: both mobile and leanback ARM64 instrumentation
compiled and assembled successfully. [New runner hashes](../../PUBLIC_PREPARATION.md)
identify the foreground-host revision. These new runners were not installed.

## Reconnected device, 2026-09-11

User authorized testing at `172.16.30.109:38591`. The foreground runner installed,
but the run passed only the first 16 tests before its new QuickJS class setup hung.
A debugger showed `Instrumentation.startActivitySync` waiting in ActivityScenario
while the main thread was idle. The debugger resumed all threads and detached;
the test process was deliberately stopped. This is an additional test-host setup
problem, not evidence of a QuickJS native failure or a successful foreground fix.

Replaced the synchronous setup with main-thread asynchronous launch plus a
15-second resumed-lifecycle deadline and bounded cleanup. The mobile runner built
successfully; see [its hash](../../PUBLIC_PREPARATION.md).
Installation ended with `INSTALL_FAILED_ABORTED: User rejected permissions` at the
phone installer. That replacement was not installed and no rerun completed.
The 20-test gate remains open. See [setup diagnostic](../../PUBLIC_PREPARATION.md)
and [run metadata](../../PUBLIC_PREPARATION.md).

## Final successful run: awake and foreground through ADB

All **20 selected tests passed**, zero failures and zero skips, in **10.324 seconds**
on `172.16.30.109:38591`. The exact passed method set was checked against the original
selection; no methods were dropped. [Raw instrumentation output](../../PUBLIC_PREPARATION.md),
[validated summary](../../PUBLIC_PREPARATION.md),
and [runner hash/setup](../../PUBLIC_PREPARATION.md)
record the result.

The unsuccessful test-class foreground hooks were removed. The retained runner
changes are the new conversion ownership regression, HTTP progress markers and
120-second HTTP timeout. During the successful run, ADB sent wake events and
brought the verification HomeActivity forward every five seconds. No debugger
was attached. Keeping the screen awake alone had not prevented OEM process
freezing; app foregrounding was also needed on this device. HomeActivity can start
ordinary background requests, so this is a correctness run, not a clean performance
benchmark. The original `stay_on_while_plugged_in=0` setting was restored afterward
and the device returned to its launcher.

This closes the initial Android SAX/range/danmaku/QuickJS correctness selection.
It does not establish end-to-end speedup, native memory savings, leanback-device
behavior, real-provider throughput or casting remux/renderer compatibility.
Earlier stalled and setup-failure runs remain recorded above for reproducibility.
