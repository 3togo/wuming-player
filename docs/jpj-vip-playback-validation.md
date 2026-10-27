# Verifying encrypted 荐片 playback

An earlier manual probe reported HTTP 400 for the VIP route's AES key. The
probe constructed mpv's generic header list by joining every header with commas.
The browser User-Agent contains `KHTML, like Gecko`, so mpv split it into two
headers. The resulting malformed request was rejected by the key server.

The desktop application already avoids this problem: `ExternalPlayerAdapter`
uses `--user-agent` and escapes commas in the remaining generic headers. The
probe failure did not demonstrate an application encryption-key defect.

Use `scripts/desktop/verify-playback.py` for future playback checks. It calls the
built distribution's actual command builder instead of duplicating its header
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
