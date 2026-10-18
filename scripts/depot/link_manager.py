"""Local data operations for the maintenance GUI's link catalog page."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import pathlib
from collections import Counter
from urllib.parse import parse_qsl, urlsplit

ROOT = pathlib.Path(__file__).resolve().parents[2]
REPORT = ROOT / ".cache/link_health.json"
OVERRIDES = ROOT / "scripts/depot/link_catalog_overrides.json"
MANAGED = ROOT / "scripts/depot/managed_links.json"
CATALOG = ROOT / "app/src/main/assets/link_catalog.json"
SECRET_KEYS = {"access_token", "token", "api_key", "apikey", "auth", "password"}


def save_json(path: pathlib.Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def load_json(path: pathlib.Path, default: dict) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default.copy()


def key_for(url: str) -> str:
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


def load_overrides(path: pathlib.Path = OVERRIDES) -> dict:
    data = load_json(path, {"version": 1, "entries": {}})
    if data.get("version") != 1 or not isinstance(data.get("entries"), dict):
        raise ValueError("Unsupported link overrides file")
    return data


def load_managed(path: pathlib.Path = MANAGED) -> dict:
    data = load_json(path, {"version": 1, "links": []})
    if data.get("version") != 1 or not isinstance(data.get("links"), list):
        raise ValueError("Unsupported managed links file")
    return data


def validate_url(url: str) -> str:
    url = url.strip()
    parsed = urlsplit(url)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or
            parsed.username or parsed.password or any(ord(char) < 32 for char in url)):
        raise ValueError("Enter an HTTP(S) URL without embedded username/password")
    return url


def add_managed(url: str, name: str, path: pathlib.Path = MANAGED) -> dict:
    url = validate_url(url)
    name = name.strip()[:80]
    if not name:
        raise ValueError("Enter a source name")
    data = load_managed(path)
    if any(row.get("url") == url for row in data["links"]):
        raise ValueError("This URL is already in managed links")
    data["links"].append({"name": name, "url": url})
    save_json(path, data)
    return data


def remove_managed(url: str, path: pathlib.Path = MANAGED) -> bool:
    data = load_managed(path)
    remaining = [row for row in data["links"] if row.get("url") != url]
    if len(remaining) == len(data["links"]):
        return False
    data["links"] = remaining
    save_json(path, data)
    return True


def replace_managed(old_url: str, new_url: str, path: pathlib.Path = MANAGED) -> bool:
    new_url = validate_url(new_url)
    data = load_managed(path)
    target = next((row for row in data["links"] if row.get("url") == old_url), None)
    if target is None:
        return False
    if any(row.get("url") == new_url and row is not target for row in data["links"]):
        raise ValueError("This URL is already in managed links")
    target["url"] = new_url
    save_json(path, data)
    return True


def merge_managed(report: dict, managed: dict) -> dict:
    seen = {row["url"] for row in report.get("results", [])}
    for entry in managed["links"]:
        url = validate_url(entry["url"])
        if url not in seen:
            report.setdefault("results", []).append({
                "name": entry["name"], "url": url, "section": "managed",
                "status": "uncertain", "category": "unknown", "statusCode": 0,
                "contentType": "", "reason": "Not tested yet",
            })
            seen.add(url)
    update_counts(report)
    return report


def merge_curated(report: dict, depot: dict) -> dict:
    seen = {row["url"] for row in report.get("results", [])}
    for kind in ("vod", "live"):
        for entry in depot.get(kind, []):
            url = entry.get("url", "")
            if url.startswith(("http://", "https://")) and url not in seen:
                report.setdefault("results", []).append({
                    "name": entry.get("name", url), "url": url, "section": "curated",
                    "status": "uncertain", "category": "unknown", "statusCode": 0,
                    "contentType": "", "reason": "Curated source; not tested in this report",
                })
                seen.add(url)
    update_counts(report)
    return report


def update_counts(report: dict) -> None:
    counts = dict(report.get("counts", {}))
    for status in ("reachable", "uncertain", "unsupported", "gone"):
        counts.pop(status, None)
    counts.update(Counter(row.get("status", "uncertain") for row in report.get("results", [])))
    report["counts"] = counts


def set_verdict(report: dict, url: str, verdict: dict) -> dict:
    row = next((entry for entry in report["results"] if entry["url"] == url), None)
    if row is None:
        raise KeyError("Link is not in the report")
    row.update(verdict)
    row["testedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    update_counts(report)
    return row


def set_override(url: str, *, name: str | None = None,
                 enabled: bool | None = None, path: pathlib.Path = OVERRIDES) -> dict:
    data = load_overrides(path)
    key = key_for(url)
    entry = dict(data["entries"].get(key, {}))
    if name is not None:
        name = name.strip()[:80]
        if not name:
            raise ValueError("Enter a source name")
        entry["name"] = name
    if enabled is not None:
        entry["enabled"] = enabled
    data["entries"][key] = entry
    save_json(path, data)
    return entry


def display_url(url: str) -> str:
    parsed = urlsplit(url)
    if any(key.lower() in SECRET_KEYS for key, _ in parse_qsl(parsed.query, keep_blank_values=True)):
        return f"{parsed.scheme}://{parsed.netloc}{parsed.path}?credential=••••"
    return url


def redact(text: str, url: str) -> str:
    return text.replace(url, display_url(url)) if display_url(url) != url else text
