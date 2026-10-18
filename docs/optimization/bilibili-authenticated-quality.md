# Bilibili account-aware playback quality

The Android player result adapter refreshes recognized local Bilibili DASH quality
lists through the shared account cookie jar. The preview page, local player, and
cast sender consume the same resulting quality list. Credentials stay in the
phone's HTTP client; they are not embedded in MPD URLs or sent to the television.
The existing provider still generates each selected quality's MPD.

`BilibiliPlaybackQualities` in playback-core recognizes the local `/proxy?do=bili`
DASH contract, constructs the fixed Bilibili API request, and builds choices only
from actual returned DASH video tracks. Advertised `accept_quality` entries alone
are insufficient: they can include qualities unavailable to the current session.
Duplicate codec alternatives produce one menu entry. Refresh chooses the highest
returned quality, and the user can select a lower one afterward. API failures
preserve the provider's original playable result. Login state is read per request,
not cached as an authorization result.

## Device evidence, 2026-09-28

On the authorized phone, the saved account session validated successfully. For
the same selected video, anonymous API results contained 480p/360p tracks, while
the saved session also returned 720p tracks. The provider's quality menu still
contained only 480p/360p even though its proxy could already generate a 720p MPD.
This isolated the defect to quality-list construction rather than the TV or the
media relay stripping login credentials.

After applying the adapter, the installed app's preview shows 720P/480P/360P and
local playback reports 1280x720 H.264. Casting that selected stream to the Coocaa
internal DLNA receiver produces successive HTTP 206 video/audio transfers and an
advancing receiver playhead. The TV's visible picture/sound confirmation is a
separate user observation. This source exposes a maximum of 720p for this account;
no 1080p entitlement is fabricated.

Core regression tests cover actual-track filtering, codec deduplication, quality
URL construction, rejected foreign/malformed source URLs, anonymous results, and
API failures. Both Android mobile and leanback builds and module boundary checks
pass. Evidence is under `app/build/reports/bilibili-auth-quality/` (local build
outputs, not a portable test fixture).
