# Box site lists, link lists and popups — 2026-10-12

## Scope and provenance

The former `~/git/Box` is now `../references/Box`, resolved through the workspace
manifest. Reviewed revision: `258a5fef61578869ae905ca230bdde9e99fc19a8`.
TV baseline: `099a5b1928ff0c087451db895a904ff60c2a2e85`, with existing local edits
preserved. No reference pull, provider execution, device access or publishing.
The dry-run inventory is `2026-10-12-box-list-popups-inventory.json`; its
`would-update` records are planned actions, not fetched revisions. The helper
returned 1 because other references are dirty; Box is clean and unchanged.

This is a bounded UI/contract review, extending the earlier Box practices review.
“Link lists” was examined as both configuration URL history and playback
line/episode lists. Inspected Box paths under `app/src/main/`:

- `java/com/github/tvbox/osc/ui/activity/HomeActivity.java`: `showSiteSwitch`.
- `java/com/github/tvbox/osc/ui/activity/DetailActivity.java`: line selection,
  episode range groups, sorting and list refresh.
- `java/com/github/tvbox/osc/ui/dialog/{BaseDialog,SelectDialog,ApiHistoryDialog,ApiDialog}.java`.
- `java/com/github/tvbox/osc/ui/adapter/{SelectDialogAdapter,ApiHistoryDialogAdapter}.java`.
- `res/layout/{dialog_select,item_dialog_select,dialog_api_history}.xml`.
- `bean/{AbsJson,VodInfo}.java` under the Java package above; configuration and
  playback wire fields were sampled for the second review lens.

TV comparison: both SiteDialog/SiteAdapter variants, BaseSelectAdapter,
CustomRecyclerView, TvDialogFocus/TvAlertDialogBuilder, HistoryDialog/ConfigAdapter,
EpisodeListDialog/EpisodeGridDialog/BaseDialog, FlagAdapter, UrlUtil,
shared CatVodCatalogCodec/ConfigResourceResolver, and desktop
CatalogPanel/ConfigurationPanel. The architecture document is lowercase
`docs/architecture-boundaries.md`; the uppercase skill reference is stale.

## Reusable presentation patterns and decisions

| Area | Box behavior | TV decision |
| --- | --- | --- |
| Site picker | Excludes hidden sites, computes current selection in the displayed list; 1–3 columns, capped list height, title and check mark | Keep TV's adaptive grid, ranking, active styling and scrollbars. Adopt displayed-list selection (REF-020) |
| Generic selection popup | Scrolls and selects the current row, with a focusable row background | Reuse TV's existing scroll/focus path; no replacement widget dependency |
| URL history | Separate API/live/EPG histories, up to 20 records, selected URL mark, delete control, capped scroll area | TV already has persisted configuration history and delete controls. Separate EPG-history UI is deferred without a requested workflow |
| Playback lists | Separate horizontal line selector, range selector and episode grid; group sizes vary with total count | TV already separates flags and episodes and offers range tabs. Do not copy fixed count thresholds or hover-driven selection |
| Popup window | Android Dialog with fullscreen flags restored around show; history uses a dimmed style | Keep TV's Material dialogs, typography, focus installation and bottom-sheet insets handling. No `PopupWindow` use was found in the searched application Java tree |
| Dismissal and ownership | Generic select callback does not itself dismiss; URL history stacks over its editor | Require explicit caller dismissal and preserve parent input. Box's source contains no generalized lifecycle/cancellation solution worth replacing TV's existing HistoryDialog cleanup with |

The reusable abstraction is: **a bounded scrolling surface + separate active
selection and navigation focus + selection resolved after filtering/sorting +
explicit callback/dismissal ownership**. Use a grid for short names and a list for
long URLs. Leave transport, persistence and content parsing outside the popup.

Box is not uniformly stronger. Its selection adapter subclasses ListAdapter but
maintains a separate mutable list and manual notifications. Its generic picker
clamps out-of-range selection, but its history adapter directly indexes
`data.get(defaultSelect)`. History deletion sends removal notification before
mutating data, and several callbacks use bind-time positions. Do not copy these
patterns. Likewise, Box's whole-string `file://`/`./` replacement with
`clan://localhost/` is not a portable URI resolver.

## Implemented REF-020

Both Android site adapters now supply their Site activation state to the
existing BaseSelectAdapter selection contract. Both site dialogs scroll to
`adapter.getPosition()` instead of VodConfig's unfiltered configuration index.
Hidden rows and ranking can otherwise make the popup target the wrong site or
an index beyond the displayed list. Missing selection falls back to the first
row; an empty or dismissed popup skips the posted scroll.

CustomRecyclerView already requests focus on the scrolled row. This change
corrects its target, preserving the existing timing and platform behavior.
No new portable business policy, module, Java migration or copied Box code was
introduced. Desktop already owns selection through JComboBox/JList models,
so Android configuration-index misuse has no analogous desktop path here.
Box's top-level license is AGPL-3.0; this is conceptual adaptation using TV's
existing adapter API, not source transplantation.

## Observed contract coverage

These are the sampled Box dialects, not universal provider conformance.

| Contract | Android mobile/leanback | Desktop | Evidence and limitation |
| --- | --- | --- | --- |
| Site key/name/hide and current selection | Covered for sampled presentation contract | Covered for source-choice presentation; platform layout differs | Box HomeActivity filters hide before index lookup; TV SiteAdapter filters/ranks before selection; desktop CatalogPanel uses source choices |
| Classic `vod_play_from` / `vod_play_url`: `$$$` lines, `#` episodes, `$` label/ID separator | Covered through shared catalogue decoding and Android Flag/Episode adapters | Covered through shared CatVodCatalogCodec and CatalogPanel line/episode models | Box AbsJson samples the same wire shape; TV codec skips empty malformed episodes. No provider or media decoder was exercised |
| Stored API/live/EPG URL history | Partial UI equivalence: configuration history exists; separate EPG-history picker not established | Partial UI equivalence: configuration/depot selectors exist; no identical three-history popup | Box's Hawk history keys and 20-entry policy are implementation details, not a wire standard or mandatory product feature |
| `clan://localhost/` local path rewriting | Not covered by the inspected UrlUtil conversion path | Not covered by inspected shared ConfigResourceResolver | TV's sampled paths support file/assets/relative resources instead; clan scheme is left unchanged. External providers may implement more; no universal built-in clan guarantee |
| Android Dialog fullscreen/focus/Back lifecycle | Covered structurally by TV dialog builders/base and remote focus handling | Not applicable to Android window flags; Swing is the platform adapter | Physical D-pad navigation, keyguard/window transitions and exact focus timing remain untested |

## Validation and follow-up

Passed:

- `:app:testLeanbackArm64_v8aVerificationUnitTest` and
  `:app:testMobileArm64_v8aVerificationUnitTest`, filtered to
  `SiteDialogSelectionTest`: 2 cases each, 4 total, no failures. Both affected
  Kotlin/Java variants compiled as dependencies.
- `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`,
  `verifyCodeLifecycleIsolation`.
- `python3 scripts/check-leanback-remote.py`: 124 effective layouts, 300 explicit
  focus targets, 26 action contracts and 159 listener checks.
- `git diff --check`; checkpoint/inventory JSON parsing; Box remained clean.

The initial attempted Debug unit-test task names do not exist in the current
build. Task discovery resolved the supported Verification suites, which passed.
No device tests or visual rendering were performed.
Regression cases cover changed displayed order, an omitted row, absent active
selection and empty lists using both actual flavor adapters. These JVM checks
verify selection targeting, not rendered popup focus or device navigation.

Deferred: layout-driven focus scheduling replacing CustomRecyclerView's 50 ms
post, broader dialog lifecycle ownership (including empty mobile site popup
component callbacks), consistent URL-history focus, and visual/device QA. Those
need a separate bounded change and appropriate UI runtime coverage.

Reusable invocation: “Use tv-reference-abstraction to review Box URL-history
popup ownership and adapt a concrete TV lifecycle or focus defect, preserving
mobile behavior and existing local edits.”


## Visual follow-up: Box panel and row separation

The user requested ADB visual review and explicitly authorized the connected
`AUTHORIZED_TABLET_SERIAL` iPlay tablet after the old TV endpoint was unavailable.
The initial source-only review above did not establish visual quality. The
screenshots demonstrate a concrete defect: poster art bleeds through the site
picker and link-input dialog, and underlying settings labels show through the
history rows. The large grid and thick active-site outline also make navigation
focus hard to distinguish from the current selection.

Adapted Box's solid outer panel / separate inner rows, visible title, bounded
scrolling and compact spacing. The new implementation uses TV-owned semantic
tokens and Material widgets, without copying Box source or its AGPL resources:

- Android alert and custom-content overlays agree on a subtly translucent charcoal palette,
  bright text and darker secondary rows. The outer surface is 94% opaque and
  rows are 90% opaque, following the user’s preference to retain some transparency.
- Active-site selection uses a muted blue fill and thin outline; focus/press use
  a stronger blue fill and a 2 dp bright outline.
- Site pickers have localized titles (English, Simplified/Traditional Chinese),
  a smaller landscape grid width, shorter bounded list, 12 dp grid gaps and
  20 dp content padding. Row height retains a 48 dp minimum touch target.
  Popup geometry uses native configuration density rather than AutoSize’s mutable
  layout density, which inflated the first after screenshot’s rows and gaps.
- Configuration history has a localized title, compact padding/spacing and
  a 48 dp delete target. Site/history/link-input dialogs dim their background.
- Semantic attributes are declared in both Android themes and overlays and
  checked by the existing theme-contract script; no baseline exemption added.

Platform-neutral source, catalogue and persistence behavior is unchanged.
Desktop has native opaque Swing lists and was reviewed in the initial section;
this change concerns Android window/resource rendering only.

Additional visual corrections: the mobile resource override still painted active
selection like focus; it now uses the separate selected-row drawable. Leanback
state drawables were aligned with the same semantic roles. Alert panel color and
corner geometry are supplied explicitly to MaterialShapeDrawable, avoiding the
framework floating-background tint and AutoSize-inflated corners. Action-button
vertical margins/insets and padding are compacted on window attachment without
replacing caller show/dismiss listeners. Link-form outer padding is compact too.

Final validation:

- Mobile verification APK built and installed with `adb install -r`, preserving
  existing application data. Leanback Kotlin/Java compilation passed.
- 52 focused JVM cases passed: SiteDialogSelectionTest (2) and LayoutConfigTest
  (24), each on mobile and leanback arm64 verification variants.
- Theme contract, typography, UI dimensions, leanback remote checker, all three
  architecture guards and final `git diff --check` passed.
- ADB screenshots were captured and visually inspected for site selection,
  configuration history and link input on the authorized tablet. The site
  screenshot after a D-pad key shows different active and focused rows. Back
  dismissed site/history popups; tapping link Cancel returned focus to Home.
  Link action-button bounds are 96 physical pixels high at density 320, or
  48 native dp. No provider or playback verification is implied.
- Final screenshot evidence is saved outside the repository at
  `/home/developer/.codex/artifacts/2026-10-12-tv-popups/`: before-current.png,
  before-history.png, before-link.png, after-site.png, after-site-focus.png,
  after-history.png and after-link.png; hierarchy XML and build log accompany it.
- Original power value `15` and lock-disabled state `true` were restored. A
  transient Incognito toggle from navigation was restored to its original Off
  state, confirmed in the final history screenshot. Returned to the launcher.

Earlier source-only validation limitations above apply to that initial review;
this follow-up adds actual tablet screenshot and basic input verification.
Physical TV remote navigation, other popup families and provider execution remain
outside this bounded run. No commit, reference update or publishing performed.


## Player settings row-spacing follow-up

A fresh user-requested ADB screenshot showed Player setting, rather than an
alert popup. Its first four rows measured 206 px high with 69 px gaps on the
iPlay tablet (native density 320). The `SettingRow` minimum height and XML
margins were using AutoSize's inflated layout density.

A mobile-only UI spacing adapter now normalizes tagged settings rows after
inflation: 48 native dp minimum height, 8 native dp vertical padding and row
gaps, 12 native dp horizontal padding. Longer/multiline content can still grow.
No player values, callbacks, text sizing or leanback layouts were changed.

The mobile verification build and its theme/typography/dimension checks passed;
the standalone theme and leanback remote checks and final diff check passed.
Installed with `-r`, preserving data. The matching after screenshot/hierarchy
measured 96 px row heights and 16 px gaps for all four sampled rows. Evidence:
`before-player-settings.png/xml`, `after-player-settings.png/xml` and
`settings-spacing-build.log` in the artifact directory recorded above.
Original power/lock state restored; no settings value was edited in this run.


## Shared variables replace per-page spacing

The following app-wide follow-up supersedes MobileSettingsSpacing and the
separate dialog height alias. The canonical design-core metrics now include
`controlMinHeight` (48), `controlPaddingHorizontal` (12),
`controlPaddingVertical` (8) and `controlGap` (8). Generated Android/Kotlin
adapters feed ControlSpacing through the common TextScale hierarchy walk.
It applies to standard buttons and tagged rows/icons/groups on both settings
pages in mobile and leanback, and to late-attached dialog controls. Site/history
gaps and list adapters consume those same variables; site cells can grow for
large text. Desktop button defaults and their preferred minimum height consume
the shared metrics too, with a large-text regression test.

Validation: both Android variants compiled and mobile verification APK built;
52 focused Android regressions, 34 design-core tests and 6 desktop theme/widget
tests passed. Generated tokens, UI theme/typography/dimensions, leanback remote,
three architecture guards and diff checks passed. Final tablet screenshots are
recorded below after shared settings and action controls are inspected.

Final shared-spacing tablet evidence: `shared-settings.png/xml`,
`shared-player.png/xml` and `shared-link.png/xml` in the artifact directory above.
Sampled general settings, player settings and OK/Cancel actions all measure
96 px high (48 native dp); settings gaps measure 16 px (8 native dp). Grouped
source rows use the same gap token, removing leftover margins on their outer
containers. Both Android variants compile with the final tags; physical TV
rendering was not tested. Original power/lock settings restored after screenshots.
