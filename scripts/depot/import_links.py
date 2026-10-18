#!/usr/bin/env python3
"""Review and merge a plain-text link collection into the bundled depot.

The input is untrusted data. Only public HTTP(S) config and playlist candidates are
considered; every new entry must pass update_depot's content-shape gate. A preview
is the default. --apply writes a bounded merge after the verdicts are printed.
"""

from __future__ import annotations

import argparse
import collections
import ipaddress
import json
import pathlib
import re
import sys
from urllib.parse import urlsplit

import update_depot
import validate_depot

DEPOT = pathlib.Path(update_depot.DEPOT_FILE)
URL_RE = re.compile(r"https?://[^\s<>\"“”\[\]()，,]+", re.I)
EMBEDDED_GITHUB = re.compile(r"^https?://[^/]+/(https?://(?:raw|gist)\.githubusercontent\.com/.*)$", re.I)
PROXY_HOSTS = frozenset(("ghproxy.net", "gh-proxy.com", "gh-proxy.org", "v6.gh-proxy.org",
                         "v4.gh-proxy.org", "cdn.gh-proxy.org", "ghp.ci", "ghfast.top",
                         "github.moeyy.xyz", "mirror.ghproxy.com", "ghproxy.com", "gh.jasonzeng.dev",
                         "ghproxy.monkeyray.net"))
CONFIG_SUFFIXES = (".json", ".txt")
PLAYLIST_SUFFIXES = (".m3u", ".m3u8")
REJECT_SUFFIXES = (".md5", ".png", ".webp", ".jpg", ".jpeg", ".gif", ".html",
                   ".apk", ".js", ".py", ".mp4", ".zip")


def canonical(raw: str) -> str | None:
    """Return a usable publisher URL, never a third-party accelerator URL."""
    value = raw.strip().rstrip(".,;:，。；：!！\"'。")
    embedded = EMBEDDED_GITHUB.match(value)
    if embedded:
        value = embedded.group(1)
    elif "http://" in value[value.find("://") + 3:] or "https://" in value[value.find("://") + 3:]:
        return None
    try:
        parts = urlsplit(value)
        host = parts.hostname
        if parts.scheme.lower() not in ("http", "https") or not host or parts.username is not None:
            return None
        if host.lower() in PROXY_HOSTS:
            # Common mirror form without an inner scheme: /raw.githubusercontent.com/…
            if parts.path.startswith("/raw.githubusercontent.com/"):
                value = "https:/" + parts.path
                parts = urlsplit(value)
                host = parts.hostname
            else:
                return None
        if host.lower() == "github.com" and "/blob/" in parts.path:
            path = parts.path.replace("/blob/", "/", 1)
            value = "https://raw.githubusercontent.com" + path
            parts = urlsplit(value)
            host = parts.hostname
        if any(c.isspace() or ord(c) < 32 for c in value):
            return None
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            if host.lower() in ("localhost",) or host.lower().endswith((".local", ".localhost")):
                return None
        else:
            if not address.is_global:
                return None
    except ValueError:
        return None
    return value


def classify(url: str, label: str) -> str | None:
    path = urlsplit(url).path.lower().rstrip("/")
    if path.endswith(REJECT_SUFFIXES):
        return None
    if path.endswith(PLAYLIST_SUFFIXES):
        return "live"
    if path.endswith(CONFIG_SUFFIXES):
        return "live" if path.endswith(".txt") and any(word in label for word in ("直播", "IPTV", "iptv")) else "vod"
    # Extensionless and PHP links are too ambiguous to admit without a hand review.
    return None


def candidates(text: str) -> tuple[list[dict[str, str]], collections.Counter]:
    result: list[dict[str, str]] = []
    seen: set[str] = set()
    counts: collections.Counter = collections.Counter()
    for line in text.splitlines():
        for match in URL_RE.finditer(line):
            counts["links"] += 1
            url = canonical(match.group())
            if url is None:
                counts["unsafe_or_proxy"] += 1
                continue
            if url in seen:
                counts["duplicates"] += 1
                continue
            seen.add(url)
            prefix = line[:match.start()]
            previous = list(URL_RE.finditer(prefix))
            if previous:
                prefix = prefix[previous[-1].end():]
            label = prefix.strip(" \t:：-—•*（(")
            label = label[-64:].strip() if len(label) > 64 else label.strip()
            section = classify(url, label)
            if section is None:
                counts["unsupported_or_ambiguous"] += 1
                continue
            name = label or update_depot.extract_name_from_url(url)
            result.append({"section": section, "url": url, "name": name})
    counts["candidates"] = len(result)
    return result, counts


def admitted_section(reason: str) -> str | None:
    """Use the fetched body's shape, not a label in the untrusted list."""
    if reason.startswith(("config:", "warehouse index")):
        return "vod"
    if reason.startswith(("playlist:", "text:")) and "no stream URLs inside" not in reason:
        return "live"
    return None


def merge(source: pathlib.Path, depot_path: pathlib.Path, max_check: int, max_add: int,
          apply: bool) -> dict[str, int]:
    if max_check < 1 or max_add < 1:
        raise ValueError("--max-check and --max-add must be positive")
    data = json.loads(depot_path.read_text(encoding="utf-8"))
    existing = {entry["url"] for section in ("vod", "live") for entry in data[section]}
    found, counts = candidates(source.read_text(encoding="utf-8-sig"))
    pending = [entry for entry in found if entry["url"] not in existing]
    counts["already_in_depot"] = len(found) - len(pending)
    admitted: list[dict[str, str]] = []
    checked = 0
    for entry in pending[:max_check]:
        if len(admitted) >= max_add:
            break
        ok, reason = update_depot.gate_url(entry["url"])
        checked += 1
        section = admitted_section(reason) if ok else None
        print(f"{'ADD' if section else 'SKIP'} {section or entry['section']} {entry['name']}: {reason}")
        if section:
            admitted.append({**entry, "section": section, "warehouse": reason.startswith("warehouse index")})
        else:
            counts["failed_gate"] += 1
    counts["checked"] = checked
    counts["unchecked"] = len(pending) - checked
    counts["admitted"] = len(admitted)
    counts["added"] = 0
    if apply and admitted:
        # Validation may take minutes. Re-read immediately before writing so edits made
        # during the probes are retained, including source metadata and manual additions.
        data = json.loads(depot_path.read_text(encoding="utf-8"))
        current = {entry["url"] for section in ("vod", "live") for entry in data[section]}
        for entry in admitted:
            if entry["url"] not in current:
                saved = {"url": entry["url"], "name": entry["name"]}
                if entry["warehouse"]:
                    saved["warehouse"] = True
                data[entry["section"]].append(saved)
                current.add(entry["url"])
                counts["added"] += 1
        validate_depot.atomic_write_json(depot_path, data)
    print(f"Summary: {dict(counts)}")
    print("Saved" if apply and admitted else "Preview only; depot unchanged")
    return dict(counts)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("--depot", type=pathlib.Path, default=DEPOT)
    parser.add_argument("--max-check", type=int, default=60)
    parser.add_argument("--max-add", type=int, default=20)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    try:
        merge(args.source, args.depot, args.max_check, args.max_add, args.apply)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
