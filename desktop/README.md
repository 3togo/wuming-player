# 無名 Linux desktop

This is a physically separate Kotlin/JVM application. It consumes only the generic TVBox
libraries and must never depend on `app`, Android modules, Android AARs, CatVod, Media3 or Room.

The client supplies a JDK/Swing shell, a safe external-player adapter for `mpv` or `vlc`, and a
headless architecture smoke check. Its first migrated feature is Live TV: load an HTTP(S) or local
M3U/TVBox TXT playlist, browse and filter channel groups, load XMLTV/XMLTV.GZ current/next
programme data, select alternate routes, and play streams with their HTTP headers. Direct
JavaScript sources run locally through GraalJS with no Java host access, a read-only per-source
module filesystem, explicit HTTP(S) and in-memory storage bridges, and bounded module/request
sizes. Supported guarded DEX/native providers from the built-in default site receive automatic
first-run setup, as authorized by the user. Custom sites use the GUI approval flow; this does not
introduce an Android application dependency into the desktop app.
Conventional `HTTP_PROXY`, `HTTPS_PROXY`, and `NO_PROXY` environment settings are honored by
configuration downloads, JavaScript modules, provider requests, and optional companion requests.

Canonical TVBox `type` 4 sources execute as bounded remote Spider HTTP endpoints and use the same
CatVod result decoder as local providers. Direct JavaScript sources may mint random loopback-only
capability URLs for their `proxy` callback; those URLs expire, are revoked with the provider, and
do not expose a general listener or filesystem. The CatVod `local` bridge persists quota-limited
state per provider identity under the user's configuration directory and clears it when that
provider is removed. It is deliberately separate from Secret Service account credentials.
File/stream host objects (`JSFile`, streamed `req`, and proxy buffer mode 3) remain unsupported.

Ordinary JVM CatVod JARs can be tested through an explicit-trust command. The artifact is checked
against a user-supplied SHA-256, rejected if it contains DEX, Android references, native libraries,
guard payloads, AAR/APK content, or nested JARs, and then executed in a memory-limited child JVM:

```bash
./gradlew :desktop:run --args='--test-jvm-spider CONFIG_URL QUERY SOURCE_KEY SHA256'
```

This child process is an isolation boundary for crashes, classpath pollution, and provider static
state; it is not an operating-system sandbox. The GUI never executes a remote JVM spider merely
because a downloaded configuration advertises it.

Guarded DEX/native packages use a physically separate runtime with unidbg in a disposable child
JVM. Its diagnostic entry point requires an explicit SHA-256 and ABI. Both `armeabi-v7a` and
`arm64-v8a` are supported package targets; custom-site packages require explicit approval (the built-in default has the authorized first-run setup described below):

```bash
./gradlew :desktop:run --args='--probe-guarded-spider ARTIFACT_URL SHA256 armeabi-v7a'
./gradlew :desktop:run --args='--prepare-guarded-spider ARTIFACT_URL SHA256 arm64-v8a'
./gradlew :desktop:run --args='--test-guarded-spider CONFIG_URL QUERY SOURCE_KEY SHA256 armeabi-v7a [LOCAL_ARTIFACT]'
./gradlew :desktop:run --args='--diagnose-guarded-spider CONFIG_URL QUERY SOURCE_KEY SHA256 armeabi-v7a [LOCAL_ARTIFACT]'
```

To fingerprint the currently configured spider bundle without trusting, decrypting, converting, or executing it:

```bash
./gradlew :desktop:run --args='--fingerprint-spider-artifact CONFIG_URL SOURCE_KEY'
```

Preparation is content-addressed under the desktop cache directory. A validated prepared JAR is
reused together with the ABI-matched native library and guard data; ARM emulation and DEX
conversion run again only after an input/tool version change or cache validation failure. The
native bridge remains inside the memory-limited provider child JVM. dex2jar 2.4 is fetched from its
official release and pinned by SHA-256.

### `NewCzGuard` support decision

As checked on 2026-08-11, the bundle supplied by `http://www.饭太硬.cc/tv` has SHA-256
`6a7442551dad7ac0bde979655fd95644ac695039848918a32240809146d7d15d`, contains both
`armeabi-v7a` and `arm64-v8a`. SafeLine rejects the configured Linux HTTP proxy path, including
requests made by Chromium through that proxy, while the same detail page returns normal HTML from
an Android device and from Linux when `www.4kcz.com` bypasses the proxy. With that bypass, the
isolated and normally verified desktop host passes search, detail and direct-playback resolution;
the 2026-08-11 probe returned two search results, one playback line and 153 episodes for `仙逆`.

Custom sites use the GUI's explicit artifact trust flow. The exact built-in default URL instead
automatically inspects and approves supported providers during initial startup setup. A configuration
label alone cannot enable unverified providers. After approval, the provider identity, exact SHA-256,
and selected ABI are remembered. An unchanged artifact is restored on later starts; a changed
artifact requires inspection and approval again. Do not repeat conversion or add browser automation. Retest when
the artifact SHA-256 or upstream page format changes. The shared catalogue codec classifies strong
WAF and browser-challenge pages distinctly from malformed provider JSON; it does not bypass them.

On startup, the desktop app automatically loads the last working configuration URL. A fresh
installation uses the bundled **饭太硬** source; a remembered depot selection is used if there
is no saved working URL. Temporary startup failures retry in the background after 2 and 5 seconds,
with progress shown on the catalogue screen. No initial **Load locally** click is needed.

The **Settings** home action opens configuration, which locally downloads and decodes TVBox JSON or image-plus-Base64 envelopes.
To change sources, select a built-in source or enter a URL and choose **Load locally**. If a navigation page is returned,
the importer can try its limited, explicitly advertised configuration fallbacks. It never asks an
Android companion or a generic public decoding relay. Imported direct-JavaScript sites are sent to
the **VOD** screen and executed through the shared `CatalogSourceExecutor` contract. To use the
guarded catalogue, choose **Enable verified guarded sources…**. The desktop client first downloads
and fingerprints the bundle without executing it, then shows its SHA-256, available ABIs, and every
verified provider that will be enabled. Verify the fingerprint, choose `armeabi-v7a` for older
TV-box compatibility, and approve that exact artifact once. Preparation and conversion run once
per artifact/ABI; each provider still receives a separate memory-limited child JVM. The UI enables
activation for providers measured on the desktop runtime. Verification labels distinguish
catalogue-only, discovery and cloud-search support; they do not guarantee every playback line.
**糯米 (`Nmyswv`)** and **Bili (`Bili`)** passed home, category, search and episode-detail
checks on 2026-09-16 and are enabled as **verified desktop catalogue · playback unverified**.
Their sampled playback requests returned no usable URL. Other unmeasured or failing providers
remain disabled. See [the verification record](../docs/desktop-site-verification.md) for the
artifact fingerprint, results and limits.

For a provider outside that list, select its row and choose **Check selected spider…**.
Settings inspects the bundle, asks for approval of that provider and exact SHA-256,
then probes home, at most two categories, an optional search term, and up to three
episode-detail pages. Successful catalogue or discovery checks enable the source
without changing the allow-list. An empty result can be retried with another search
term. The check does not request playback or save cloud files.
Runtime status and checks are also available for sites whose catalogue visibility
is disabled by the configuration. Their verification is shown independently;
checking them saves the result without changing that visibility setting.
The VOD selector follows the same configured site names and order as Settings.
Sites without an active runtime stay visible with a setup, unsupported-runtime, or
configuration-disabled label. Selecting one explains its status; Search All only
queries active searchable sites. Activating more runtimes does not reorder the list
or replace the remembered site selection.
`searchable: 0` disables search only: music, sports and education providers can
still browse Home/categories. Only explicitly hidden sites and push/input handlers
are excluded from catalogue activation. Built-in default setup also enables newly
eligible verified providers when earlier provider approvals already exist. Empty
pages show a visible explanation or search suggestion rather than a blank grid.

The common desktop spider adapter routes guarded DEX and plain DEX to the existing
compatibility host, and portable JVM JARs to the isolated JVM host. Unsupported packages
and missing classes remain explicit errors; this does not provide every Android API.
Rows show **desktop spider adapter available** before activation and **desktop spider
runtime active** after activation, instead of continuing to say a runtime is required.
Successful ordinary catalogue and playback requests also record verification without
extra probes. Evidence survives restart, is scoped to provider configuration, artifact
SHA-256, ABI and runtime revision, and expires separately per capability after 30 days.
Playback evidence means a URL was resolved, not that a player rendered the media.

Libvio's UC and Quark lines are
recognized as account-dependent and fail immediately with an explicit account-required result.
The **Accounts** home action provides desktop-native QR login, verification and logout for Aliyun
Drive, Baidu Netdisk, Quark and UC without an Android companion. Credentials are durably stored in
the current user's Linux Secret Service and are restored on later launches; they are never written
to the checkout or build tree. The project-root launcher installs `libsecret-tools` and `mpv` on Debian/Ubuntu
when either package is missing. Direct binary launches leave Login disabled until `secret-tool` is available,
instead of pretending that a memory-only session was saved. Quark shared-file original playback uses
those credentials through the shared cloud runtime; Ali, UC and Baidu playback resolution remains
unsupported on desktop. The Quark resolver tracks and reuses exact saved copies, waits for completed
save tasks, recovers recorded pending tasks, and conservatively reclaims old inactive records.
Accounts → Saved copies lets you review records, reconcile tasks, clean eligible copies and explicitly
forget uncertain or old-session local records. Interrupted submissions without a task ID block
automatic retries to avoid duplicate saves. It does not sweep arbitrary cache-folder
contents or purge the recycle bin. See [the platform capability matrix](../docs/platform-capabilities.md)
for lifecycle limits and the distinction between fixture tests and verified real playback.
AAR-backed and other unsupported spider sources remain classified but disabled pending an explicit
support decision.

The last successfully validated TVBox URL is persisted and loaded automatically at application
startup; a failed URL never replaces it. Settings also remembers the selected site by its stable
provider key, so renamed or reordered labels do not corrupt the selection. The **VOD** screen requests each executable source's home
feed immediately, then continues to support explicit search. Its first source entry is
always **Show All**, followed by provider-specific choices. Search results use a responsive poster
grid; visible posters and the initial result batch load automatically, including CatVod inline
`Referer`, `Cookie`, `User-Agent`, and JSON header metadata. It supports search, detail,
playback-line/episode navigation, direct HTTP(S) resolution, and a cancellable source audit.
The Type selector exposes Android-compatible media types, configured category order, initial filter
values, folder drill-down, and previous/next category pages. Under **Show All**, type labels include
their owning site and category requests remain isolated to that provider. Provider action cards
remain visibly distinct from playable media and are not sent to the detail parser. The
semantic **Show All** or provider selection is persisted across configuration reloads and guarded
provider restoration. Successful playback launches create a bounded, file-backed history entry;
the **Keep** and **History** controls expose those entries, while detail reload
restores the last playback line and episode. Previous/next episode controls and favorite toggling
follow the same platform-neutral policies as the Leanback user-library workflow. When VLC or mpv
reports a natural end-of-media event, desktop resolves and starts the next episode automatically;
closing the player manually cancels that advance. Clear **Play next automatically** to stop the
advance; that preference is retained across restarts. Desktop also parses the configuration's
host-scoped HLS rules and runs VOD and Live manifests through the shared Android-derived playlist
purifier. Removed ad segments never reach VLC/mpv; nested master playlists, provider headers, and
image-wrapped transport streams remain supported, with the original manifest used if filtering
fails. Playback position,
speed, opening/ending skips, and danmaku remain owned by the external player rather than
being falsely emulated by the Swing shell.

### Video casting with Android phones

Open **Push** for two-way DLNA video casting. Connect the desktop and phone to the same LAN.

To send a catalog episode, open its details, select the episode, and choose
**Cast to TV** beside **Play episode**. The app resolves the video and opens Push
with the title, URL and required request headers already prepared. Choose a DLNA
receiver and select **Send video**. Local playback continues until the receiver
accepts the request, then stops. The TV starts the selected episode from the
beginning; automatic position transfer and cast episode queues are not included.
Use Push's Play, Pause and Stop controls for the outgoing session.

Catalog casting uses a receiver-scoped LAN relay for HTTP media and HLS playlists,
segments and encryption keys. Provider credentials stay on the desktop and are
scoped to their original host, including across redirects. The TV must support
the stream's codec and format; this does not transcode unsupported video.

- **Phone → desktop:** select **Enable receiving** (requires `mpv`), then choose **TVBox Desktop**
  in the Android video's DLNA cast menu. The phone can play, pause, stop, seek, mute, and change
  volume. **Stop receiving** removes the renderer and closes its dedicated mpv session. Receiving
  starts disabled on every launch and stays active while navigating to other desktop screens.
- **Desktop → phone / TV:** enable a DLNA **renderer/receiver** in the Android app (a sender-only
  cast app will not appear), choose **Find receivers**, select the device, enter a direct HTTP(S)
  video URL or choose a local video file, and select **Send video**. Play, Pause, and
  **Stop / disconnect** control the outgoing session. A selected file is served through a random
  capability URL with HTTP range support; disconnect revokes that URL after attempting Stop, including when the receiver is offline.
- **Open locally** retains the existing direct URL/file playback workflow.

While receiving is enabled, other devices on the LAN can initiate/control playback. The receiver
accepts HTTP(S) media only, not remote requests to open desktop files. Casting uses UDP multicast
port 1900 and dynamically allocated local HTTP ports; firewall rules or Wi-Fi client isolation can
prevent discovery or playback. UPnP control traffic bypasses HTTP proxies.

This implements video casting, not Android screen mirroring, Miracast, Chromecast/Google Cast, or
AirPlay. The phone must use DLNA, and receiving on the phone needs an app that provides that mode.
The current outgoing workflow is direct URLs and selected files in Push; VOD/Live playback is not
automatically redirected. Local playlist files with relative segments, DRM, provider headers/cookies,
transcoding, and automatic cast episode queues are outside this initial desktop implementation.
Public HTTP(S) playlists depend on the target player's format support. The desktop receiver uses
mpv; VLC remains available for ordinary local playback.

Validation includes SSDP discovery against a test renderer, real HTTP descriptors/SOAP controls,
URL/instance validation, local-file range/HEAD requests and revocation, and headless mpv playback
with pause, seek, volume, and stop. A physical Android phone/strict DLNA receiver compatibility test
is still required before release.

Android companion compatibility code is not exposed in the primary Catalogue workflow and is not
involved in local configuration or local JavaScript execution. See
[`docs/desktop-companion-api.md`](../docs/desktop-companion-api.md) for pairing and limitations.
The desktop shell follows the Leanback home composition: a dark toolbar with logo/title and clock,
then 100-pixel focus tiles ordered **Vod**, **Live**, **Search**, **Keep**, **Push**, **Setting**.
Desktop-only **Accounts** follows that stable primary row. Desktop keeps this row visible on every
feature screen so mouse and keyboard users can switch directly. The toolbar's **← Back** button
and Escape share the same navigation: return from details or category history first, then return
home. The button is disabled at home. Clicking the TVBox title returns directly home.
Loading a TVBox configuration populates both destinations: Catalogue receives its executable VOD
sources, while Live TV receives a named playlist selector. Startup probes the first few streams and
selects the first verified reachable channel instead of treating a merely parseable stale list as
working. Desktop also offers **CCTV · daily checked routes** first, backed by the maintained
`best-fan/iptv-sources` CCTV playlist. Entries with the same TV-guide identity are merged into one
channel with alternate routes. Clicking play checks the selected route and up to five alternates,
then launches the first reachable one; a stale route therefore does not make the whole channel fail.

```bash
./gradlew :desktop:run
./gradlew :desktop:run --args='--smoke'
./gradlew :desktop:installDist
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --inspect-config 'http://www.饭太硬.cc/tv'
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --test-live-source 'http://www.饭太硬.cc/tv'
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --test-js-source 'http://www.饭太硬.cc/tv' '英雄联盟'
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --test-cloud-login
```

The cloud-login smoke command creates and polls a redacted QR challenge for Aliyun Drive, Baidu
Netdisk, Quark and UC without printing the QR content or storing credentials. The project-root launcher runs the
installed distribution from a private temporary snapshot, so rebuilding the checkout cannot replace
classes underneath an already-running GUI.

Persistent redacted traces are written under the directory reported by
`./scripts/desktop/run-tvbox-desktop.sh --log-directory`. They include configuration response fingerprints,
cache decisions, guarded child-process requests, result sizes, catalogue counts and playback
decisions; queries, episode IDs, extensions and playback URLs are represented only by lengths and
short SHA-256 tags. External-player launches, exit codes, buffering, demux and first-frame
milestones are also recorded without exposing the media URL. VLC launches use a separate visible
instance so an old or minimized VLC process cannot swallow a channel selection.

The guarded command-line smoke test requires a non-empty catalogue from Home or Search, followed by
detail and episode results. It checks one episode from every playback line plus the middle and last
episode of the first line, fails on an unusable direct line, and reports recognized account-dependent
cloud lines as explicit skips. This permits valid home-only and search-only providers without
claiming a provider works when both catalogue paths are empty.

When an approved provider bundle changes, working sources remain enabled. Settings marks the
impacted sources as **bundle updated** and offers **Review updated bundle…**. Review the old
and new SHA-256 and approve the affected sources together; approvals are saved only for
sources that activate successfully. An update does not silently replace an exact-hash approval.

The catalogue footer has a **Thumbnails per page** control (6–120, default 24), saved across
restarts. In a category, both sets of Previous/Next buttons navigate pages of that size:
a 40-thumbnail page combines two requests from a source that returns 20 videos at a time.
Unused videos are retained for the next page; changing the size reuses already fetched results.
Home recommendations, search and saved lists paginate their available results locally.
Only the displayed thumbnail page is prefetched. On startup, the remembered approved provider
is restored first so its catalogue can load while other providers start in the background.

When starting an episode, the playback area shows the current stage and elapsed seconds
through URL resolution, player preparation and mpv startup. Preparation runs off the UI
thread, and Stop cancels a pending launch. The timer completes when mpv reports playback
has started (or shows a failure); it does not invent an estimated completion time.

Catalogue thumbnails include WebP decoding through the ImageIO plugin registry. Posters retain
544 × 736 pixels in the bounded memory/disk caches and use progressive bicubic resampling for
cards and details. Multi-resolution icons preserve detail on HiDPI screens and enlarged cards;
older 136 × 184 disk entries are refreshed automatically when requested. Proxy settings are
still honored for images; add a failing image CDN to `NO_PROXY` when the configured proxy cannot
complete its TLS connection.

For Live TV, choose **TV** and enter a playlist URL or choose a `.m3u`, `.m3u8`, or
`.txt` file. Standard metadata (`group-title`, `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-url`), inline
headers and common VLC HTTP directives are preserved. A declared `tvg-url`/`x-tvg-url`/`url-tvg` is loaded in
the background and matched by stable channel ID before conservative name fallback. Spider-generated JSON catalogues and
Android-specific DRM directives remain outside this milestone.

### Build a Debian package

Run the packaging script from the repository, without sudo:

```bash
./scripts/desktop/build-desktop-deb.sh
# Use dependencies already in the Gradle cache:
./scripts/desktop/build-desktop-deb.sh --offline
# Set release metadata explicitly:
./scripts/desktop/build-desktop-deb.sh --version 0.1.0-1 --maintainer 'Your Name <you@example.org>'
```

The script runs `:desktop:installDist` and packages its runtime JARs, launcher, application menu
entry and the shared Android launcher icon. The window/taskbar and toolbar use the same
artwork, sourced directly from `app/src/main/ic_launcher-playstore.png`. It writes the `.deb` and a `.sha256` checksum to `desktop/build/deb`.
The default version comes from Gradle; `0.1.0-SNAPSHOT` becomes `0.1.0~snapshot-1` so a subsequent
`0.1.0` release sorts after it. The default maintainer is a local-build placeholder; set
`--maintainer` for distributed releases. `--output-dir` selects another destination, and
`--skip-build` packages the existing distribution as-is (supply `--version` if version metadata
has not yet been generated). Run `--help` for all options.

Build requirements are the normal project Java/Gradle environment plus `dpkg-deb` 1.19 or later
and standard GNU command-line tools. The build uses
[`dpkg-deb --root-owner-group`](https://manpages.debian.org/bookworm/dpkg/dpkg-deb.1.en.html)
to produce root-owned package contents without running the build as root. Build on the intended
Linux host architecture: amd64 or arm64. A JRE is not bundled.

Install the generated file with APT, which resolves the full Java 17-compatible desktop runtime,
mpv, and Secret Service tool dependencies:

```bash
sudo apt install ./desktop/build/deb/tvbox-desktop_0.1.0~snapshot-1_amd64.deb
tvbox-desktop
tvbox-desktop --smoke
```

Use the actual filename printed by the script when selecting a different version or architecture.
The app is installed in `/usr/lib/tvbox-desktop`, with `/usr/bin/tvbox-desktop` and a desktop menu
entry. Launching the installed application requires neither the source checkout nor Gradle.
The package build does not install or launch the GUI. Existing user caches, settings and account
storage remain in their usual per-user locations. Bundled dependencies retain their embedded
license notices, and the project license is installed under `/usr/share/doc/tvbox-desktop`.

Initial release targets are Linux x86-64 and arm64 with Java 17 or newer. An embedded player
remains deferred; Debian packages use the system's external mpv player.

## Public and private editions on Linux

The public repository owns the entire desktop application and the Linux launch
and Debian packaging scripts. Private builds export an exact public commit and
add their catalogs and Android SDK adapters; they do not maintain another desktop
source tree. Android-only SDKs do not become Linux playback engines.

Run `scripts/desktop/run-tvbox-desktop.sh` or
`scripts/desktop/build-desktop-deb.sh` from either repository. In the private
repository these commands compose the pinned public source and delegate to those
same scripts. Private outputs live under `.build/workspace/desktop/build`.

`desktopPackage` in the edition configuration selects the Debian package and
installed command: `tvbox-desktop` for public, `tvbox-desktop-private` for private.
The Gradle distribution records that identity in `share/desktop-edition.json`.
Packaging reads this built metadata, including with `--skip-build`, rather than
relabelling an existing distribution using the current checkout configuration.
Older distributions must be rebuilt once to supply the metadata. Both packages
keep the application name 無名. Package files are separate; existing application
preferences and user data retain their current locations.

## Startup and thumbnail diagnostics

The launcher prints the log directory at startup. Logs are normally under
`~/.cache/tvbox-desktop/logs/` (or `$XDG_CACHE_HOME/tvbox-desktop/logs/`). Provider
activation records preparation, completion and failures with the original stack
trace. Catalog image counts distinguish missing provider pictures from download
failures; successful thumbnail requests record whether the pixels came from disk
or the network.

Guarded-provider native execution is an optional `GuardedNativeProvider` service.
The public distribution contains no native implementation. A private edition may
supply that port without replacing shared desktop, preparation or catalog code.

The private launcher locks its generated workspace during composition and build,
then releases that lock after the shared launcher copies the standalone runtime.
An open desktop window therefore does not block later builds. A busy private
workspace reports an error immediately; a suspended older launcher must be
resumed or closed to release the lock it already owns.

### One GUI instance and tray readability

Only one GUI instance per Linux user is allowed by default. A second launch reports
that 無名 is already running; use its tray icon to reopen the window. The lock
remains held while the app is hidden and is released by the OS when the process
exits, so a leftover lock file does not block future launches. Diagnostic commands
such as `--smoke` do not take the GUI lock.

Under **Settings → Appearance**, **Prevent multiple app instances** is checked by
default. Clear it to allow additional GUIs. The setting applies to future launches;
close existing instances after changing it. `--allow-multiple-instances` overrides
the setting for one launch. Public and private desktop editions share this guard,
just as they share the current user's desktop settings.

The tray popup menu uses bold text at 18 logical pixels, scaled with the desktop
UI. Native tooltip styling remains controlled by the Linux desktop.

**Use one video playback window**, also enabled by default under Appearance,
reuses the current mpv window for catalog/live playback when its request headers
are compatible. Otherwise the app closes its previous player before starting the
replacement. Clear the option to permit independent player windows. This setting
applies to future playback requests without restarting the app.
