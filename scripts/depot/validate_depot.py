#!/usr/bin/env python3
"""Validate depot links, back up the depot, and remove unusable entries.

The backup is created before the depot is parsed or any network request is made.
By default this script validates ``app/src/main/assets/depot.json`` and writes a
backup beside it named like ``depot.json.20260803_153012_123456+0800.bak``.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import http.client
import json
import os
import pathlib
import re
import shutil
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Iterable


REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_DEPOT = REPO_ROOT / "app/src/main/assets/depot.json"
DEFAULT_ASSET_ROOT = REPO_ROOT / "app/src/main/assets"
DEFAULT_TIMEOUT = 15.0
DEFAULT_RETRIES = 2
DEFAULT_WORKERS = 8
DEFAULT_DEEP_SAMPLES = 12
MAX_PROBE_BYTES = 1024 * 1024
MAX_NESTED_BYTES = 64 * 1024
USER_AGENT = "TVBoxDepotValidator/1.0"
SECTIONS = ("vod", "live")
NESTED_KEYS = {"api", "ext", "jar", "server", "spider", "url"}
URL_PATTERN = re.compile(r"https?://[^\s\"'<>\\]+", re.IGNORECASE)
IGNORED_SUFFIXES = (
    ".avif", ".bmp", ".gif", ".ico", ".jpeg", ".jpg", ".png", ".svg", ".webp"
)


@dataclasses.dataclass(frozen=True)
class Check:
    section: str
    index: int
    name: str
    url: str
    valid: bool
    reason: str


@dataclasses.dataclass(frozen=True)
class Fetch:
    valid: bool
    reason: str
    content: bytes = b""
    final_url: str = ""


def timestamp() -> str:
    """Return a sortable, collision-resistant local timestamp."""
    return dt.datetime.now().astimezone().strftime("%Y%m%d_%H%M%S_%f%z")


def backup_depot(depot_path: pathlib.Path) -> pathlib.Path:
    """Create and return a timestamped backup next to ``depot_path``."""
    backup_path = depot_path.with_name(f"{depot_path.name}.{timestamp()}.bak")
    shutil.copy2(depot_path, backup_path)
    return backup_path


def useful_content(content: bytes) -> tuple[bool, str]:
    """Reject empty responses and common HTML error/login pages."""
    if not content or not content.strip():
        return False, "empty response"
    probe = content.lstrip(b"\xef\xbb\xbf\x00\t\r\n ").lower()
    if probe.startswith(b"<!doctype html") or probe.startswith(b"<html"):
        return False, "HTML response"
    return True, f"loaded {len(content)} byte(s)"


def fetch_asset(url: str, asset_root: pathlib.Path) -> Fetch:
    relative = urllib.parse.unquote(url.removeprefix("assets://")).lstrip("/")
    if not relative:
        return Fetch(False, "empty asset path")
    target = (asset_root / relative).resolve()
    try:
        target.relative_to(asset_root.resolve())
    except ValueError:
        return Fetch(False, "asset path escapes the asset directory")
    try:
        content = target.read_bytes()[:MAX_PROBE_BYTES]
        valid, reason = useful_content(content)
        return Fetch(valid, reason, content, target.as_uri())
    except FileNotFoundError:
        return Fetch(False, "asset does not exist")
    except OSError as error:
        return Fetch(False, f"asset read failed: {error}")


def check_asset(url: str, asset_root: pathlib.Path) -> tuple[bool, str]:
    result = fetch_asset(url, asset_root)
    return result.valid, result.reason


def request_url(url: str) -> str:
    """Convert an internationalized URL into an urllib-compatible ASCII URL."""
    parts = urllib.parse.urlsplit(url)
    if not parts.hostname:
        raise ValueError("URL has no hostname")
    hostname = parts.hostname.encode("idna").decode("ascii")
    if ":" in hostname and not hostname.startswith("["):
        hostname = f"[{hostname}]"
    userinfo = ""
    if parts.username is not None:
        userinfo = urllib.parse.quote(parts.username, safe="%")
        if parts.password is not None:
            userinfo += ":" + urllib.parse.quote(parts.password, safe="%")
        userinfo += "@"
    port = f":{parts.port}" if parts.port is not None else ""
    netloc = f"{userinfo}{hostname}{port}"
    path = urllib.parse.quote(parts.path, safe="/%:@!$&'()*+,;=-._~")
    query = urllib.parse.quote(parts.query, safe="%=&?/:;+,@!$'()*-._~")
    return urllib.parse.urlunsplit((parts.scheme, netloc, path, query, parts.fragment))


def fetch_http(
    url: str,
    timeout: float,
    retries: int,
    max_bytes: int = MAX_PROBE_BYTES,
    range_request: bool = False,
) -> Fetch:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json, text/plain, application/x-mpegURL, */*",
    }
    if range_request:
        headers["Range"] = f"bytes=0-{max_bytes - 1}"
    request = urllib.request.Request(
        request_url(url),
        headers=headers,
    )
    context = ssl.create_default_context()
    last_error = "request failed"
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
                status = getattr(response, "status", 200)
                if status < 200 or status >= 300:
                    last_error = f"HTTP {status}"
                else:
                    content = response.read(max_bytes)
                    valid, reason = useful_content(content)
                    final_url = getattr(response, "geturl", lambda: url)()
                    return Fetch(valid, reason, content, final_url)
        except urllib.error.HTTPError as error:
            last_error = f"HTTP {error.code}"
            error.close()
        except urllib.error.URLError as error:
            last_error = f"connection failed: {error.reason}"
        except TimeoutError:
            last_error = "timeout"
        except http.client.HTTPException as error:
            last_error = f"HTTP protocol failed: {error}"
        except (OSError, ValueError) as error:
            last_error = f"request failed: {error}"
        if attempt < retries:
            time.sleep(min(0.25 * (2**attempt), 1.0))
    return Fetch(False, last_error)


def check_http(url: str, timeout: float, retries: int) -> tuple[bool, str]:
    result = fetch_http(url, timeout, retries)
    return result.valid, result.reason


def nested_candidates(content: bytes, base_url: str) -> list[str]:
    """Extract operational child URLs from a JSON config or text playlist."""
    text = content.decode("utf-8", errors="replace").lstrip("\ufeff")
    weighted: list[tuple[int, str]] = []

    def add(value: str, priority: int) -> None:
        raw_values = URL_PATTERN.findall(value)
        stripped = value.strip()
        if not raw_values and stripped.startswith(("/", "./", "../")):
            raw_values = [urllib.parse.urljoin(base_url, stripped)]
        for candidate in raw_values:
            candidate = candidate.rstrip(",)]}")
            if ";md5;" in candidate.lower():
                candidate = candidate[: candidate.lower().index(";md5;")]
            if any(marker in candidate for marker in ("{", "}", "[", "]")):
                continue
            path = urllib.parse.urlsplit(candidate).path.lower()
            if path.endswith(IGNORED_SUFFIXES):
                continue
            weighted.append((priority, candidate))

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                normalized = str(key).lower()
                if normalized in NESTED_KEYS and isinstance(child, str):
                    priority = 0 if normalized == "api" else 1 if normalized == "url" else 2
                    add(child, priority)
                if isinstance(child, (dict, list)):
                    walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        for candidate in URL_PATTERN.findall(text):
            add(candidate, 0)
    else:
        walk(parsed)

    source = urllib.parse.urldefrag(base_url)[0]
    result: list[str] = []
    seen: set[str] = set()
    for _, candidate in sorted(weighted, key=lambda item: item[0]):
        candidate = urllib.parse.urldefrag(candidate)[0]
        if candidate == source or candidate in seen:
            continue
        seen.add(candidate)
        result.append(candidate)
    return result


def check_nested(
    content: bytes,
    base_url: str,
    asset_root: pathlib.Path,
    timeout: float,
    retries: int,
    sample_limit: int,
) -> tuple[bool, str]:
    candidates = nested_candidates(content, base_url)
    if not candidates:
        return True, "no testable nested URLs"
    selected = candidates[:sample_limit]
    failures: list[str] = []
    for position, candidate in enumerate(selected, 1):
        if candidate.startswith("assets://"):
            result = fetch_asset(candidate, asset_root)
        else:
            result = fetch_http(
                candidate,
                timeout,
                retries,
                max_bytes=MAX_NESTED_BYTES,
                range_request=True,
            )
        if result.valid:
            host = urllib.parse.urlsplit(candidate).hostname or candidate
            return True, f"nested reachable: {host} ({position}/{len(selected)} checked)"
        failures.append(result.reason)
    details = ", ".join(dict.fromkeys(failures))
    return False, f"0/{len(selected)} nested URLs reachable ({details})"


def check_entry(
    section: str,
    index: int,
    entry: Any,
    asset_root: pathlib.Path,
    timeout: float,
    retries: int,
    deep: bool,
    deep_samples: int,
) -> Check:
    if not isinstance(entry, dict):
        return Check(section, index, "<invalid entry>", "", False, "entry is not an object")
    name = str(entry.get("name") or f"{section}[{index}]")
    url = entry.get("url")
    if not isinstance(url, str) or not url.strip():
        return Check(section, index, name, "", False, "missing or empty URL")
    url = url.strip()
    result: Fetch
    if url.startswith("assets://"):
        result = fetch_asset(url, asset_root)
    else:
        try:
            scheme = urllib.parse.urlsplit(url).scheme.lower()
        except ValueError as error:
            result = Fetch(False, f"malformed URL: {error}")
        else:
            if scheme in {"http", "https"}:
                result = fetch_http(url, timeout, retries)
            else:
                result = Fetch(False, "unsupported URL scheme")
    if result.valid and deep:
        nested_valid, nested_reason = check_nested(
            result.content,
            result.final_url or url,
            asset_root,
            timeout,
            retries,
            deep_samples,
        )
        return Check(
            section,
            index,
            name,
            url,
            nested_valid,
            f"{result.reason}; {nested_reason}",
        )
    return Check(section, index, name, url, result.valid, result.reason)


def entries(depot: dict[str, Any]) -> Iterable[tuple[str, int, Any]]:
    for section in SECTIONS:
        values = depot.get(section, [])
        if not isinstance(values, list):
            raise ValueError(f"depot section '{section}' must be an array")
        for index, entry in enumerate(values):
            yield section, index, entry


def validate_entries(
    depot: dict[str, Any],
    asset_root: pathlib.Path,
    timeout: float,
    retries: int,
    workers: int,
    deep: bool,
    deep_samples: int,
) -> list[Check]:
    work = list(entries(depot))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [
            executor.submit(
                check_entry, section, index, entry, asset_root, timeout, retries,
                deep, deep_samples
            )
            for section, index, entry in work
        ]
        return [future.result() for future in futures]


def remove_invalid(depot: dict[str, Any], checks: list[Check]) -> None:
    invalid = {(check.section, check.index) for check in checks if not check.valid}
    for section in SECTIONS:
        depot[section] = [
            entry
            for index, entry in enumerate(depot.get(section, []))
            if (section, index) not in invalid
        ]


def atomic_write_json(path: pathlib.Path, value: dict[str, Any]) -> None:
    """Replace ``path`` only after a complete JSON file is on disk."""
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary_path = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if path.exists():
            shutil.copymode(path, temporary_path)
        os.replace(temporary_path, path)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()


def validate_depot(
    depot_path: pathlib.Path,
    asset_root: pathlib.Path,
    timeout: float = DEFAULT_TIMEOUT,
    retries: int = DEFAULT_RETRIES,
    workers: int = DEFAULT_WORKERS,
    dry_run: bool = False,
    deep: bool = True,
    deep_samples: int = DEFAULT_DEEP_SAMPLES,
) -> tuple[pathlib.Path, list[Check]]:
    """Back up, validate, and optionally prune a depot file."""
    depot_path = depot_path.resolve()
    asset_root = asset_root.resolve()
    if not depot_path.is_file():
        raise FileNotFoundError(f"depot not found: {depot_path}")
    if timeout <= 0:
        raise ValueError("timeout must be greater than zero")
    if retries < 0:
        raise ValueError("retries cannot be negative")
    if workers <= 0:
        raise ValueError("workers must be greater than zero")
    if deep_samples <= 0:
        raise ValueError("deep samples must be greater than zero")

    backup_path = backup_depot(depot_path)
    with depot_path.open("r", encoding="utf-8") as stream:
        depot = json.load(stream)
    if not isinstance(depot, dict):
        raise ValueError("depot root must be an object")

    checks = validate_entries(
        depot, asset_root, timeout, retries, workers, deep, deep_samples
    )
    if not dry_run and any(not check.valid for check in checks):
        remove_invalid(depot, checks)
        atomic_write_json(depot_path, depot)
    return backup_path, checks


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(
        description="Back up depot.json, test every link, and remove unusable entries."
    )
    value.add_argument("depot", nargs="?", type=pathlib.Path, default=DEFAULT_DEPOT)
    value.add_argument(
        "--asset-root",
        type=pathlib.Path,
        default=DEFAULT_ASSET_ROOT,
        help="directory used to resolve assets:// links",
    )
    value.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    value.add_argument("--retries", type=int, default=DEFAULT_RETRIES)
    value.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    value.add_argument("--deep-samples", type=int, default=DEFAULT_DEEP_SAMPLES)
    value.add_argument(
        "--no-deep",
        action="store_true",
        help="only validate top-level depot URLs",
    )
    value.add_argument(
        "--dry-run",
        action="store_true",
        help="report failures without changing the depot (a backup is still created)",
    )
    return value


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        backup_path, checks = validate_depot(
            args.depot,
            args.asset_root,
            timeout=args.timeout,
            retries=args.retries,
            workers=args.workers,
            dry_run=args.dry_run,
            deep=not args.no_deep,
            deep_samples=args.deep_samples,
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    print(f"Backup: {backup_path}")
    for check in checks:
        marker = "OK" if check.valid else "REMOVE"
        print(f"[{marker:6}] {check.section}: {check.name} — {check.reason}")
        if not check.valid and check.url:
            print(f"         {check.url}")

    invalid = [check for check in checks if not check.valid]
    valid = len(checks) - len(invalid)
    action = "would remove" if args.dry_run else "removed"
    print(f"Summary: {valid} valid, {len(invalid)} invalid; {action} {len(invalid)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
