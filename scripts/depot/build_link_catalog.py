#!/usr/bin/env python3
"""Build a deterministic, offline browsing catalog from an untrusted link list.

The default uses a prior screening report; --screen refreshes that report first.
Reachability does not certify playback. The curated depot remains separate, and
its URLs are omitted from the catalog to avoid duplicate choices.
"""

from __future__ import annotations

import argparse
import json
import pathlib
from urllib.parse import parse_qsl, urlsplit

from import_links import candidates
from link_manager import key_for, load_managed, load_overrides

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEPOT = ROOT / "app/src/main/assets/depot.json"
OUTPUT = ROOT / "app/src/main/assets/link_catalog.json"
REPORT = ROOT / ".cache/link_health.json"
PAGE_SIZE = 50
SECRET_KEYS = {"access_token", "token", "api_key", "apikey", "auth", "password"}


def credential_query(url: str) -> bool:
    return any(key.lower() in SECRET_KEYS for key, _ in parse_qsl(urlsplit(url).query,
                                                                    keep_blank_values=True))


def build(source: pathlib.Path, depot: pathlib.Path, report: pathlib.Path,
          overrides: pathlib.Path | None = None, managed: pathlib.Path | None = None) -> dict:
    entries, _ = candidates(source.read_text(encoding="utf-8-sig"))
    if managed is not None:
        entries.extend(load_managed(managed)["links"])
    health = json.loads(report.read_text(encoding="utf-8"))
    if health.get("version") != 1:
        raise ValueError("Unsupported link health report")
    verdicts = {row["url"]: row for row in health["results"]}
    missing = {entry["url"] for entry in entries} - verdicts.keys()
    if missing:
        raise ValueError(f"Health report misses {len(missing)} candidate(s); rerun check_link_catalog.py")
    names = {entry["url"]: entry for entry in entries}
    health["results"] = [{**names.get(row["url"], {}), **row} for row in health["results"]]
    return build_from_report(depot, health, overrides)


def build_from_report(depot: pathlib.Path, health: dict,
                      overrides: pathlib.Path | None = None) -> dict:
    saved = json.loads(depot.read_text(encoding="utf-8"))
    curated = {item["url"] for section in ("vod", "live") for item in saved[section]}
    if health.get("version") != 1:
        raise ValueError("Unsupported link health report")
    choices = load_overrides(overrides)["entries"] if overrides is not None else {}
    grouped: dict[tuple[str, str], list[dict[str, str]]] = {}
    for entry in health["results"]:
        url = entry["url"]
        verdict = entry
        override = choices.get(key_for(url), {})
        query = urlsplit(url).query
        # Retain checked credential-bearing sources for use, but mark them for
        # redaction in every source browser. Other query URLs still need review.
        if (url in curated or (query and not credential_query(url)) or
                verdict["status"] != "reachable" or override.get("enabled") is False):
            continue
        kind = verdict["category"]
        if kind not in ("vod", "live"):
            continue
        family = "GitHub" if urlsplit(url).hostname.lower() in (
            "github.com", "raw.githubusercontent.com", "gist.githubusercontent.com",
        ) else "Other"
        grouped.setdefault((kind, family), []).append({
            "name": override.get("name", entry.get("name", url))[:80], "url": url,
            "warehouse": verdict["reason"].startswith(("depot:", "2423 decoded depot:")),
            "protected": bool(query),
        })
    groups = []
    for (kind, family), items in sorted(grouped.items()):
        for start in range(0, len(items), PAGE_SIZE):
            page = items[start:start + PAGE_SIZE]
            end = start + len(page)
            groups.append({
                "name": f"{kind.upper()} · {family} · {start + 1}–{end}",
                "kind": kind,
                "entries": page,
            })
    return {"version": 1, "screenedAt": health["checkedAt"],
            "screening": "reachable response with compatible content; playback unverified",
            "groups": groups}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("--depot", type=pathlib.Path, default=DEPOT)
    parser.add_argument("--report", type=pathlib.Path, default=REPORT)
    parser.add_argument("--output", type=pathlib.Path, default=OUTPUT)
    parser.add_argument("--overrides", type=pathlib.Path,
                        default=ROOT / "scripts/depot/link_catalog_overrides.json")
    parser.add_argument("--managed", type=pathlib.Path,
                        default=ROOT / "scripts/depot/managed_links.json")
    parser.add_argument("--screen", action="store_true", help="probe all candidates before building")
    args = parser.parse_args()
    if args.screen:
        import check_link_catalog
        report = check_link_catalog.check(args.source, progress=True, managed=args.managed)
        check_link_catalog.save_report(report, args.report)
        print(f"Screened {len(report['results'])} candidates: {report['counts']}", flush=True)
    catalog = build(args.source, args.depot, args.report, args.overrides, args.managed)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(f"Wrote {sum(len(group['entries']) for group in catalog['groups'])} screened links "
          f"in {len(catalog['groups'])} groups to {args.output}")


if __name__ == "__main__":
    main()
