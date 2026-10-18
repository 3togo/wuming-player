#!/usr/bin/env python3
"""Collect episode names from the sites inside a TVBox config (default: 饭太硬).

Purpose: build a corpus of raw episode names so we can design redundancy
reduction (e.g. stripping quality/source words like "1080P", "$ HD" suffixes)
for episode display in the app.

Scope: only direct CMS sites are crawlable from a plain script:
  - type 0 (macCMS XML API,  ?ac=videolist)
  - type 1 (macCMS JSON API, ?ac=detail)
Type 3 spider sites need the jar executed inside the app/desktop and are skipped.

Usage:
  python3 scripts/collect_episode_names.py                # scan 饭太硬
  python3 scripts/collect_episode_names.py <config_url>   # scan another config

Output: episode_names_report.json + episode_names_long.txt in project root.
"""

import base64
import collections
import concurrent.futures
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

CONFIG_URL = "http://www.饭太硬.cc/tv"
MAX_CATEGORIES_PER_SITE = 8      # cap crawl size per site
MAX_PAGES_PER_CATEGORY = 3       # each page ~20-30 videos
LONG_NAME_THRESHOLD = 12         # "long" episode name = length > this
MAX_LONG_SAMPLES = 5000          # cap stored long-name samples
WORKERS = 8
TIMEOUT = 15

UAS = [
    "okhttp/4.12.0",
    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
]


def ascii_url(url):
    """Punycode-encode non-ASCII hosts (e.g. 饭太硬.cc -> xn--...)."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname
    if host and any(ord(c) > 127 for c in host):
        host = host.encode("idna").decode()
        netloc = f"{host}:{parts.port}" if parts.port else host
        return urllib.parse.urlunsplit((parts.scheme, netloc, parts.path,
                                        parts.query, parts.fragment))
    return url


def http_get(url, headers=None, raw=False):
    last_err = None
    for ua in UAS:
        hdrs = {"User-Agent": ua}
        if headers:
            hdrs.update({k: v for k, v in headers.items() if k.lower() != "user-agent"})
        req = urllib.request.Request(ascii_url(url), headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                data = resp.read()
                if raw:
                    return data
                for enc in ("utf-8", "gb18030"):
                    try:
                        return data.decode(enc)
                    except UnicodeDecodeError:
                        continue
                return data.decode("utf-8", errors="replace")
        except Exception as e:  # noqa: BLE001
            last_err = e
    raise last_err


def strip_js_comments(text):
    """TVBox configs commonly contain // and /* */ comments outside strings."""
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        else:
            out.append(c)
        i += 1
    return "".join(out)


def parse_config_json(text):
    text = strip_js_comments(text)
    text = re.sub(r",\s*([}\]])", r"\1", text)  # trailing commas
    return json.loads(text)


def decode_config(payload):
    """Handles plain JSON, base64-wrapped JSON, and the TVBox image-plus-Base64
    envelope: binary image bytes followed by an `XXXXXXXX**` marker and MIME
    base64 config text (same scheme as TvBoxConfigPayloadDecoder)."""
    envelope = payload.decode("latin-1")
    # 1) image-plus-Base64 envelope: marker then MIME base64 (primary 饭太硬 scheme)
    match = re.search(r"[A-Za-z0-9]{8}\*\*", envelope)
    if match:
        encoded = envelope[match.end():].strip()
        if encoded:
            try:
                decoded = base64.b64decode(encoded).decode("utf-8").strip()
                if decoded.startswith("{"):
                    return parse_config_json(decoded)
            except Exception:  # noqa: BLE001
                pass
    # 2) plain JSON anywhere in the payload
    start, end = envelope.find("{"), envelope.rfind("}")
    if start >= 0 and end > start:
        try:
            return parse_config_json(envelope[start:end + 1])
        except Exception:  # noqa: BLE001
            pass
    # 3) whole payload as base64
    try:
        decoded = base64.b64decode(payload + b"=" * (-len(payload) % 4)).decode("utf-8")
        start, end = decoded.find("{"), decoded.rfind("}")
        if start >= 0 and end > start:
            return parse_config_json(decoded[start:end + 1])
    except Exception:  # noqa: BLE001
        pass
    raise ValueError("config is not plain JSON nor a supported encoded envelope "
                     "(plain/base64/image-plus-Base64)")


def load_config(url):
    payload = http_get(url, raw=True)
    cfg = decode_config(payload)
    sites = cfg.get("sites") or []
    return cfg, sites


def parse_play_url(play_from, play_url):
    """vod_play_url format: ep$url#ep$url$$$ep$url#... groups match play_from."""
    flags = (play_from or "").split("$$$")
    groups = (play_url or "").split("$$$")
    out = []  # (flag, episode_name)
    for i, group in enumerate(groups):
        flag = flags[i].strip() if i < len(flags) else "?"
        for item in group.split("#"):
            if not item:
                continue
            name = item.split("$", 1)[0].strip()
            if name:
                out.append((flag, name))
    return out


def parse_xml_episodes(xml_text):
    """macCMS XML: <video><dl><dd flag="x">name$url#name$url</dd></dl></video>."""
    out = []
    try:
        root = ET.fromstring(re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", xml_text))
    except ET.ParseError:
        return out
    for video in root.iter("video"):
        title = (video.findtext("name") or "").strip()
        dl = video.find("dl")
        if dl is None:
            continue
        for dd in dl.iter("dd"):
            flag = dd.get("flag") or "?"
            for item in (dd.text or "").split("#"):
                if not item:
                    continue
                name = item.split("$", 1)[0].strip()
                if name:
                    out.append((flag, name, title))
    return out


def parse_json_episodes(doc):
    out = []
    for vod in doc.get("list") or []:
        title = (vod.get("vod_name") or "").strip()
        for flag, name in parse_play_url(vod.get("vod_play_from", ""),
                                         vod.get("vod_play_url", "")):
            out.append((flag, name, title))
    return out


def crawl_site(site):
    """Return (site_name, [(flag, episode_name, vod_title)], error)."""
    name = site.get("name") or site.get("key") or "?"
    stype = site.get("type")
    api = (site.get("api") or "").strip()
    search = site.get("searchable", 1)
    error = None
    episodes = []
    if stype not in (0, 1) or not api.startswith(("http://", "https://")):
        return name, [], f"skip type={stype}"
    if not search:
        return name, [], "skip not searchable"
    try:
        if stype == 1:
            # category ids
            doc = json.loads(http_get(api + ("&" if "?" in api else "?") + "ac=list"))
            cats = [c.get("type_id") for c in (doc.get("class") or [])
                    if c.get("type_id") is not None][:MAX_CATEGORIES_PER_SITE]
            for cat in cats:
                for pg in range(1, MAX_PAGES_PER_CATEGORY + 1):
                    q = urllib.parse.urlencode({"ac": "detail", "t": cat, "pg": pg})
                    try:
                        page = json.loads(http_get(f"{api}{'&' if '?' in api else '?'}{q}"))
                    except Exception:  # noqa: BLE001
                        break
                    batch = parse_json_episodes(page)
                    episodes.extend(batch)
                    if len(page.get("list") or []) == 0:
                        break
                    time.sleep(0.2)
        else:  # type 0 XML
            for pg in range(1, MAX_PAGES_PER_CATEGORY * MAX_PAGES_PER_CATEGORY + 1):
                q = urllib.parse.urlencode({"ac": "videolist", "pg": pg})
                try:
                    xml_text = http_get(f"{api}{'&' if '?' in api else '?'}{q}")
                except Exception:  # noqa: BLE001
                    break
                batch = parse_xml_episodes(xml_text)
                episodes.extend(batch)
                if not batch:
                    break
                time.sleep(0.2)
    except Exception as e:  # noqa: BLE001
        error = f"error: {e}"
    return name, episodes, error


def main():
    url = sys.argv[1] if len(sys.argv) > 1 else CONFIG_URL
    print(f"loading config: {url}")
    cfg, sites = load_config(url)
    print(f"config has {len(sites)} sites; "
          f"{sum(1 for s in sites if s.get('type') in (0, 1))} are direct CMS (crawlable)")

    all_episodes = []
    for name, episodes, note in concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) \
            .map(crawl_site, sites):
        status = f"{len(episodes)} episode names"
        if note:
            status += f" ({note})"
        print(f"  {name}: {status}")
        all_episodes.extend((name, flag, ep, vod) for flag, ep, vod in episodes)

    if not all_episodes:
        print("no episode names collected; nothing to report")
        return

    counter = collections.Counter(ep for _, _, ep, _ in all_episodes)
    long_names = [(site, flag, ep, vod) for site, flag, ep, vod in all_episodes
                  if len(ep) > LONG_NAME_THRESHOLD]

    report = {
        "config_url": url,
        "threshold_long": LONG_NAME_THRESHOLD,
        "total_episode_names": len(all_episodes),
        "unique_episode_names": len(counter),
        "long_episode_names": len(long_names),
        "per_site": {
            site: {"total": sum(1 for s, *_ in all_episodes if s == site),
                   "long": sum(1 for s, *_ in long_names if s == site)}
            for site in sorted({s for s, *_ in all_episodes})
        },
        "top_100_frequent_names": counter.most_common(100),
        "long_name_samples": [
            {"site": s, "flag": f, "name": e, "len": len(e), "vod": v}
            for s, f, e, v in long_names[:MAX_LONG_SAMPLES]
        ],
    }
    out_json = "episode_names_report.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    out_txt = "episode_names_long.txt"
    with open(out_txt, "w", encoding="utf-8") as f:
        for s, _flag, e, _v in long_names[:MAX_LONG_SAMPLES]:
            f.write(f"{e}\n")

    print(f"\ncollected {len(all_episodes)} names "
          f"({len(counter)} unique, {len(long_names)} longer than {LONG_NAME_THRESHOLD} chars)")
    print(f"report: {out_json}\nlong names: {out_txt}")


if __name__ == "__main__":
    main()
