# Verifying encrypted 荐片 playback

An earlier manual probe reported HTTP 400 for the VIP route's AES key. The
probe constructed mpv's generic header list by joining every header with commas.
The browser User-Agent contains `KHTML, like Gecko`, so mpv split it into two
headers. The resulting malformed request was rejected by the key server.

The desktop application already avoids this problem: `ExternalPlayerAdapter`
uses `--user-agent` and escapes commas in the remaining generic headers. The
probe failure did not demonstrate an application encryption-key defect.

Use `scripts/desktop/verify-playback.py` for future playback checks. It calls the
built distribution's actual command and process-environment builders instead of duplicating its header
encoding. It requires a JDK, mpv, and a resolved provider result as JSON with
`parse`, `url`, and optional `header` fields. Headers may be a JSON string or
object. Keep the provider host alive while playing its loopback URL.

```sh
python3 scripts/desktop/verify-playback.py \
  --distribution /path/to/tvbox-desktop \
  --playback-json /private/scratch/playback.json \
  --output /private/scratch/playback-evidence
```

The check plays a 20-second sample, retains player diagnostics, and requires a
normal exit plus both video and audio output without key/HTTP 400 errors.
Inputs and diagnostics can contain signed URLs; keep them outside Git. The
script inherits network settings. If a proxy cannot reach a video CDN, test the
network route separately and record any environment changes with the result.
Changing a test's network environment does not establish playback through the
application's usual proxy configuration.


The desktop player now adds `video.cqwqq.com` to its child-only proxy exclusions.
This exact-host exception lets JPJ VIP segments connect directly when the normal
proxy fails their TLS connections. Existing proxy values and exclusions are
preserved; the desktop and provider processes keep their original environments.
The shared playback policy owns the exception and the desktop process adapter
applies it to mpv. The verification tool uses that same process adapter.

The theme uses Java's logical SansSerif font so installed fonts can supply CJK,
emoji, and playback-symbol glyphs. A fixed physical CJK font disabled that
fallback and displayed missing-glyph boxes even with emoji fonts installed.
