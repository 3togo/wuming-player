#!/usr/bin/env python3
"""Probe every distinct link candidate before it enters the offline catalog.

This is an admission snapshot, not a promise that a third-party source will keep
working. Redirects are recorded as uncertain rather than followed to an untrusted
destination. HTTP errors, timeouts and malformed content are kept in the report.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import pathlib
import ssl
import subprocess
import threading
import urllib.error
import urllib.request
from collections import Counter
from urllib.parse import urlsplit

import update_depot
import validate_depot
from import_links import candidates
from link_manager import load_managed

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_REPORT = ROOT / ".cache/link_health.json"
MAX_BYTES = 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def decode_2423(body: bytes) -> bytes:
    """Use OpenSSL to check the CBC envelope already supported by the app."""
    value = b"".join(body.split()).decode("ascii")
    if len(value) < 32 or len(value) % 2 or not value.startswith("2423"):
        raise ValueError("invalid 2423 envelope")
    marker = value.find("2324", 4)
    if marker < 4 or marker % 2 or marker + 4 >= len(value) - 26:
        raise ValueError("invalid 2423 framing")
    key_text = bytes.fromhex(value[4:marker]).decode("utf-8").lower()
    iv_text = bytes.fromhex(value[-26:]).decode("utf-8").lower()
    key = key_text[:16].ljust(16, "0").encode("utf-8")
    iv = iv_text[:16].ljust(16, "0").encode("utf-8")
    if len(key) != 16 or len(iv) != 16:
        raise ValueError("invalid 2423 key or IV")
    cipher = bytes.fromhex(value[marker + 4:-26])
    result = subprocess.run(
        ["openssl", "enc", "-aes-128-cbc", "-d", "-K", key.hex(), "-iv", iv.hex()],
        input=cipher, capture_output=True, timeout=5, check=True,
    )
    return result.stdout


def looks_like_live_txt(body: bytes) -> bool:
    lines = body.decode("utf-8", "replace").splitlines()
    channels = sum(1 for line in lines if "," in line and "://" in line.split(",", 1)[1]
                   and line.split(",", 1)[0].strip())
    return channels >= 2 or (channels >= 1 and any("#genre#" in line.lower() for line in lines))


def probe(url: str, timeout: float = 8.0) -> dict[str, str | int]:
    request = urllib.request.Request(
        validate_depot.request_url(url),
        headers={"User-Agent": validate_depot.USER_AGENT,
                 "Accept": "application/json, text/plain, application/x-mpegURL, */*"},
    )
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=ssl.create_default_context()), NoRedirect(),
    )
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read(MAX_BYTES + 1)
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            status_code = response.status
            content_range = response.headers.get("Content-Range", "")
    except urllib.error.HTTPError as error:
        status = error.code
        content_type = error.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        error.close()
        return {"status": "gone" if status in (404, 410) else "uncertain",
                "category": "unknown", "contentType": content_type,
                "statusCode": status, "reason": f"HTTP {status}"}
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
        return {"status": "uncertain", "category": "unknown", "contentType": "",
                "statusCode": 0, "reason": f"request failed: {str(error)[:160]}"}
    base = {"contentType": content_type, "statusCode": status_code}
    if not body.strip():
        return {**base, "status": "gone", "category": "unknown", "reason": "empty response"}
    if len(body) > MAX_BYTES:
        return {**base, "status": "uncertain", "category": "unknown",
                "reason": "body exceeds probe sample"}
    # Some servers return only a partial body even without a Range request.
    # A truncated JSON document must not be called incompatible.
    if status_code == 206 and "/" in content_range:
        total = content_range.rsplit("/", 1)[-1]
        if total.isdigit() and int(total) > len(body):
            return {**base, "status": "uncertain", "category": "unknown",
                    "reason": f"partial response: {len(body)}/{total} byte(s)"}
    encoded = body.lstrip().startswith(b"2423")
    if encoded:
        try:
            body = decode_2423(body)
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return {**base, "status": "uncertain", "category": "unknown",
                    "reason": "2423 envelope decoder unavailable"}
        except (ValueError, UnicodeError, subprocess.CalledProcessError) as error:
            return {**base, "status": "unsupported", "category": "unknown",
                    "reason": f"invalid 2423 envelope: {str(error)[:80]}"}
    kind, detail = update_depot.classify_bytes(body)
    if kind in ("config", "depot"):
        return {**base, "status": "reachable", "category": "vod",
                "reason": f"{'2423 decoded ' if encoded else ''}{kind}: {detail}"}
    if kind == "playlist" or (kind == "text" and content_type in (
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/x-mpegurl",
    )) or (kind == "text" and looks_like_live_txt(body)):
        return {**base, "status": "reachable", "category": "live",
                "reason": f"{kind}: {detail}"}
    return {**base, "status": "unsupported", "category": "unknown", "reason": detail}


def check(source: pathlib.Path, workers: int = 16, timeout: float = 8.0,
          progress: bool = False, managed: pathlib.Path | None = None) -> dict:
    entries, counts = candidates(source.read_text(encoding="utf-8-sig"))
    if managed is not None:
        seen = {entry["url"] for entry in entries}
        for entry in load_managed(managed)["links"]:
            if entry["url"] not in seen:
                entries.append({"section": "managed", **entry})
                seen.add(entry["url"])
        counts["candidates"] = len(entries)
    limits: dict[str, threading.Semaphore] = {}
    for entry in entries:
        limits.setdefault(urlsplit(entry["url"]).hostname.lower(), threading.Semaphore(3))

    def run(entry: dict) -> dict:
        host = urlsplit(entry["url"]).hostname.lower()
        with limits[host]:
            verdict = probe(entry["url"], timeout)
        return {**entry, **verdict}

    results = [None] * len(entries)
    if progress:
        print(f"Screening {len(entries)} candidate URLs...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(run, entry): index for index, entry in enumerate(entries)}
        for finished, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            results[futures[future]] = future.result()
            if progress and (finished % 100 == 0 or finished == len(entries)):
                print(f"Screened {finished}/{len(entries)}", flush=True)
    return {"version": 1, "source": str(source),
            "checkedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "counts": {**counts, **Counter(row["status"] for row in results)}, "results": results}


def save_report(report: dict, path: pathlib.Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("--report", type=pathlib.Path, default=DEFAULT_REPORT)
    parser.add_argument("--managed", type=pathlib.Path,
                        default=ROOT / "scripts/depot/managed_links.json")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args()
    if not 1 <= args.workers <= 32 or not 1 <= args.timeout <= 30:
        parser.error("workers must be 1–32 and timeout 1–30 seconds")
    report = check(args.source, args.workers, args.timeout, progress=True, managed=args.managed)
    save_report(report, args.report)
    print(f"Wrote {len(report['results'])} verdicts to {args.report}: {report['counts']}")


if __name__ == "__main__":
    main()
