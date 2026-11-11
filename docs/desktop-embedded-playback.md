# Embedded desktop playback

Desktop catalogue and Live TV playback use the existing mpv engine inside the
application by default. Settings → Playback → **Play inside the app** selects
this behavior; turning it off applies external playback to the next video.
The player shares the main content area with the browser, beside it in windows
at least 1100 logical pixels wide and below it in narrower windows. Navigation
stays in its own rail while browsing during playback. Drag the divider to adjust
the space given to each pane. The player header and controls use the same palette
as the rest of the app, including live appearance changes. Wrapping catalogue rows
recalculate their height after resizing. The minimum window size is 800 × 720
logical pixels (bounded by the available screen), keeping navigation and player
controls usable at larger UI scales. The timeline sits above
transport controls; **More…** contains subtitle loading and **Open externally**.

The player provides pause, absolute/relative seeking, volume/mute, speed, local
subtitles, fullscreen, Stop and **Open externally**. Space pauses/resumes and
Left/Right seek ten seconds when the video has focus. F11 toggles video
fullscreen while the embedded player is visible; Escape exits fullscreen before
performing navigation. Fullscreen gives the entire window to video: the player
header, permanent controls, borders and browser chrome are hidden. Moving the
pointer reveals a small floating timeline, pause/seek buttons and Exit fullscreen;
it hides after brief inactivity outside the bar. Hovering over the controls or
dragging the timeline keeps them visible. The overlay never resizes or reparents
the native video surface. Window maximize controls still maximize the application.
Aspect ratio and black bars remain mpv's responsibility.

## Engine and fallback

`EmbeddedPlaybackPanel` owns a persistent AWT Canvas. JNA 5.18.1 obtains its
native window ID, and `ExternalPlayerAdapter` supplies mpv's `--wid` option.
Fullscreen resizes the existing surface without reparenting/recreating its
native peer. mpv remains a separate process, so no decoder or libmpv native ABI
is loaded into the application process. JNA's small native bridge is used only
by the desktop UI adapter, with its licence notices in desktop resources.

The engine still uses the existing transport preparation, safe headers, HLS
compatibility relays, IPC state snapshots, history/resume, prewarming and episode
completion paths. Embedded selections currently replace the previous mpv
process; external sessions retain the existing compatible-session reuse path.
No second provider/catalogue resolution is performed for fallback.

The initial embedded path supports Linux's X11 AWT toolkit, including
XWayland when Java uses X11. Headless/native Wayland toolkits, missing native
window handles, and non-mpv players use an external window. An embedded
renderer startup failure or unavailable IPC control socket retries externally once.
Media/format failures are reported with a safe diagnostic and do not trigger an
external retry of the same invalid input. A failure after successful playback is
reported normally rather than looping.

CatVod HLS URLs can use an extensionless, per-provider loopback capability endpoint.
The shared HLS transport policy recognizes these alongside Android's fixed proxy
endpoint. Desktop validates their response and applies the existing HLS preparation
and purification pipeline. A URL or error page returned in place of a playlist is
rejected before mpv starts. Catalogue playback automatically tries the next line
for the same episode after resolution, preparation or player failure. Episode
names/numbers are matched rather than assuming every line has the same ordering;
missing or ambiguous matches are skipped. Each remaining line is tried once,
wrapping to earlier lines before reporting exhaustion and the last error. A
playback failure after startup carries the last observed position to the next line.
Stop, a newer playback request, a source change or a manual episode/line selection
prevents an older failure from changing the new selection.
The existing separate DLNA receiving-player lifecycle remains external.

**Open externally** also hands the current video to an external mpv window,
retaining position, pause, speed, loaded local subtitles and volume/mute.
It does not permanently change the preference. Prepared URLs/headers are passed
back through the existing player adapter rather than asking the provider to
resolve the episode again. Playback relays are closed once per retired session.
Stop cancels pending catalogue/Live TV preparation and suppresses pending
advancement/retry. App shutdown terminates owned player processes.

## Verification

Run ordinary headless desktop checks and build the distribution:

```sh
./gradlew-safe :desktop:check :desktop:installDist --offline
```

The explicit native-window tests require Linux, mpv, FFmpeg, xwininfo, Xvfb and
xvfb-run. They use synthetic local media and isolated preferences; they do not
load saved subscriptions or use Android devices/accounts:

```sh
xvfb-run -a -s '-screen 0 1280x900x24' ./gradlew-safe :desktop:embeddedPlaybackTest --offline
```

The native suite forces mpv's software X11 renderer and disables audio output.
It checks visible colored video pixels, the mpv child-window relationship,
keyboard pause, seek, subtitle tracks, speed, volume/mute, native-peer identity
through fullscreen/resize, responsive side-by-side/stacked layouts, light appearance,
Escape, external handoff and retained state, natural
catalogue episode advancement, and bounded fallback after a forced renderer
failure. Screenshots are written to `/tmp/wuming-embedded-render` by default
(override with `-PembeddedRenderDirectory=/absolute/path`).

GPU rendering, audible sound, native Wayland, physical display/DPI combinations
and provider-specific live services are not established by the Xvfb tests.
Production prefers mpv's GPU X11 output with its X11 renderer as a fallback.
For reference, see the [mpv embedding option](https://mpv.io/manual/stable/#options-wid)
and [JNA heavyweight-component API](https://java-native-access.github.io/jna/5.18.1/javadoc/com/sun/jna/Native.html#getComponentID-java.awt.Component-).

Latest local validation: [2026-11-04 results](desktop-embedded-playback-validation-2026-11-04.json).
