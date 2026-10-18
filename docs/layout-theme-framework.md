# Shared layout and theme framework

## Plan and ownership

The framework has three layers:

1. `libraries/design-core` owns stable theme IDs, semantic color roles, component
   metrics and layout policy. It has no Android or Swing dependencies.
2. Android resources/AppCompat and desktop Swing are rendering and persistence
   adapters. Platform code chooses actual widgets, translates logical units and
   preserves native input, accessibility and lifecycle behavior.
3. Screens consume semantic roles (`surface`, `text`, `accent`, `selection`) and
   shared metrics rather than inventing palettes or copying layout decisions.

Implemented scope: a canonical token source with generated adapters, four tested
palettes, generated Android Aurora resources, desktop persisted appearance
controls, shared existing poster/grid contracts, and bounded desktop navigation.
Classic Android retains its wallpaper-led screens; popups use nearly opaque neutral
surfaces so lists and forms remain legible over poster art. This is an incremental
framework migration; existing screens still contain legacy spacing and color debt.

## Tokens and generation

Edit `libraries/design-core/design-tokens.json`, then run:

```sh
python3 scripts/generate-design-tokens.py
./gradlew verifyDesignTokens :libraries:design-core:test
```

The generated Kotlin `DesignTokens` and Android `design_tokens.xml` files are
checked in so IDE previews work without a generator at runtime. Android preBuild,
desktop check and design-core check fail when generated output is stale. Never
edit generated files directly. Adding a palette requires every semantic role.

Android's existing `aurora_*` names are compatibility aliases to `ds_*` resources;
existing semantic theme attributes and widgets remain the public UI contract.
Use `?attr/dialogSurface`, `?attr/dialogOnSurface`, `?attr/focusOutline`, etc. in
layouts, not direct palette colors. The existing theme-debt checker still applies.
Android alert dialogs and their custom content share a charcoal surface at 94% opacity,
with separate semantic selected/focused row colors and a dimmed backdrop. Site
pickers and configuration history add titles and use compact spacing; phone
single-column presentation is retained. This adapts Box's panel/row separation.
Android Aurora retains a dark canvas in both appearance modes: legacy white
artwork labels are not yet safe on a light canvas. It consumes the shared dark
Aurora tokens with opaque surfaces; Classic retains the wallpaper-led presentation.
Desktop supports all four palettes. Android light Aurora is deliberately deferred
until remaining text, icon and media-overlay roles have been migrated and rendered.

Text uses sp on Android; spacing and corners use dp. Swing receives user-space
pixels, then applies its existing desktop scale once. Do not pass physical display
pixels into layout policy. Mobile and TV retain their native typography and focus
zoom where they have not been explicitly migrated.

## Shared control spacing

`libraries/design-core/design-tokens.json` is the single tuning point:
`controlMinHeight` = 48, `controlPaddingHorizontal` = 12,
`controlPaddingVertical` = 8, and `controlGap` = 8 logical units.
The generator emits Android `ds_control_*` dimensions and Kotlin
`DesignTokens.CONTROL_*` constants. Do not add separate 48 dp aliases or
screen-specific settings spacing helpers.

Android `ControlSpacing` translates those dimensions using configuration density,
independently of AutoSize's mutable layout density. The existing common TextScale
walk applies it to standard wrap-content buttons, `settings-row` containers,
`control-icon` actions, `control-group` margins and `app-action` text controls, including views attached
later to dialogs or recycler lists. It is installed through the existing
activity, fragment and bottom-sheet typography hooks. Tagged rows cover both
settings pages and both Android variants. Site/history grids use `controlGap` and their rows use the shared minimum height;
custom list adapters consume the same height/padding dimensions directly.
Poster grids, playback geometry, checkboxes and fixed-height custom controls keep
their dedicated layout contracts. Longer content may expand beyond the minimum.

Desktop button/toggle delegates use the same control padding and minimum preferred
height, applying desktop scaling once. Explicit screen sizing remains an override.
This is rendering policy in platform adapters; shared variables stay in design-core.

## Appearance and layout behavior

Appearance IDs remain system=0, light=1, dark=2; color IDs remain classic=0,
aurora=1. Unknown IDs fall back safely. Android uses AppCompat for system night
mode and recreation. Desktop defaults to its established dark appearance and
offers Classic/Aurora and Light/Dark in Settings → Appearance. Changes persist
immediately and apply on restart, avoiding partially rethemed custom components.
Swing has no portable OS appearance signal; SYSTEM resolves to dark on desktop
and is not offered as a misleading automatic setting.

The existing grid contract stays mobile portrait=3, mobile landscape=5, TV=5,
with 3:4 posters. `LayoutConfig` delegates these constants to `LayoutPolicy`.
Desktop navigation is bounded to 176–256 logical units; ultrawide windows give
their extra width to content. Core compact/medium/expanded breakpoints and bounded
form widths are available for future screen migrations; they do not silently
override native navigation or user-selected grid settings.

Desktop poster cells reserve the measured title and metadata heights, layout gap
and focus-border insets before sizing artwork. This avoids clipping metadata when
the text scale or focus state changes.

## Extension and validation

New shared behavior goes in design-core. Add semantic roles there before wiring
new platform adapters. Keep TV focus/OK/Back behavior in Leanback and preserve
phone behavior when changing TV-specific navigation. Do not globally replace
legacy `vs_*` sizes or bypass provider/poster layout contracts.

The core suite checks normal text contrast (4.5:1), filled-action text and focus
contrast (3:1), persisted-ID compatibility and narrow/ultrawide geometry. Desktop
tests exercise settings persistence and render light/dark Classic/Aurora at two
widths to `desktop/build/reports/appearance`. Run both Android flavor unit suites,
the theme contract, Leanback remote checker and all three architecture guards.
Offline Swing renders and Android compilation do not establish real-device
D-pad, rotation or accessibility behavior; device verification needs separate
authorization.

Validated during implementation: 3 core tests; 7 desktop theme, settings and layout
tests with inspected Swing renders; the mobile unit suite (919 tests, 1 skipped);
27 targeted Leanback theme/layout tests; mobile/Leanback compilation and Lite
resource linking; token-drift, semantic-theme, remote-layout and architecture
checks. No physical device tests were performed.

Future screen migrations can replace remaining local spacing and typography,
adopt bounded forms where appropriate, finish Android light-canvas migration, and
add live desktop theme switching only
after custom-painted components support a complete theme-change lifecycle.

### Stable semantic typography (2026-10-07)

`TypographyPolicy` and `TextRole` in design-core own the mobile and TV text
hierarchy. Sizes come from `design-tokens.json`; `font_*` Android dimensions
alias generated tokens. Caption and micro text have a 12 logical-unit floor.
Mobile semantic text uses native configuration density, not AutoSize's mutable
layout density. Enlargement uses native smallest width and the saved text cap,
with a bounded 1–1.5 multiplier; rotation does not change it. Android converts SP through the platform font-scaling converter once, including
Android 14 nonlinear scaling, using a copy of native-density metrics. TV retains the limiting-axis 960×540 distance-viewing
contract. Swing's default controls consume the same label role through
`DesktopTvTheme.textSize`, with desktop scaling applied once.

Every XML view declaring `@dimen/font_*` carries a keyed role tag:

```xml
<tag android:id="@id/tag_typography_role" android:value="label" />
```

`TextScale` resolves those roles directly to pixels, including Material widgets.
Synchronous `TextScale.bind` hooks size controls at inflation and rebinding.
RecyclerView child-attachment hooks cover rows exposed by scrolling without a
window-wide layout. A root-scoped global-layout observer covers fragments and
async content; actual text-size comparisons repair stale caches and overrides. No per-video-frame scan is needed. Existing unkeyed tags
and D-pad contracts remain independent. Ordinary controls and poster labels use their declared semantic role as the
authoritative size. Localized navigation rail labels preserve the required
English/Chinese sizing contract. Subtitle settings and an explicit keyed playback
overlay multiplier remain separate contracts; overlay reapplication starts from
the role baseline rather than an already scaled size. Avoid deriving general UI text
from screen pixel ratios when adding new controls.

`python3 scripts/check-typography.py` (also a pre-build check) requires valid role metadata for XML text controls, including sizes inherited
from local styles and controls otherwise using platform defaults. It rejects
runtime `setTextSize` calls outside the resolver, except the localized navigation
rail and non-UI paint/danmaku operations. Detail roles must match across mobile
portrait/landscape resources. Policy tests cover bounded tablet sizing and
invalid preferences; device screenshots still verify clipping and readability.

Verification on the explicitly authorized AUTHORIZED_TABLET_SERIAL tablet:
landscape and portrait details retain the same text sizes; asynchronously loaded
Bilibili quality chips and rebuilt/reversed episode rows match the label role.
A temporary system font scale of 1.3 enlarged history and detail text without
shrinking individual labels to fit. Original font/rotation/power/lock settings
were restored. The separate verification package retained authenticated quality
tracks 80/64/32/16 and resumed playback. Mobile APK build, leanback compile,
design-core tests (6), desktop theme tests (3), token/boundary checks, and the
Leanback remote checker passed. Phone and physical TV screenshots were not taken.

Popup windows require their own root: `PopupToast` calls `TextScale.apply` on the
banner before attaching it to the activity decor. `TvAlertDialogBuilder` installs
`TextScale.applyDialog` on the dialog decor at creation, without consuming the
caller's show listener. This covers subsequently created titles, messages,
actions, custom content and list rows. Tagged XML keeps its declared role;
otherwise dialog titles use title, actions label, and content body. Mobile
Material dialogs use this shared builder. The exceptional platform proxy-result
dialog and crash-details dialog explicitly register their decor too. Bottom
sheets continue to use their existing root hook.

Popup follow-up device verification: `NotifyPopupInstrumentedTest` checks the
Material banner against label pixels, idempotent reapplication, standard dialog
title/body/action sizes, and preservation of caller show listeners. Both selected
methods passed together on AUTHORIZED_TABLET_SERIAL (4.875 seconds). Settled popup
and dialog screenshots were inspected; power/lock settings were restored.

Runtime popup follow-up: `TextScale.setRole` tags and immediately sizes
programmatically created controls. Source-picker row rebinding and QR/login
controls use it; legacy dialog-size helpers now delegate to semantic roles, and
TV configuration text helpers include the same system font-scale handling.
Source-picker column counts no longer reduce font size. Static typography and
Leanback navigation checks pass for this refinement. Its APK/device validation
is pending: the simultaneous runtime/catalog/search module reorganization
currently produces a circular Gradle compile-task dependency. Earlier popup
instrumentation results above apply to the previously installed build.


### Typography ownership correction (2026-10-07)

An authorized tablet screenshot showed smaller and larger category chips in the
same row. MobileTypeAdapter created landscape text from screen-height ratios,
while TextScale subsequently applied LABEL. Rebinding did not restore the role,
and RecyclerView could expose new holders without a global-layout pass. The
policy cache also compared only its last target, allowing later setters to hide.

Ordinary UI setters now call TextScale.bind or setRole. Geometry-based font
formulas and eleven-character fitting were removed from UI sizing helpers;
legacy FontScale entry points delegate to the existing shared design-core roles.
All XML text controls declare their role, including player actions inherited from
Control/ControlChip and previously implicit platform-default buttons/fields.
Long labels keep one role size and use the component's existing wrapping,
ellipsizing, or scrolling contract. Navigation localization remains unchanged.

Checker fixtures cover inherited styles, untagged platform-default text, and
additional setters in an otherwise exempt file. TypographyOwnershipInstrumentedTest
covers category holder reuse with short/long Chinese and English labels, stale
cache repair, idempotent overlay variants, RecyclerView scroll attachment, and
rotation/platform-font-scaling independence from mutable layout density.
Its device execution must be recorded separately from APK/test compilation.

Local validation for this correction: mobile ARM64 verification app and separate
instrumentation APK assembled; Leanback ARM64 Kotlin/Java compiled; all 24
LayoutConfig JVM tests and 32 design-core tests passed, including 3 typography
policy tests. All 4 checker regression fixtures passed. Typography enforcement
covered 798 views; Leanback navigation, design-token and shared-library/desktop/
lifecycle boundary checks passed. The new checker tests run as a dependency of
verifyTypography in the build. Physical-device instrumentation and after
screenshots are pending explicit installation/testing authorization; screenshot
authorization alone was not used to install packages.
