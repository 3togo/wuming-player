# Shared-edition refactor: public validation

Validated on 2026-10-26. The public application builds independently with its
original public package identity and without private SDK artifacts. The new build
configuration and Android extension interfaces are exercised by the separate
private consumer. There is no Java/Kotlin/Rust language migration.

Completed offline checks:

- `unitAcceptanceBaseline`: 8,178 JUnit executions, zero failures/errors, 14 skipped;
  212 Python tooling tests passed. Counts include all configured Android variants,
  desktop and shared libraries; they are executions, not unique test methods.
- Mobile ARM64, TV ARM64 and TV Lite ARM64 verification APK builds passed, along
  with both helper debug APKs. Signing used the existing external identity with
  the explicit verification-only signing flag; no production release was created.
- Public bundled-source provenance and removed-SDK scans passed: 88 reviewed
  artifacts and 567 pinned vendor files.
- Java retention: all 94 retained contracts passed. Shared-library, desktop,
  lifecycle and design-token boundaries passed through the acceptance tasks.
- TV remote/label checks passed: 124 effective layouts, 300 focus targets,
  26 action contracts, 159 listener checks and three app-label locales.

The TV Lite manifest now shares TV's launcher and activity declarations alongside
its existing shared TV sources/resources. The former Lite manifest registered only
three utility activities. This was found when checking the private protocol
manifest merge; keeping the incomplete Lite manifest would omit the launcher.
Verification-only activities remain in the original verification manifest.

No device was accessed during the offline phase. Live SDK playback, actual TV navigation, casting
picture/sound, and native-engine behavior still require authorized device checks.
The offline result demonstrates build/integration and regression-test parity,
not a new claim of hardware playback validation.

## Silent Android device follow-up

On 2026-10-26, public source `84f888d39764e4ac7c57b0ad058162affb940616`
passed all 35 selected tests on an authorized Kuwan Pad Pro (Android 14, ARM64).
The selection included the 32-method deep mobile suite, two database preservation
checks, and the audio-free loopback playback/decoder-reset fixture. Initial,
cold-start, background-return and restart launches also passed. The separate
testing app and instrumentation runner were installed with data-preserving updates.

Device testing exposed a shared startup defect: Room's main database header could
still contain version 0 while the committed WAL held version 46. The read-only
preflight now checks the last committed, checksum-validated WAL page one. Newer
versions still enter recovery, and originals and sidecars remain unchanged. All
32 user-library-core tests and shared-library/desktop/lifecycle boundary checks
passed; real Android WAL and recovery-export checks passed on the device.

The mobile checks now account for optional Live navigation and native-density
touch targets. This tablet overrides activity orientation requests; the final
layout run used the explicit `smoke_rotate_display=true` test option, which rotates
the actual display and restores the saved rotation mode without relaxing layout
assertions. Earlier failed attempts were retained. The final public selection
passed in full after a separate startup attempt lost focus to another application.

Media/system/ring/notification volumes were zero, total-silence mode was enabled,
and touch/lock sounds were disabled. The synthetic video files contain no audio
streams. Monitoring found no media-volume or silence-mode changes. Original audio,
rotation and wake settings were verified restored, testing processes stopped,
and the device returned to its launcher. A second authorized Android 7.1 device
was inspected only: API 25 is below the app's API 26 minimum.

These results cover the selected tablet flows, not physical TV navigation,
live private-provider playback or casting to another device.
