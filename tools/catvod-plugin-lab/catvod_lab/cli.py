"""Command line entry point for the CatVod plugin lab.

Every command other than `fetch` runs entirely offline against the journals, so analysis
is reproducible long after a configuration has gone dead.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path
from typing import List, Optional

from . import DEFAULT_DEPOT, DEFAULT_MIRROR_FILE, DEFAULT_WORK, REPO_ROOT
from . import pipeline
from .store import Layout


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="catvod_lab",
        description="Download and statically study third-party CatVod plugin packages "
                    "referenced by the depot, for improving the OpenDrive cloud pipeline.",
    )
    parser.add_argument("--repo", type=Path, default=REPO_ROOT, help="repository root")
    parser.add_argument("--depot", type=Path, default=DEFAULT_DEPOT, help="depot.json path")
    parser.add_argument("--work", type=Path, default=DEFAULT_WORK, help="work root (downloaded artifacts)")
    sub = parser.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--only", default="", help="limit to artifact ids with this sha256 prefix")

    fetch = sub.add_parser("fetch", help="download depot configs and the plugin artifacts they reference")
    fetch.add_argument("--limit", type=int, default=0, help="only the first N depot entries")
    fetch.add_argument("--include", default="", help="regex: keep matching config URLs")
    fetch.add_argument("--skip", default="", help="regex: drop matching config URLs")
    fetch.add_argument("--sections", default="vod,live", help="depot sections to read")
    fetch.add_argument("--timeout", type=float, default=20.0, help="per-request timeout in seconds")
    fetch.add_argument("--max-bytes", type=int, default=64 * 1024 * 1024, help="config download cap")
    fetch.add_argument("--max-artifact-bytes", type=int, default=96 * 1024 * 1024, help="artifact download cap")
    fetch.add_argument("--workers", type=int, default=6, help="parallel depot configs")
    fetch.add_argument("--mirror-file", type=Path, default=DEFAULT_MIRROR_FILE, help="mirror list JSON")
    fetch.add_argument("--refresh", action="store_true", help="re-download even when already stored")

    inventory_parser = sub.add_parser("inventory", parents=[common], help="structural facts per artifact (offline)")
    inventory_parser.add_argument("--refresh", action="store_true", help="re-inspect artifacts already inventoried")
    scan_parser = sub.add_parser("scan", parents=[common], help="class/string evidence and family classification (offline)")
    scan_parser.add_argument("--refresh", action="store_true", help="re-scan artifacts already scanned")
    sub.add_parser("report", help="aggregate corpus report and append evidence rows (offline)")
    improve_parser = sub.add_parser("improve", help="extract per-family OpenDrive improvement leads from the scanned corpus (offline)")
    improve_parser.add_argument("--top", type=int, default=40, help="max addresses shown per family lead")
    sub.add_parser("doctor", help="report toolchain and dependency availability")

    run = sub.add_parser("run", help="fetch, inventory, scan and report in sequence")
    run.add_argument("--limit", type=int, default=0, help="only the first N depot entries")
    run.add_argument("--include", default="", help="regex: keep matching config URLs")
    run.add_argument("--skip", default="", help="regex: drop matching config URLs")
    run.add_argument("--sections", default="vod,live", help="depot sections to read")
    run.add_argument("--timeout", type=float, default=20.0, help="per-request timeout in seconds")
    run.add_argument("--max-bytes", type=int, default=64 * 1024 * 1024, help="config download cap")
    run.add_argument("--max-artifact-bytes", type=int, default=96 * 1024 * 1024, help="artifact download cap")
    run.add_argument("--workers", type=int, default=6, help="parallel depot configs")
    run.add_argument("--refresh", action="store_true", help="re-download and re-parse everything")
    return parser


def _patterns(include: str, skip: str):
    return (
        re.compile(include) if include else None,
        re.compile(skip) if skip else None,
    )


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    layout = Layout.create(args.work)

    if args.command == "doctor":
        return _doctor(args)

    if args.command in ("fetch", "run"):
        include, skip = _patterns(getattr(args, "include", ""), getattr(args, "skip", ""))
        summary = pipeline.fetch_stage(
            layout,
            args.depot if args.depot.is_file() else args.repo / "app" / "src" / "main" / "assets" / "depot.json",
            include=include,
            skip=skip,
            limit=args.limit,
            sections=[s for s in getattr(args, "sections", "vod,live").split(",") if s],
            timeout=args.timeout,
            max_bytes=getattr(args, "max_bytes", 64 * 1024 * 1024),
            max_artifact_bytes=getattr(args, "max_artifact_bytes", 96 * 1024 * 1024),
            mirror_file=getattr(args, "mirror_file", DEFAULT_MIRROR_FILE),
            refresh=getattr(args, "refresh", False),
            workers=args.workers,
        )
        print(summary.line())
        if args.command == "fetch":
            print(f"work root: {layout.root}")
            return 0

    if args.command in ("inventory", "run"):
        only = getattr(args, "only", "")
        print(pipeline.inventory_stage(layout, only=only, refresh=getattr(args, "refresh", False)).line())

    if args.command in ("scan", "run"):
        only = getattr(args, "only", "")
        print(pipeline.scan_stage(layout, only=only, refresh=getattr(args, "refresh", False)).line())

    if args.command in ("report", "run"):
        print(pipeline.report_stage(layout, args.repo).line())

    if args.command in ("improve", "run"):
        top = getattr(args, "top", 40)
        print(pipeline.improve_stage(layout, args.repo, top=top).line())

    print(f"work root: {layout.root}")
    if args.command == "run":
        print("digest: report/corpus.md")
        print("leads:  report/opendrive-leads.md")
    return 0


def _doctor(args: argparse.Namespace) -> int:
    print(f"python: {sys.version.split()[0]}")
    try:
        import requests  # noqa: F401

        print(f"requests: available ({requests.__version__})")
    except ImportError:
        print("requests: MISSING — `pip install -r tools/catvod-plugin-lab/requirements.txt` (needed only by fetch)")
    for tool in ("java", "jadx", "apktool", "baksmali", "dexdump", "unzip"):
        found = shutil.which(tool)
        print(f"{tool}: {found or 'not installed (optional)'}")
    depot = args.depot if args.depot.is_file() else args.repo / "app" / "src" / "main" / "assets" / "depot.json"
    print(f"depot: {depot} {'found' if depot.is_file() else 'MISSING'}")
    work = Layout.create(args.work)
    print(f"work root: {work.root} (git-ignored through the repository .cache/ rule)")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
