# Bug investigation and repair

Use this guide when reproducing, tracing, fixing and validating a defect in 無名.
For daily build/depot/publication commands, see [maintenance](maintenance.md).
For acceptance requirements, see [test strategy](test-strategy.md). This guide
supplements those documents rather than replacing their release or device rules.

## 1. Establish the failing configuration

Record the application edition, Git revision and local changes, mode (mobile,
leanback, leanback_lite or desktop), ABI, OS/API level, screen resolution and
language. Record the configured provider and player engine when relevant, without
copying account tokens or credential-bearing URLs into reports.

Write a short reproduction: starting state, exact actions, expected result,
observed result and how long the failure takes to appear. Include an unaffected
control, such as mobile when only leanback is failing. Distinguish a reproducible
failure from a plausible explanation that still needs runtime evidence.

Before editing:

```bash
git status --short
git log -20 --oneline
df -h . "$HOME/temp"
```

Preserve unrelated work. Use `~/temp/` for scratch files and isolated worktrees;
do not reset or stash someone else's edits. Choose a descriptive category branch
when a new branch is needed. Confirm sufficient disk space before native builds;
a partial build or an older output APK is not a successful new artifact.

Establish which edition and launcher the configuration requires. A public build
may lack optional/private provider extensions. Keep extension implementations in
their owning repository; a synthetic catalogue test does not prove that the user's
configured provider can activate.

## 2. Use history to find the original contract

Search local history by affected path and behavior, then inspect patches and tests:

```bash
git log --oneline -- app/src/leanback/res/layout/activity_video.xml
git log --all --oneline --grep='typography' -i
git show COMMIT -- path/to/affected/file
git blame -L START,END -- path/to/affected/file
```

Commit titles are leads, not proof. Read the implementation and regression tests,
then compare them with current callers and uncommitted changes. Check whether a
later change supersedes the original decision. Use `gh` for GitHub reads and
publication, with inherited proxies disabled; local history inspection uses `git`.
Do not use a speculative revert that would also remove unrelated fixes.

## 3. Trace the entire production path

Follow input to state, state to rendering/output, and every callback that can
write the same state again. Search identifiers and call sites with `rg`.
Include timers, background completion, player size changes, lifecycle restoration,
configuration changes, cached results and error/retry paths.

For UI defects, enumerate all overlapping views and every writer of their text,
size and visibility. Check flavor/resource overrides and runtime typography;
changing XML alone may be overwritten by a later binding pass. Distinguish video
resolution from display resolution and native density from mutable layout metrics.
Apply a screen-size factor once, and test repeated binding for stable results.

For intermittent duplicate overlays, inspect the whole transition: playing →
pause/INFO → timer refresh → resume. Hiding a view during pause is insufficient if
a periodic renderer makes it visible again. Reserve the clock's width, constrain
long text, and verify both immediate and delayed state after each action.

For provider/playback failures, separate activation, catalogue parsing, URL
resolution, HTTP transport, playlist validity, player creation and decoding.
Preserve useful error details through wrappers to the UI. HTTP 200 and an HLS MIME
type do not establish valid media; inspect returned bytes and upstream failures.
Keep media rejection separate from renderer failure so fallback does not replay
invalid input. Redact secrets from all evidence.

## 4. Repair at the owning boundary

Keep a flavor-specific UI fix in its flavor whenever possible. Audit mobile and
leanback_lite resource inheritance before claiming mobile is unchanged. Reusable
non-GUI logic belongs in `libraries/*-core`, with Android and desktop providing
ports; read [desktop sync architecture](desktop-sync-architecture.md) first.
Prefer existing parsers, policies and lifecycle helpers over a second implementation.

For each changed early return, catch or callback, check cleanup, notifications,
request identity, cancellation and retry behavior. Late results must not replace a
new selection. Preserve working cached state during failed refreshes. Distinguish
an idle stopped provider that can restart from an explicitly closed executor.

Before Java/Kotlin/Rust migration, read [language boundaries](language-boundaries.md)
and [Java retention](java-retention.json). A bug fix does not justify removing a
retention marker or adding business logic to a required Java facade.

Use the specialist notes for the affected boundary:

| Area | Required context |
| --- | --- |
| Quark smart quality | [Measured failures and accepted fallback](quark-smart-quality-lessons.md) |
| UC playback/quota/cache | [Quota fallback and cleanup boundaries](uc-playback-cache-lessons.md) |
| Ali routing/tokens/copies | [Route and account-scoped cleanup contracts](ali-playback-cache-lessons.md) |
| Android instrumentation | [Device isolation and lifecycle](android-instrumentation-lessons.md) |
| Desktop playback/build | [Desktop build/test](desktop-build-test.md) and [embedded playback](desktop-embedded-playback.md) |

## 5. Validate behavior and its neighboring states

For a meaningful regression, add a maintained test that fails before the fix (or
with the fix removed) where practical. Assert observable output and state changes,
not just that a helper executes. Include recovery, repeated operations and an
unaffected case. Use existing fixtures; keep validation proportional to the risk.

Run focused tests first, then the mandatory routine acceptance baseline:

```bash
./gradlew-safe --offline unitAcceptanceBaseline
```

Offline requires cached dependencies. Report missing prerequisites separately
from passing tests. The baseline covers deterministic contracts, not real TV
appearance, device decoding or live provider availability.

For TV layout, focus or label changes, also run:

```bash
python3 scripts/check-leanback-remote.py
```

Exercise D-pad/OK/Back, visible focus, long titles, clock visibility, relevant
locales, preview/fullscreen, pause/resume, and multiple refresh intervals. For
screen-relative sizing compare the text-height/screen-height ratio at supported
display outputs, including 1080p and 4K where available. Check native font scaling
and repeated layout passes. Static layout checks cannot establish rendered bounds.

Use only explicitly authorized devices and an exact serial on every ADB command.
Read the instrumentation guide first. Save and restore wake/timeout/log settings,
keep authorized devices awake throughout tests, and never remove secure locks.
Explain the two packages before instrumentation installs: `無名(testing)` is the
application and `無名(test runner)` is its runner. Device smoke commands and
limitations are maintained in [test strategy](test-strategy.md).

For migration changes additionally run the retention inventory and verification:

```bash
python3 scripts/check-java-retention.py --inventory
./gradlew-safe verifyJavaRetention
```

## 6. Build and hand off the exact artifact

Build only the requested mode/ABI unless additional variants are needed. Example
for a separately installed leanback ARM64 testing app:

```bash
./gradlew-safe :app:assembleLeanbackArm64_v8aVerification
```

Use `LeanbackArmeabi_v7aVerification` for a 32-bit ARM TV. Verify the finished APK's
package, `無名(testing)` label, ABI, version, signing certificate and checksum.
Use current output metadata rather than selecting an old APK by filename alone.
Keep the normal app named `無名` in all supported locales.

Signing validation failures require checking the configured identity against
`updates.json`. Restore the shared key for ordinary distributable builds; never
generate a replacement or skip validation to claim a release artifact. The explicit
`wumingEphemeralSigning` option is for intentional local test signing with an
existing configured key, not release publication. Disclose test signing and its
inability to update an installation signed with a different certificate.

If a build fails, report the failing stage and preserve useful diagnostics. For
`No space left on device`, inspect free space and generated outputs; do not delete
checkouts, keys or unrelated caches blindly. Retry after space is available and
verify that packaging and signing actually finish.

Review the complete final diff and run `git diff --check`. Report what changed,
what was tested, what remains unverified and the artifact path. Keep implementation,
compilation, device validation and publication claims separate. Before opening or
updating a PR, use the shared `pr-readiness` workflow. Publishing is a separate
operation governed by [maintenance](maintenance.md) and [Android updates](android-updates.md).

## Historical examples worth reviewing

These local-history examples explain the workflow; they do not establish current
runtime coverage or mean the listed code is still unchanged. Inspect them with
`git show SHA`.

| Commit | Observed change and maintenance implication |
| --- | --- |
| `1e96b17` | Repaired caption/micro roles, TV overrides, clipping and stock-font glyphs. Audit tokens, flavor resources, runtime sizing and glyph availability together. |
| `1926245` | Resumed empty all-source searches after provider activation, guarded by current query/page/source state. Test asynchronous recovery and navigation away. |
| `88de5ad` | Added poster mirror recovery and restart of stopped guarded hosts while tracking explicit closure. Test both successful startup and later resource recovery. |
| `fa0d685` | Repaired a recognized reversed short-array initialization sequence at provider class loading, including prepared JARs. Test actual transformed behavior and constrain repair matching. |
| `b2a0ead` | Added committed-WAL-aware database preflight and fixtures. Main database metadata alone may omit the effective schema; keep upgrade checks nondestructive. |

When adding future examples, include the symptom, owning boundary, regression and
limitations. Keep historical evidence separate from instructions about current
behavior; update this guide when the maintained workflow changes.
