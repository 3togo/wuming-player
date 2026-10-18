# Leanback remote audit

This file holds two dated audits. Both are source and offline resource audits, not
completed device certifications: no TV was accessed for either one, so rendered D-pad
geometry, asynchronous focus recovery, external provider login pages, and IME behavior
still need an authorized device run.

A former duplicate at `docs/LEANBACK_REMOTE_AUDIT.md` (same content as the 2026-09-05
section below, differing only by filename case) was merged here and removed. The two
names could not both survive a checkout on a case-insensitive filesystem.

## 2026-09-11 — dialog and editor focus contract

### Scope and changes

- Reviewed the 129 effective main/leanback layouts, native widgets, custom focus selectors, binding click listeners, and programmatic dialog construction. Both leanback variants share these resources.
- All app-owned Material alert builders use `TvAlertDialogBuilder`. Its TV-only window listener handles clickable and long-clickable controls added during layout, gives them a focus outline, and allows vertical D-pad escape from editors. Existing show/dismiss callbacks remain owned by their callers. List dialogs retain a visible selector.
- Credential forms explicitly connect Cancel/Confirm and the text fields. Alert action buttons have an orange focused background, dark text, and a white outline. Custom TV editor backgrounds also have an outline.
- The provider-login window integration and the plain Alist/WebDAV dialogs install the same focus handling. Provider content is generated externally and still needs device verification.
- Credential exports suggest a filename using the current local timestamp to seconds (`yyyyMMdd-HHmmss`). Existing export-path rejection of existing files is unchanged.
- TV focus behavior is gated by `Util.isLeanback()` or leanback resources. Phone exports also receive the timestamped filename suggestion.

### Validation

- `python3 scripts/check-leanback-remote.py`: passed, including 129 layouts, 287 explicit focus targets, 26 action contracts, and 163 binding listener checks. It now rejects custom editor backgrounds without focus feedback and new Material alert builders that bypass the TV contract.
- `AccountCredentialBackupPathTest`: all five JVM tests passed, including the timestamp-to-seconds filename format.
- Leanback verification, leanback-lite debug, and mobile debug Java compilation passed. Leanback instrumentation sources compiled, including editor navigation and dynamic dialog action/OK/Back regressions.
- No device was accessed or installed. Static checks and compilation do not prove rendered D-pad geometry, external provider/web UI behavior, or IME behavior. The instrumentation navigation tests still need an authorized TV run.

## 2026-09-05 — full coverage audit

The user explicitly prohibited TV access; no device operations were performed for this
audit. External websites and hardware-specific focus behavior still need runtime
verification when a TV becomes available.

### Coverage

The scan resolves main resources with leanback overrides, covering 127 effective
layouts and 274 explicit focus targets. Both leanback and leanback_lite use these
TV resources. It checks explicit clickable controls, focus feedback (including
inherited styles and duplicate-parent-state children), 26 specific action
contracts, TV form editors, the cloud web pointer, and application labels.

| Screens / related dialogs | Source audit result |
| --- | --- |
| Home | Existing focusable navigation and history/poster rows retained. Down no longer dereferences a missing first row while data is loading. Handled Menu release is consumed. |
| VOD categories and filters | Existing grid/category navigation retained. Shared filter value chips now receive focus with a visible outline. |
| Search, collected search, fast search (all result layouts) | Reviewed keyboard, microphone, strict-search toggle, provider filters, and result layouts. Existing focus selectors and custom keyboard navigation retained. |
| Video details, VOD playback, live playback, casting playback | Existing D-pad/media key handling, seek controls, action rows, episode/line/channel adapters retained. Fullscreen video surfaces intentionally do not draw a border. |
| Favorites and playlists | Existing focusable rows and contextual actions retained; playlist list/multiselect dialogs already explicitly style and initialize selection. |
| Settings and player settings | Existing focusable settings rows retained. Generic player choice dialogs now explicitly select and highlight their list item. |
| File browser, drive management, backup/restore | File/backup rows already expose focused open/delete controls. Drive type/sort dialogs now show list focus. Local-path and WebDAV/Alist editors allow D-pad escape. |
| Push / pairing | QR action and clipboard action now focusable, visibly outlined, and linked vertically. |
| Cloud accounts and credential backup/import | TV path dialog avoids system DocumentsUI; relative backup paths use /sdcard. Shared form navigation preserves resource IDs and links fields to confirmation/cancel. |
| Cloud cache and WebDAV connectors | Existing focused action rows retained; connector editor uses TV single-line editors and explicit field/button navigation. |
| Cloud web login / management | TV-only WebView pointer: arrows move, OK clicks, arrows at edges scroll, Page Up/Down scroll pages, Menu/Back returns to native toolbar. Phone WebView remains unchanged. |
| Provider lab and crash screen | Provider editors use the TV editor; diagnostic controls and crash actions expose native/explicit focus behavior. |
| Cast-device chooser and incoming cast | Device toolbar icons and incoming-cast action now focusable and outlined; device rows have a persistent focused outline. |
| Timer, playback info, subtitles | Timer presets and info share/copy targets now focusable and outlined. Online subtitle result lists explicitly initialize and highlight selection. |
| Track, speed, buffer, offset, danmaku, episode, site, history, depot, config, password, update dialogs | Static layout/style review included. Existing focusable controls, native sliders/buttons, selectors, and custom editors retained. Removed a non-actionable focus stop in the User-Agent dialog. |

### Changes and limitations

- TV layout overrides keep mobile layout behavior unchanged.
- `TvDialogFocus` handles field/button navigation and list highlighting only in TV builds.
- `RemoteWebView` supplies pointer clicks for touch-oriented cloud websites. It
  cannot guarantee that every third-party login challenge, embedded scrolling
  region, popup, or external app is operable with a remote.
- The path flow needs Android storage access; absolute paths do not bypass scoped
  storage restrictions. No storage-permission bypass was introduced.
- The check is structural: it cannot prove on-screen geometry, asynchronous focus
  recovery, all long-press actions, or provider website behavior.

### Validation

- `python3 scripts/check-leanback-remote.py`: passed.
- Leanback verification, leanback-lite debug, and mobile verification APK builds: passed.
- `LeanbackRemoteDialogsInstrumentedTest` adds checks for D-pad escape from form
  editors, preserved view IDs, and secondary action focusability. These tests are
  compiled locally, not run on a device during this audit.
- Existing `LeanbackCriticalFlowInstrumentedTest` covers other core flows and
  remains available for a later authorized device run.

### App identity

Normal app: `無名`. Isolated verification app: `無名(testing)`. Instrumentation
package: `無名(test runner)` (no launcher entry). Labels are distinct in the default,
simplified Chinese, and traditional Chinese resources. See `AGENTS.md` for the
persistent naming and device-access instructions.
