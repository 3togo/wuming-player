# FreeBox investigation and adopted abstractions

Status: implemented foundations and integrations  
Reviewed: 2026-08-09  
Reference: [kknifer7/FreeBox](https://github.com/kknifer7/FreeBox), commit
[`86ca1aa7`](https://github.com/kknifer7/FreeBox/commit/86ca1aa7a6c40f8867a7170d15b6acd79ab230d4)

## Outcome

FreeBox is a JavaFX desktop player and provider-development tool. Its most useful ideas for this
Android project are boundaries and workflows rather than portable implementations. This project
adopts five concepts:

1. active, stage-based provider diagnostics;
2. a pluggable subtitle-provider and secure download boundary;
3. an ordered HLS transformation pipeline with typed diagnostics;
4. a cancellable Spider Lab session for debug tooling;
5. a platform-neutral multi-channel EPG timeline model.

FreeBox and this project both declare GPLv3. Nevertheless, the implementations here are native to
this project's architecture: they avoid JavaFX, GraalVM, desktop file watchers, and FreeBox's
known correctness and cancellation issues.

## Investigation details

### Source audit

FreeBox's source audit is a hard-coded chain of search, home, category, detail, and playback
auditors. Its useful contribution is the stage/result model: each operation has status, elapsed
time, and supporting evidence. The implementation was not ported because it:

- checks only the first category, video, play line, and episode;
- uses a hard-coded search keyword;
- marks playback usable largely from a URL prefix rather than probing media;
- has weak cancellation and no consistent exceptional-completion path;
- retains raw request and response bodies that can contain credentials.

The adopted implementation is under
[`api/diagnostic`](../app/src/main/java/com/fongmi/android/tv/api/diagnostic). It provides:

- `ProbePlan` for selected stages, keyword, sample count, timeout, and retry count;
- `ProviderDiagnosticRunner` for per-run cancellation and per-stage timeouts;
- `ProbeResult` with stable status/failure codes and sanitized evidence;
- `SiteDiagnosticTarget`, which reuses `SiteApi` and performs a bounded HTTP range probe;
- `DiagnosticSanitizer`, shared by diagnostics and Spider Lab traces.

Diagnostic results are deliberately separate from `SitePerformance`. Synthetic probes must not
silently outweigh real playback reliability measurements.

Example:

```java
try (ProviderDiagnosticRunner runner = new ProviderDiagnosticRunner()) {
    ProviderDiagnosticRun run = runner.start(
            new SiteDiagnosticTarget(site), ProbePlan.standard("example title"));
    List<ProbeResult> results = run.future().get();
}
```

Only invoke playback and stream stages from an explicit diagnostic action. Provider resolution may
perform authentication-dependent work and must never run on the UI thread.

### HLS filtering and relay

FreeBox advertises filename-sequence, filename-length, discontinuity, and child-playlist
heuristics. The implementation was rejected because several segment conditions use the equivalent
of `not .ts OR not .png`, which is always true, and its live-network tests contain no assertions.
Merging every child of a master playlist also risks concatenating alternative renditions instead
of choosing a rendition.

The adopted code is under [`media/hls`](../app/src/main/java/com/wuming/android/tv/media/hls):

```text
validate → ordered transforms → preserve last valid playlist → report changes/warnings/confidence
```

- `HlsPipeline` composes pure transforms and fails open when a transform throws or returns invalid
  output.
- `HighestQualityVariantTransform` selects by resolution then bitrate.
- `UriRewriteTransform` resolves both line and `URI="..."` references before mapping them.
- `LegacyAdRemovalTransform` adapts the existing configured purifier without pretending that its
  heuristic result has high confidence.

`CastMediaRelay` and the playback purification path now use this boundary. Network child fetching
remains outside transforms, preventing a pure playlist operation from unexpectedly multiplying
requests.

### Spider hot reload and inspection

FreeBox's useful developer workflow is invoke → inspect raw result → edit → reload → invoke again.
Its JavaFX controller/file-watcher code is not portable, and its GraalJS context grants unrestricted
host and I/O access.

The adopted code under [`api/debug`](../app/src/main/java/com/wuming/android/tv/api/debug) supplies:

- `SpiderInvocation`, an operation plus explicit arguments and parameters;
- `SpiderTrace`, which captures elapsed time, output, and structured failure data after redaction;
- `DefaultSpiderSession`, enforcing one active invocation with cancel, reload, and destroy;
- `SpiderFactory`, allowing a future Android debug screen, ADB tool, or test to decide how a fresh
  isolated runtime is constructed.

No release UI or arbitrary external-file watcher is enabled. Provider Lab uses the existing
provider process and does not grant a spider new filesystem or host access.

Debug builds now expose **Provider Lab** by long-pressing the Settings version row. The shared
screen can run metadata-only or explicitly confirmed full diagnostics, inspect spider home/search
results, reload the session, and cancel the active operation. Release builds reject the entry
point, and the activity is not exported.

### Subtitle search and download

FreeBox scrapes one subtitle website and exposes search, archive navigation, and selection. The
product capability is useful, but copying a single-site HTML parser would make behavior depend on
unstable selectors and an external site's unversioned markup.

The adopted code under
[`media/subtitle`](../app/src/main/java/com/wuming/android/tv/media/subtitle) separates:

- `SubtitleProvider`: provider-specific search and candidate resolution;
- `SubtitleSearchEngine`: registration, deterministic provider ordering, and failure isolation;
- `SubtitleCandidate`/`SubtitlePage`: provider-neutral result models;
- `HttpSubtitleDownloader`: HTTP transport using the app's shared network stack;
- `SubtitleDownloadService`: bounded direct-file and ZIP storage.

Archive extraction accepts only SRT, ASS, SSA, VTT, and SUB files; limits compressed bytes,
expanded bytes, and entry count; normalizes generated filenames; rejects symlink destinations and
path traversal; and never overwrites an existing subtitle. The bundled Assrt adapter uses the
service's official token-authenticated JSON API rather than scraping HTML. Its token is accepted
only through the debug Provider Lab and retained in process memory; once configured, an online
search action appears in the subtitle track dialog. Production credential storage and a normal-user
settings surface remain deliberately out of scope. Assrt attribution is retained in the search UI.

### EPG overview

FreeBox's EPG fetcher is simpler than this project's existing JSON/XMLTV and timezone-aware data
layer. Only its multi-channel timeline presentation is net new.

[`ProgramGuideLayout`](../app/src/main/java/com/wuming/android/tv/media/epg/ProgramGuideLayout.kt)
maps channel programmes into normalized row cells for a chosen time window. It clips programmes at
window boundaries, sorts cells, identifies the current programme, and reports a normalized now
marker. Android TV, mobile, and remote clients can render the same layout without copying EPG
fetching logic.

## Explicit non-adoptions

- JavaFX controllers, VLCJ/mpv desktop integration, and desktop file watching;
- FreeBox's HLS filtering algorithms or master-playlist merging;
- its blocking WebSocket/Future coordinator, whose timeout choice appears inverted;
- unrestricted Graal host/I/O access;
- raw diagnostic request/response persistence;
- single-site subtitle HTML parsing;
- external-player code, because this project already supports MX Player, Reex, Kodi, and VLC.

## Verification expectations

Fast JVM tests cover transformation ordering/fail-open behavior, URI and variant handling,
timeline clipping, provider failure isolation, secure subtitle extraction, diagnostic timeout and
redaction, and Spider Lab reload/cancellation. Run:

```bash
./gradlew :app:testKeyLogic
```

Device tests remain necessary before treating the debug UI and online-subtitle flow as release
ready, or before adding an EPG grid surface. In particular, verify provider-process isolation,
focus behavior, process recreation, subtitle selection, and cancellation on both mobile and
Leanback flavors. The Assrt token entry is intentionally a debug-only integration seam until those
tests and a credential-storage decision are complete.
