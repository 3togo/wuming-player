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

No device was accessed. Live SDK playback, actual TV navigation, casting
picture/sound, and native-engine behavior still require authorized device checks.
The offline result demonstrates build/integration and regression-test parity,
not a new claim of hardware playback validation.
