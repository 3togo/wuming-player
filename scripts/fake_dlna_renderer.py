#!/usr/bin/env python3
"""Fake DLNA MediaRenderer for testing TV app casting WITHOUT a real TV.

- Standard UPnP/SSDP discovery (jUPnP compatible): SSDP M-SEARCH replies + NOTIFY alive.
- AVTransport SOAP service: SetAVTransportURI / Play / Pause / Stop / Seek /
  GetTransportInfo / GetPositionInfo / GetMediaInfo.
- When SetAVTransportURI arrives, it emulates the real TV's fetch pattern
  (concurrent playlist fetches, Range probes, sequential segments) and writes
  a byte-level validation report to /tmp/fake_renderer_report.json.
- Selectable receiver profiles for working HLS, immediate HLS rejection, and
  the observed HP HD03 fetch-then-STOPPED behavior.
- Machine-readable `/debug/state` and `/debug/report` endpoints.

Usage: python3 fake_dlna_renderer.py [http_port] [profile]

Profiles: hls-capable, hls-reject, hp-hd03 (default)
"""
import argparse
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def detect_host_ip():
    """Auto-detect the LAN IP the renderer should advertise.

    Hardcoding an IP breaks the moment the machine moves networks (different
    subnet, DHCP lease change). An advertised LOCATION pointing at a dead IP
    makes the TV app discover the renderer then fail every description.xml
    fetch — and an IP_ADD_MEMBERSHIP on a non-local address throws OSError,
    silently killing the SSDP thread so the renderer is never discovered at all.

    Priority: FAKE_RENDERER_IP env var > UDP-connect probe (no packets sent)
    > `ip route get` to the SSDP multicast address > `hostname -I`.
    """
    env_ip = os.environ.get("FAKE_RENDERER_IP", "").strip()
    if env_ip:
        return env_ip

    def good(ip):
        return ip and not ip.startswith("127.") and not ip.startswith("169.254.")

    # UDP connect only consults the routing table; no packets leave the host.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("239.255.255.250", 1900))
            ip = s.getsockname()[0]
            if good(ip):
                return ip
        finally:
            s.close()
    except OSError:
        pass

    try:
        out = subprocess.check_output(
            ["ip", "-4", "-o", "route", "get", "239.255.255.250"],
            stderr=subprocess.DEVNULL, text=True, timeout=3)
        m = re.search(r"src (\d+\.\d+\.\d+\.\d+)", out)
        if m and good(m.group(1)):
            return m.group(1)
    except Exception:
        pass

    try:
        out = subprocess.check_output(["hostname", "-I"],
                                      stderr=subprocess.DEVNULL, text=True, timeout=3)
        for tok in out.split():
            if good(tok):
                return tok
    except Exception:
        pass

    print(f"[renderer] {time.strftime('%H:%M:%S')} "
          f"detect_host_ip: fell back to 127.0.0.1 — renderer will NOT be reachable from other hosts",
          flush=True)
    return "127.0.0.1"


PROFILE_HLS_CAPABLE = "hls-capable"
PROFILE_HLS_REJECT = "hls-reject"
PROFILE_HP_HD03 = "hp-hd03"
VALID_PROFILES = {PROFILE_HLS_CAPABLE, PROFILE_HLS_REJECT, PROFILE_HP_HD03}


def parse_cli():
    parser = argparse.ArgumentParser(
        description="Run a fake UPnP/DLNA MediaRenderer for casting diagnostics.")
    parser.add_argument("http_port", nargs="?", type=int, default=8200,
                        help="HTTP description/control port (default: 8200)")
    parser.add_argument("profile", nargs="?", choices=sorted(VALID_PROFILES),
                        default=os.environ.get("FAKE_RENDERER_PROFILE", PROFILE_HP_HD03),
                        help="receiver behavior profile (default: hp-hd03; may also be set "
                             "with FAKE_RENDERER_PROFILE)")
    return parser.parse_args()


if __name__ == "__main__":
    CLI_ARGS = parse_cli()
    HTTP_PORT = CLI_ARGS.http_port
    RENDERER_PROFILE = CLI_ARGS.profile
else:
    HTTP_PORT = 8200
    RENDERER_PROFILE = os.environ.get("FAKE_RENDERER_PROFILE", PROFILE_HP_HD03)

if RENDERER_PROFILE not in VALID_PROFILES:
    raise SystemExit(f"Unknown fake renderer profile: {RENDERER_PROFILE}; "
                     f"choose one of {', '.join(sorted(VALID_PROFILES))}")
HOST_IP = detect_host_ip()
SSDP_ADDR = ("239.255.255.250", 1900)
UUID = "uuid:11111111-2222-3333-4444-555555555555"
FRIENDLY_NAME = "FakeRenderer-Test"
LOCATION = f"http://{HOST_IP}:{HTTP_PORT}/description.xml"

STATE = {
    "uri": None,
    "transport": "NO_MEDIA_PRESENT",
    "position": 0.0,
    "duration": "01:00:00",
    "clock_started": False,
}
SESSION = {"id": 0}
LOCK = threading.Lock()
REPORT = {"set_uri_calls": [], "fetch_reports": []}

SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
SERVICE_ID = "urn:upnp-org:serviceId:AVTransport"

DESCRIPTION = f"""<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>http://{HOST_IP}:{HTTP_PORT}/</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>{FRIENDLY_NAME}</friendlyName>
    <manufacturer>TestLab</manufacturer>
    <modelName>FakeRenderer</modelName>
    <UDN>{UUID}</UDN>
    <serviceList>
      <service>
        <serviceType>{SERVICE_TYPE}</serviceType>
        <serviceId>{SERVICE_ID}</serviceId>
        <SCPDURL>/scpd.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""

# Minimal but jUPnP-parseable SCPD declaring every action the app calls.
SCPD = f"""<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Play</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Pause</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Stop</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Seek</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
      <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetTransportInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
      <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
      <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetPositionInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
      <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
      <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
      <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
      <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
      <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
      <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetMediaInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
      <argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
      <argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
      <argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
      <argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
      <argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>
      <argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>
      <argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType><defaultValue>OK</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType><defaultValue>1</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType><defaultValue>0</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType><defaultValue>0</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType><defaultValue>NETWORK</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType><defaultValue>NOT_IMPLEMENTED</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType><defaultValue>NOT_IMPLEMENTED</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""


def log(msg):
    print(f"[renderer] {time.strftime('%H:%M:%S')} {msg}", flush=True)


def fmt_clock(seconds):
    h = int(seconds) // 3600
    m = (int(seconds) % 3600) // 60
    s = int(seconds) % 60
    return f"{h:02d}:{m:02d}:{s:02d}"


def clock_ticker():
    while True:
        time.sleep(1)
        with LOCK:
            if STATE["transport"] == "PLAYING":
                STATE["position"] += 1.0


def parse_hhmmss(text):
    m = re.match(r"(\d+):(\d+):(\d+)", text or "")
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) if m else 0.0


def http_fetch(url, headers=None, timeout=15):
    """Fetch URL, return dict with status/headers/body plus validation info."""
    result = {"url": url, "ok": False, "error": None}
    parsed = urllib.parse.urlsplit(url)
    if parsed.hostname == HOST_IP:
        host = "127.0.0.1" + (f":{parsed.port}" if parsed.port else "")
        fetch_url = urllib.parse.urlunsplit(
            (parsed.scheme, host, parsed.path, parsed.query, parsed.fragment))
    else:
        fetch_url = url
    req = urllib.request.Request(fetch_url,
                                 headers=headers or {"User-Agent": "FakeRenderer/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            result.update({
                "status": resp.status,
                "headers": {k.lower(): v for k, v in resp.getheaders()},
                "body": body,
                "bytes": len(body),
                "ok": True,
            })
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b""
        result.update({"status": e.code, "headers": dict(e.headers or {}), "body": body,
                       "bytes": len(body), "http_error": True})
    except Exception as e:
        result["error"] = repr(e)
    return result


def write_report():
    with open("/tmp/fake_renderer_report.json", "w") as f:
        json.dump(REPORT, f, indent=2, ensure_ascii=False)


def reset_debug_state():
    """Reset observable state without replacing dicts shared with handlers."""
    with LOCK:
        SESSION["id"] += 1
        STATE.update({"uri": None, "transport": "NO_MEDIA_PRESENT", "position": 0.0,
                      "duration": "01:00:00", "clock_started": False})
        REPORT["set_uri_calls"].clear()
        REPORT["fetch_reports"].clear()


def media_kind(uri):
    """Classify the URL path while ignoring query parameters and fragments."""
    path = (uri or "").split("?", 1)[0].split("#", 1)[0].lower()
    if path.endswith(".m3u8"):
        return "hls"
    if path.endswith(".mp4") or path.endswith(".ts"):
        return "progressive"
    return "hls"


def playlist_media_urls(body):
    """Return non-tag media lines from an HLS playlist."""
    text = (body or b"").decode("utf-8", "replace")
    return [line.strip() for line in text.splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def emulate_dongle_hls_fail(uri, session, fetcher=http_fetch, sleep_fn=time.sleep,
                            stop_delay=4.0):
    """Emulate the HP HD03 dongle on an HLS relay playlist: fetch the playlist
    and a couple of segments, report PLAYING, then STOPPED (black-screen exit).
    This is the signature that makes the phone app fall back to aggregated.ts."""
    report = {"mode": "dongle_hls_fail", "uri_head": uri[:140], "playlists": [], "segments": []}
    log(f"SetAVTransportURI -> dongle HLS-fail emulation: {uri[:100]}")
    r = fetcher(uri)
    pr = {"status": r.get("status"), "mime": r.get("headers", {}).get("content-type"),
          "bytes": r.get("bytes"), "is_playlist": (r.get("body") or b"").startswith(b"#EXTM3U")}
    report["playlists"].append(pr)
    log(f"playlist fetch: status={pr['status']} mime={pr['mime']} bytes={pr['bytes']} m3u8={pr['is_playlist']}")
    segments = playlist_media_urls(r.get("body"))
    report["segment_count"] = len(segments)
    for i, seg in enumerate(segments[:2]):
        f = fetcher(seg, {"Range": "bytes=0-"})
        report["segments"].append({"index": i, "status": f.get("status"), "bytes": f.get("bytes"),
                                   "content_range": f.get("headers", {}).get("content-range")})
        log(f"seg {i}: status={f.get('status')} bytes={f.get('bytes')}")
    sleep_fn(stop_delay)
    with LOCK:
        if SESSION["id"] == session:
            STATE["transport"] = "STOPPED"
            STATE["position"] = 0.0
    report["verdict"] = "STOPPED_SENT"
    with LOCK:
        REPORT["fetch_reports"].append(report)
        write_report()
    log("dongle HLS-fail emulation: reported STOPPED (app should fall back to aggregated.ts)")
    return report


def emulate_hls_capable(uri, session, fetcher=http_fetch):
    """Emulate a renderer that accepts HLS and starts fetching segments."""
    report = {"mode": "hls_capable", "uri_head": uri[:140], "playlists": [], "segments": []}
    result = fetcher(uri)
    is_playlist = (result.get("body") or b"").startswith(b"#EXTM3U")
    report["playlists"].append({
        "status": result.get("status"),
        "mime": result.get("headers", {}).get("content-type"),
        "bytes": result.get("bytes"),
        "is_playlist": is_playlist,
    })
    for index, segment in enumerate(playlist_media_urls(result.get("body"))[:2]):
        fetched = fetcher(segment, {"Range": "bytes=0-"})
        report["segments"].append({"index": index, "status": fetched.get("status"),
                                   "bytes": fetched.get("bytes")})
    ok = bool(result.get("ok")) and is_playlist and bool(report["segments"])
    report["verdict"] = "HLS_PLAYING" if ok else "HLS_FETCH_FAILED"
    with LOCK:
        if SESSION["id"] == session:
            STATE["transport"] = "PLAYING" if ok else "STOPPED"
        REPORT["fetch_reports"].append(report)
        write_report()
    return report


def emulate_hls_reject(uri, session):
    """Emulate a renderer that rejects HLS without fetching the playlist."""
    report = {"mode": "hls_reject", "uri_head": uri[:140], "playlists": [],
              "segments": [], "verdict": "HLS_REJECTED"}
    with LOCK:
        if SESSION["id"] == session:
            STATE["transport"] = "STOPPED"
            STATE["position"] = 0.0
        REPORT["fetch_reports"].append(report)
        write_report()
    return report


def ts_sync_ok(data):
    """Verify 0x47 sync bytes on the 188-byte MPEG-TS packet grid."""
    if len(data) < 188 * 8:
        return False
    return all(data[i] == 0x47 for i in range(0, len(data) - 188, 188))


def mp4_sig_ok(data):
    """Verify a progressive MP4: 'ftyp' box right after the 4-byte size field."""
    return len(data) >= 12 and data[4:8] == b"ftyp"


def mp4_moov_ok(data):
    """True if the data contains an MP4 'moov' box marker. MediaMuxer writes
    moov last, so it shows up in a tail range probe of a valid remuxed file;
    raw TS bytes never contain it."""
    return b"moov" in data


def probe_response(url, headers, read_bytes, label, report):
    """Issue one request, read up to read_bytes, record response metadata."""
    entry = {"label": label, "request_headers": headers or {}, "url_head": url[:100]}
    req = urllib.request.Request(url, headers=headers or {"User-Agent": "FakeDongle/1.0"})
    try:
        resp = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as e:
        resp = e
    except Exception as e:
        entry["error"] = repr(e)
        report["probes"].append(entry)
        log(f"{label}: ERROR {entry['error']}")
        return entry
    try:
        entry["status"] = resp.status if hasattr(resp, "status") else resp.code
        hdrs = {k.lower(): v for k, v in resp.getheaders()}
        entry["headers"] = {k: hdrs.get(k) for k in
                            ("content-length", "content-range", "transfer-encoding",
                             "accept-ranges", "content-type")}
        body = resp.read(read_bytes)
        entry["read_bytes"] = len(body)
        entry["ts_sync_ok"] = ts_sync_ok(body)
        entry["mp4_sig_ok"] = mp4_sig_ok(body)
        entry["mp4_moov_ok"] = mp4_moov_ok(body)
    finally:
        try:
            resp.close()
        except Exception:
            pass
    report["probes"].append(entry)
    log(f"{label}: status={entry.get('status')} hdrs={entry.get('headers')} "
        f"read={entry.get('read_bytes')} sync47={entry.get('ts_sync_ok')} "
        f"mp4={entry.get('mp4_sig_ok')} moov={entry.get('mp4_moov_ok')}")
    return entry


def _emulate_black_screen_stop(session, report):
    """MP4-only dongle abort sequence: brief PLAYING then black-screen STOPPED.
    Mirrors the real HP HD03 which shows ~1 frame then exits on unplayable bytes."""
    with LOCK:
        if SESSION["id"] == session:
            STATE["transport"] = "PLAYING"
    time.sleep(2.5)
    with LOCK:
        if SESSION["id"] == session:
            STATE["transport"] = "STOPPED"
            STATE["position"] = 0.0
    with LOCK:
        REPORT["fetch_reports"].append(report)
        write_report()


def emulate_dongle_progressive(uri, session):
    """Emulate the HP HD03 dongle player against the aggregated progressive
    stream: rapid re-opens, Range probes, byte-level validation.

    When the cast URI promises .mp4, the dongle is MP4-only: it requires an
    ftyp box at the head and a reachable moov box (head or tail). Raw TS bytes
    under a .mp4 URL — the regression where the relay skipped remux and fell
    through to TS — make the real device black-screen and stop. Detect that
    exactly instead of giving a false-green RANGE_OK."""
    report = {"mode": "dongle_progressive", "uri_head": uri[:140], "probes": []}
    log(f"SetAVTransportURI -> dongle progressive emulation: {uri[:100]}")
    expects_mp4 = uri.lower().endswith(".mp4")
    # Observed dongle pattern: several rapid plain GETs (probe + reopen)
    for i in range(3):
        probe_response(uri, {"User-Agent": "FakeDongle/1.0"}, 65536, f"rapid-open-{i + 1}", report)
    # Range play from 0
    rs = probe_response(uri, {"User-Agent": "FakeDongle/1.0", "Range": "bytes=0-"},
                        1 << 20, "range-start", report)
    first = report["probes"][0]
    cl = first.get("headers", {}).get("content-length")
    te = first.get("headers", {}).get("transfer-encoding")

    if expects_mp4:
        # ftyp at the head distinguishes a real MP4 from TS bytes served under
        # a .mp4 URL. MediaMuxer always writes ftyp first; raw TS starts with
        # the 0x47 sync byte, so a missing ftyp is the clean regression signal.
        head_ok = bool(rs.get("mp4_sig_ok")) or any(p.get("mp4_sig_ok") for p in report["probes"])
        moov_ok = head_ok and any(p.get("mp4_moov_ok") for p in report["probes"])
        if not moov_ok and cl and cl.isdigit() and int(cl) > 0:
            # Real dongle probes the file tail for the moov atom (MediaMuxer
            # writes it last). Fetch the last 1 MiB so a large moov is covered.
            total = int(cl)
            tail_start = max(0, total - (1 << 20))
            tail = probe_response(uri, {"User-Agent": "FakeDongle/1.0",
                                  "Range": f"bytes={tail_start}-"}, 1 << 20,
                                  "tail-moov-probe", report)
            moov_ok = bool(tail.get("mp4_moov_ok"))
        if not head_ok:
            report["verdict"] = "MP4_EXPECTED_GOT_TS"
            report["streaming_ok"] = False
            log("verdict=MP4_EXPECTED_GOT_TS (URI promises .mp4 but head has no ftyp; "
                "relay served TS — emulating black-screen STOPPED)")
            _emulate_black_screen_stop(session, report)
            return
        if not moov_ok:
            report["verdict"] = "MP4_NO_MOOV"
            report["streaming_ok"] = False
            log("verdict=MP4_NO_MOOV (ftyp present but no moov reachable; emulating STOPPED)")
            _emulate_black_screen_stop(session, report)
            return
        report["mp4_verified"] = True

    # Range at a 188-byte-grid aligned offset: server must truly skip bytes
    aligned = 188 * 5000
    probe_response(uri, {"User-Agent": "FakeDongle/1.0", "Range": f"bytes={aligned}-"},
                   262144, f"range-{aligned}", report)
    # Sustained play: consume 4 MB from 0 like a player prebuffering
    e = probe_response(uri, {"User-Agent": "FakeDongle/1.0", "Range": "bytes=0-"},
                       4 << 20, "sustained-4MB", report)
    streaming_ok = e.get("read_bytes", 0) >= (3 << 20) and (e.get("ts_sync_ok") or e.get("mp4_sig_ok"))
    if rs.get("status") == 206 and rs.get("headers", {}).get("content-range"):
        report["verdict"] = "RANGE_OK" if streaming_ok else "RANGE_OK_BUT_STREAM_BROKEN"
    elif te == "chunked" or not cl:
        report["verdict"] = "CHUNKED_NO_LENGTH_NO_RANGE"
    else:
        report["verdict"] = "STATIC_NO_RANGE"
    report["streaming_ok"] = streaming_ok
    log(f"verdict={report['verdict']} (content-length={cl}, transfer-encoding={te})")
    with LOCK:
        REPORT["fetch_reports"].append(report)
        write_report()
    with LOCK:
        if SESSION["id"] == session:
            STATE["transport"] = "PLAYING"


def emulate_tv(uri, session, profile=None):
    """Dispatch by cast URI type: HLS playlist -> dongle HLS-fail emulation,
    aggregated progressive file (TS or MP4) -> dongle player byte-level trace."""
    profile = profile or RENDERER_PROFILE
    if media_kind(uri) == "progressive":
        emulate_dongle_progressive(uri, session)
    elif profile == PROFILE_HLS_CAPABLE:
        emulate_hls_capable(uri, session)
    elif profile == PROFILE_HLS_REJECT:
        emulate_hls_reject(uri, session)
    else:
        emulate_dongle_hls_fail(uri, session)


def soap_response(action, body_xml=""):
    return (f'<?xml version="1.0" encoding="utf-8"?>'
            f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            f's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
            f'<s:Body><u:{action}Response xmlns:u="{SERVICE_TYPE}">{body_xml}</u:{action}Response>'
            f'</s:Body></s:Envelope>').encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log(f"HTTP {self.address_string()} {fmt % args}")

    def _send(self, code, body, ctype="text/xml; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/description.xml"):
            self._send(200, DESCRIPTION.encode())
        elif self.path.startswith("/scpd.xml"):
            self._send(200, SCPD.encode())
        elif self.path.startswith("/debug/state"):
            with LOCK:
                body = json.dumps({"profile": RENDERER_PROFILE, **STATE}).encode()
            self._send(200, body, "application/json")
        elif self.path.startswith("/debug/report"):
            with LOCK:
                body = json.dumps(REPORT).encode()
            self._send(200, body, "application/json")
        elif self.path.startswith("/test/stream.m3u8"):
            segment = f"http://{HOST_IP}:{HTTP_PORT}/test/segment.ts"
            body = (f"#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4,\n{segment}\n"
                    f"#EXT-X-ENDLIST\n").encode()
            self._send(200, body, "application/vnd.apple.mpegurl")
        elif self.path.startswith("/test/segment.ts"):
            body = (b"\x47" + b"\x00" * 187) * 8
            self._send(200, body, "video/mp2t")
        else:
            self._send(404, b"not found", "text/plain")

    def do_SUBSCRIBE(self):
        self._send(200, b"", extra={"SID": "uuid:sub-fake-1", "TIMEOUT": "Second-1800"})
    def do_UNSUBSCRIBE(self):
        self._send(200, b"")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        payload = self.rfile.read(length).decode("utf-8", "replace")
        if self.path.startswith("/debug/reset"):
            reset_debug_state()
            self._send(204, b"", "application/json")
            return
        action = re.search(r'#([A-Za-z]+)"', self.headers.get("SOAPACTION", "") or "")
        action = action.group(1) if action else ""
        log(f"SOAP action={action}")

        if action == "SetAVTransportURI":
            uri = re.search(r"<CurrentURI>(.*?)</CurrentURI>", payload, re.S)
            uri = uri.group(1).strip() if uri else ""
            uri = uri.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            with LOCK:
                SESSION["id"] += 1
                session = SESSION["id"]
                STATE["uri"] = uri
                STATE["transport"] = "TRANSITIONING"
                STATE["position"] = 0.0
                REPORT["set_uri_calls"].append({"time": time.time(), "uri": uri})
            threading.Thread(target=emulate_tv, args=(uri, session), daemon=True).start()
            self._send(200, soap_response("SetAVTransportURI"))
        elif action == "Play":
            with LOCK:
                STATE["transport"] = "PLAYING"
            self._send(200, soap_response("Play"))
        elif action == "Pause":
            with LOCK:
                STATE["transport"] = "PAUSED_PLAYBACK"
            self._send(200, soap_response("Pause"))
        elif action == "Stop":
            with LOCK:
                STATE["transport"] = "STOPPED"
            self._send(200, soap_response("Stop"))
        elif action == "Seek":
            target = re.search(r"<Target>(.*?)</Target>", payload, re.S)
            if target:
                with LOCK:
                    STATE["position"] = parse_hhmmss(target.group(1).strip())
            self._send(200, soap_response("Seek"))
        elif action == "GetTransportInfo":
            with LOCK:
                state = STATE["transport"]
            self._send(200, soap_response("GetTransportInfo",
                f"<CurrentTransportState>{state}</CurrentTransportState>"
                f"<CurrentTransportStatus>OK</CurrentTransportStatus>"
                f"<CurrentSpeed>1</CurrentSpeed>"))
        elif action == "GetPositionInfo":
            with LOCK:
                uri, pos = STATE["uri"] or "", STATE["position"]
            self._send(200, soap_response("GetPositionInfo",
                f"<Track>1</Track><TrackDuration>{STATE['duration']}</TrackDuration>"
                f"<TrackMetaData></TrackMetaData><TrackURI>{uri}</TrackURI>"
                f"<RelTime>{fmt_clock(pos)}</RelTime><AbsTime>{fmt_clock(pos)}</AbsTime>"
                f"<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>"))
        elif action == "GetMediaInfo":
            with LOCK:
                uri = STATE["uri"] or ""
            self._send(200, soap_response("GetMediaInfo",
                f"<NrTracks>1</NrTracks><MediaDuration>{STATE['duration']}</MediaDuration>"
                f"<CurrentURI>{uri}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                f"<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                f"<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                f"<WriteStatus>NOT_IMPLEMENTED</WriteStatus>"))
        else:
            self._send(401, b"unknown action", "text/plain")


def ssdp_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except OSError:
        pass
    sock.bind(("", 1900))
    # Join the SSDP multicast group on HOST_IP. If HOST_IP is stale or the
    # interface can't join (e.g. just changed networks), fall back to
    # INADDR_ANY so discovery still works — never let this kill the thread.
    joined = False
    for iface in (HOST_IP, "0.0.0.0"):
        mreq = socket.inet_aton(SSDP_ADDR[0]) + socket.inet_aton(iface)
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            joined = True
            log(f"SSDP listening on {HOST_IP}:1900 (multicast joined on {iface})")
            break
        except OSError as e:
            if iface == HOST_IP:
                log(f"SSDP multicast join on {iface} failed ({e}); trying INADDR_ANY")
    if not joined:
        log("SSDP multicast join failed on all interfaces; M-SEARCH replies still sent")

    alive_targets = [
        ("upnp:rootdevice", f"{UUID}::upnp:rootdevice"),
        (UUID, UUID),
        ("urn:schemas-upnp-org:device:MediaRenderer:1", f"{UUID}::urn:schemas-upnp-org:device:MediaRenderer:1"),
        (SERVICE_TYPE, f"{UUID}::{SERVICE_TYPE}"),
    ]
    def announce():
        for nt, usn in alive_targets:
            msg = (f"NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n"
                   f"LOCATION: {LOCATION}\r\nNT: {nt}\r\nNTS: ssdp:alive\r\nSERVER: Linux/1.0 UPnP/1.0 FakeRenderer/1.0\r\n"
                   f"USN: {usn}\r\n\r\n").encode()
            sock.sendto(msg, SSDP_ADDR)
        log("SSDP alive announced (3 targets x4)")
    threading.Thread(target=lambda: [announce() or time.sleep(8) for _ in iter(int, 1)], daemon=True).start()

    while True:
        try:
            data, addr = sock.recvfrom(2048)
        except OSError:
            continue
        text = data.decode("utf-8", "replace")
        if not text.startswith("M-SEARCH"):
            continue
        st = re.search(r"ST:\s*(.+)", text, re.I)
        st = st.group(1).strip() if st else "ssdp:all"
        log(f"M-SEARCH from {addr[0]}: ST={st}")
        matches = ([t for t, _ in alive_targets if st in ("ssdp:all", t)]
                   if st != "ssdp:all" else [t for t, _ in alive_targets])
        for target in matches:
            usn = dict(alive_targets)[target]
            resp = (f"HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: {LOCATION}\r\n"
                    f"SERVER: Linux/1.0 UPnP/1.0 FakeRenderer/1.0\r\nST: {target}\r\nUSN: {usn}\r\n\r\n").encode()
            sock.sendto(resp, addr)


if __name__ == "__main__":
    threading.Thread(target=clock_ticker, daemon=True).start()
    threading.Thread(target=ssdp_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    log(f"Fake DLNA renderer ready: {LOCATION} (friendlyName={FRIENDLY_NAME}, "
        f"profile={RENDERER_PROFILE})")
    server.serve_forever()
