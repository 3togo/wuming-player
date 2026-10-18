"""Depot loading.

``app/src/main/assets/depot.json`` is a display-only catalogue of third-party
configuration endpoints: it holds nothing but ``{"vod": [{url, name}], "live": [...]}``
arrays. Nothing auto-loads those configs in production, which makes it a clean,
stable seed list for building a plugin corpus.

See `.codebuddy/memory/depot.md` for the ingest rules that produce this file.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional, Pattern

SECTIONS = ("vod", "live")


@dataclass(frozen=True)
class DepotEntry:
    """One configuration endpoint listed in the depot."""

    url: str
    name: str = ""
    section: str = "vod"

    @property
    def host(self) -> str:
        stripped = re.sub(r"^[a-zA-Z]+://", "", self.url)
        return stripped.split("/")[0]


def load_entries(
    depot_path: Path,
    sections: Iterable[str] = SECTIONS,
    include: Optional[Pattern[str]] = None,
    skip: Optional[Pattern[str]] = None,
) -> List[DepotEntry]:
    """Read the depot file and return the eligible configuration URLs.

    Duplicate URLs are collapsed, keeping the first occurrence.
    """
    with open(depot_path, "r", encoding="utf-8") as handle:
        depot = json.load(handle)

    wanted = list(sections)
    entries: List[DepotEntry] = []
    seen = set()
    for section in wanted:
        rows = depot.get(section)
        if not isinstance(rows, list):
            continue
        for row in rows:
            if not isinstance(row, dict):
                continue
            url = str(row.get("url") or "").strip()
            if not url:
                continue
            if include is not None and not include.search(url):
                continue
            if skip is not None and skip.search(url):
                continue
            if url in seen:
                continue
            seen.add(url)
            entries.append(DepotEntry(url=url, name=str(row.get("name") or "").strip(), section=section))
    return entries
