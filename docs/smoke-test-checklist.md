# Smoke Test Checklist

Manual verification for crash-prevention and memory-leak fixes that cannot be covered by JVM unit tests due to Android framework dependencies.

## P0 - Crash Prevention (verify no crash on each scenario)

### Group.current() boundary check
- [ ] Open Live TV, switch to the last channel group, press next/previous rapidly beyond the channel list boundary — app should not crash

### Collect.create() empty list
- [ ] Open Favorites with zero collected items — app should not crash

### DriveFolderFile JSON parse
- [ ] Open a cloud drive (Ali/UC/Quark/WebDAV) with malformed folder metadata — app should show error or empty list, not crash

### PlayerManager.onPlayerError engine null
- [ ] Start playback of an invalid URL, wait for error callback — app should not crash with NPE on engine

### DepotDialog empty readme
- [ ] Open a depot/source with no readme URLs — dialog should not crash

### BilibiliLoginDialog JSON parse
- [ ] Open Bilibili login dialog with expired/invalid QR response — should not crash

### AccountCredentialBackupState JSON parse
- [ ] Restore credentials from a corrupted backup file — should show error, not crash

### LiveActivity current() null
- [ ] In Live TV, clear all channels then press channel up/down — should not crash

## P1 - Memory Leak / Concurrency

### PlayerManager @Volatile fields
- [ ] Rapidly switch between two videos 5+ times — no stale playback state, no crash

### WallConfig main-thread check
- [ ] Set wallpaper from a background thread context (e.g., after async image load) — should not corrupt UI state

### PyLoader synchronized spider
- [ ] Load the same spider concurrently from two threads — should not create duplicate instances

### Force.fetch() timeout
- [ ] Open a p2p/p3p/p4p live stream that fails to connect — should timeout after 10s, not hang indefinitely

### CustomWallView releaseResources idempotent
- [ ] Exit and re-enter the wallpaper view 3+ times — no crash from double-release

### Dialog dismiss in onDestroy (DlnaPairing, MobileKeep, History, Crash)
- [ ] Open each dialog, rotate device or press back quickly — no leaked window exception in logcat

### GuardedSpiderRuntime inputStream .use{}
- [ ] Run a spider that produces output — stream should be properly closed, no resource leak

## P2 - Medium Risk

### AlistDriveViewModel JSON chain
- [ ] Browse Alist drive with partially missing JSON fields — should not crash

### CastController null player
- [ ] Open cast control when no player is active — should not crash

### FragmentStateManager safe cast
- [ ] Navigate between fragments rapidly — no ClassCastException

### LiveApi fetchEpgDay
- [ ] Open Live TV with a channel that has a broken EPG URL — should not crash, EPG simply unavailable

### OpenDriveQrHandoff @Volatile
- [ ] Open QR pairing, scan code quickly before decode completes — no race condition crash

### ProviderDialog Bitmap recycle
- [ ] Open provider dialog with QR code, close it — no crash from recycled Bitmap access

### WebDAVDriveViewModel JSON fields
- [ ] Connect to WebDAV server returning incomplete JSON — should not crash

## P3 - Additional Optimizations

### DanmakuData NumberFormatException
- [ ] Load a video with malformed danmaku data (non-numeric color/position) — should not crash

### DriveFolderFile name/driveData null
- [ ] Open cloud drive with a file entry missing name field — should display empty name, not crash

### RemoteTestActivity onDestroy
- [ ] Open remote test activity, press back immediately — no crash or leak

## P4 - Dialog Lifecycle

### SettingFragment dialog dismiss
- [ ] Open Settings, open a choice dialog, press back — no leaked window

### SettingPlayerFragment dialog dismiss
- [ ] Open Player Settings, open a choice dialog, press back — no leaked window

### OkHttp null check
- [ ] Trigger a network request with null body parameter — should not crash

### WebDAVDriveViewModel getConfig
- [ ] Switch WebDAV drives rapidly — no NPE from getConfig()

### PlaybackActivity getSeekView null
- [ ] Start playback, immediately press seek before UI fully initialized — no crash

### AlistDriveViewModel fileObj fields
- [ ] Browse Alist drive with items missing name/url fields — skip gracefully, no crash

## P5 - Third Round

### Thunder null checks
- [ ] Play a Thunder/迅雷 link with missing parent/name — no crash

### FilterAdapter objectFrom null
- [ ] Open a site with filter data containing null objects — no crash

### PlaySpec getUa null
- [ ] Play a video with no UA configured in settings — should use empty UA, not crash

### UpdateManifest JSON safety (covered by unit tests)
- [x] Unit tests added: rejectsInvalidJson, handlesMissingFieldsGracefully, handlesNonObjectArtifactsGracefully, defaultsNameAndDescToEmptyWhenAbsent

### MacCmsCatalogSourceExecutor JSON safety
- [x] Covered by existing unit tests (transportAndMalformedPayloadErrorsAreNotRetried, emptyFallbackStopsAfterTwoRequests)

### Shell timeout
- [ ] Run a shell command that hangs (e.g., `sleep 60`) — should timeout after 30s and return empty string, not hang

### TVBus latch await timeout
- [ ] Open a TVBus stream that doesn't respond — should timeout after 15s, not hang

### TvAlertDialogBuilder context null
- [ ] Trigger TvAlertDialogBuilder with null context — should throw explicit NPE with clear message

### CapabilityTrackSelector rejected null
- [ ] Play a video with unsupported track format — should fallback gracefully, not crash

### JianPian p2p null
- [ ] Open a JianPian/简片 link with no p2p engine available — should not crash

### VideoRankManager catch assignment
- [ ] Open video ranking with malformed JSON — should show empty ranking, not crash

### Push sleep conditional
- [ ] Trigger push:// URL with no active activity — should not sleep unnecessarily
