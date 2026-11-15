# Video preview focus border turns black after fullscreen (2026-11-15)

## Symptom

In the leanback edition, when the video preview widget (`FrameLayout #video`,
top-left of `VideoActivity`) is focused, its thickening border should be white;
after visiting fullscreen and returning, the border renders as a solid black
band. Reported as "sometimes black" because the white border is what the user
sees before the first fullscreen round-trip.

## Failing configuration

Q601B TV box (192.168.123.94:5555, Android 10 / SDK 29, armeabi-v7a,
1080p output), leanback flavor. Reproduced on the debuggable normal app
(6.4.5-testing) and on the verification build at this revision.

## Root cause (traced on device, logcat probe `BorderProbe`)

`exitFullscreen()` restored the border with
`ResUtil.getDrawable(R.drawable.selector_video)`, which inflates through
`ContextCompat.getDrawable(App.get(), …)` — the **application context**. A
runtime probe comparing both themes:

```
BorderProbe: activity focusOutline=#ffffffff focusSurface=#66000000
             | app focusOutline=UNRESOLVED focusSurface=UNRESOLVED
BorderProbe: restored=android.graphics.drawable.StateListDrawable
```

The application theme does not resolve the `?attr/focusOutline` /
`?attr/focusSurface` strokes, so the restored selector paints nothing and the
widget's own opaque `android:background="@color/black"` becomes the visible
"border". The XML-inflated instance (activity theme) resolves correctly, which
is why the border is white before the first fullscreen visit. Pixel evidence:
after a fullscreen round-trip the 8dp border band measured constant pure
(0,0,0) while the preview video kept rendering inside the widget (frame diff
mean 71 inside, 0.0 in the band).

Secondary design issue: the pre-fix border's thick outer ring was
`?attr/focusSurface` = `#66000000` (40% black) with only a thin white inner
stroke, so over dark preview content the border already read as black.

## Changes

- `VideoActivity.enterFullscreen()` captures the widget's current foreground
  drawable; `exitFullscreen()` restores that captured instance instead of
  inflating through the application theme. The activity-theme drawable always
  carries the correct focus tokens, for every color theme variant.
- `shape_video_focused.xml` now strokes both rings with the new
  `videoPreviewFocusOutline` token (white in `Theme.App` and
  `Theme.App.Aurora`, leanback and mobile), matching the intended design: the
  whole thickening border is white over arbitrary video content.

## Validation on 2026-11-15 UTC

- Environment: clean clone at `b101cfc` + this change; JDK 21, Gradle 9.7.1;
  `-PwumingEphemeralSigning=true -PnativePythonEnabled=false`.
- Gate: `scripts/check-leanback-remote.py` PASS (124 layouts, 302 focus
  targets, 26 action contracts, 161 listener checks, 3 app-label locales).
- Device (Q601B, verification build, install sha recorded at push):
  - Focused preview before fullscreen: border band pure white (255,255,255)
    across the full 16px band — new white design.
  - OK → fullscreen → BACK → focused preview: border band still pure white on
    top and left edges; before this change the same path measured constant
    pure black.
  - Preview video keeps rendering inside the widget during both states.
- Not covered: mobile flavor runtime (design token added for parity; the
  drawable is leanback-only), Aurora theme runtime (token identical white),
  other ResUtil.getDrawable call sites that inflate `?attr` drawables outside
  an activity theme — they share the latent pattern and should be audited
  separately.
