"""Work-root storage: record journals plus content-addressed artifacts.

Two properties matter for a corpus that must stay trustworthy:

- **append-only journals.** Every stage writes ``<name>.jsonl``; later stages read them
  back. Nothing rewrites history, so a re-run only adds observations.
- **content addressing.** Artifact bytes are stored under their own SHA-256, so the same
  package served by twenty configurations is one file with twenty provenance rows.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional

JOURNALS = ("configs", "refs", "artifacts", "inventory", "scan")


@dataclass
class Layout:
    """Filesystem layout of the work root."""

    root: Path

    @classmethod
    def create(cls, root: Path) -> "Layout":
        layout = cls(root)
        layout.ensure()
        return layout

    def ensure(self) -> None:
        (self.root / "artifacts").mkdir(parents=True, exist_ok=True)
        (self.root / "configs").mkdir(parents=True, exist_ok=True)
        (self.root / "report").mkdir(parents=True, exist_ok=True)

    # -- journals -----------------------------------------------------------
    def journal(self, name: str) -> Path:
        if name not in JOURNALS:
            raise KeyError(f"unknown journal: {name}")
        return self.root / f"{name}.jsonl"

    def append(self, name: str, record: Dict[str, Any]) -> None:
        path = self.journal(name)
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    def read(self, name: str) -> List[Dict[str, Any]]:
        path = self.journal(name)
        if not path.is_file():
            return []
        rows: List[Dict[str, Any]] = []
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
        return rows

    def latest(self, name: str, key: str) -> Dict[str, Dict[str, Any]]:
        """Return the last record per `key` value, preserving first-seen order."""
        out: Dict[str, Dict[str, Any]] = {}
        for row in self.read(name):
            value = row.get(key)
            if isinstance(value, str) and value:
                out[value] = row
        return out

    # -- artifacts ----------------------------------------------------------
    def artifact_path(self, sha256: str, extension: str = "bin") -> Path:
        suffix = extension if extension.isalnum() and len(extension) <= 8 else "bin"
        return self.root / "artifacts" / sha256[:2] / f"{sha256}.{suffix}"

    def has_artifact(self, sha256: str) -> bool:
        probe = self.root / "artifacts" / sha256[:2]
        if not probe.is_dir():
            return False
        return any(p.name.startswith(sha256 + ".") for p in probe.iterdir())

    def resolve_artifact(self, sha256: str) -> Optional[Path]:
        probe = self.root / "artifacts" / sha256[:2]
        if not probe.is_dir():
            return None
        for path in sorted(probe.iterdir()):
            if path.name.startswith(sha256 + "."):
                return path
        return None

    def save_artifact(self, body: bytes, sha256: str, extension: str = "bin") -> Path:
        path = self.artifact_path(sha256, extension)
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or path.stat().st_size != len(body):
            path.write_bytes(body)
        return path

    def save_config_snapshot(self, body: bytes) -> str:
        import hashlib

        digest = hashlib.sha256(body).hexdigest()
        path = self.root / "configs" / f"{digest[:16]}.bin"
        path.write_bytes(body)
        return str(path.relative_to(self.root))

    # -- reports ------------------------------------------------------------
    def write_report(self, name: str, text: str) -> Path:
        path = self.root / "report" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def write_json(self, name: str, payload: Any) -> Path:
        path = self.root / "report" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
        return path

    def append_ledger(self, records: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Append findings once each, keyed by their stable id."""
        path = self.root / "report" / "evidence-ledger.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        known: Dict[str, Dict[str, Any]] = {}
        if path.is_file():
            with open(path, "r", encoding="utf-8") as handle:
                for line in handle:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        row = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if "id" in row:
                        known[row["id"]] = row
        added: List[Dict[str, Any]] = []
        with open(path, "a", encoding="utf-8") as handle:
            for record in records:
                if record.get("id") in known:
                    continue
                known[record["id"]] = record
                added.append(record)
                handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
        return added

    def read_ledger(self) -> Iterator[Dict[str, Any]]:
        path = self.root / "report" / "evidence-ledger.jsonl"
        if not path.is_file():
            return
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError:
                        continue
