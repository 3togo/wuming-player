"""Pipeline stages shared by the CLI.

Stage boundaries are deliberate:

- `fetch` is the only stage that touches the network, so every later stage replays from
  the journals and stays reproducible;
- every stage writes append-only journals, never rewriting earlier observations;
- every stage returns a small summary instead of printing, so `run` can chain them.
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Pattern

from . import artifact as artifact_mod
from . import configdoc, depot, providers, resolve
from . import http as http_mod
from .store import Layout

MAX_REFS_PER_CONFIG = 16
REPO_SOURCE_ROOTS = ("app/src/main", "libraries")
REPO_SOURCE_GLOBS = ("**/*.java", "**/*.kt")


def _now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


@dataclass
class StageSummary:
    stage: str
    counts: Dict[str, int] = field(default_factory=dict)
    notes: List[str] = field(default_factory=list)

    def line(self) -> str:
        rendered = ", ".join(f"{key}={value}" for key, value in self.counts.items())
        return f"[{self.stage}] {rendered}"


# --------------------------------------------------------------------- fetch


def fetch_stage(
    layout: Layout,
    depot_path: Path,
    *,
    include: Optional[Pattern[str]] = None,
    skip: Optional[Pattern[str]] = None,
    limit: int = 0,
    sections: List[str] = None,
    timeout: float = 20.0,
    max_bytes: int = 64 * 1024 * 1024,
    max_artifact_bytes: int = 96 * 1024 * 1024,
    mirror_file: Optional[Path] = None,
    refresh: bool = False,
    workers: int = 6,
) -> StageSummary:
    """Stage 1 + 2: download depot configs and every plugin artifact they reference."""
    summary = StageSummary("fetch", {
        "configs": 0, "config_fetch_failed": 0, "config_decode_failed": 0,
        "refs": 0, "artifacts": 0, "artifact_duplicates": 0, "artifact_failed": 0,
        "md5_mismatch": 0, "not_an_artifact": 0,
    })

    mirrors = http_mod.load_mirrors(mirror_file)
    config_fetcher = http_mod.Fetcher(timeout=timeout, retries=2, max_bytes=max_bytes, mirrors=mirrors)
    md5_fetcher = http_mod.Fetcher(timeout=timeout, retries=1, max_bytes=1024 * 1024, mirrors=mirrors)
    artifact_fetcher = http_mod.Fetcher(timeout=timeout * 1.5, retries=1, max_bytes=max_artifact_bytes, mirrors=mirrors)

    entries = depot.load_entries(depot_path, sections or list(depot.SECTIONS), include, skip)
    if limit and limit > 0:
        entries = entries[:limit]
    summary.counts["configs"] = len(entries)

    known_artifacts = {} if refresh else layout.latest("artifacts", "url")

    def handle(entry: depot.DepotEntry) -> None:
        result = config_fetcher.get(entry.url)
        if not result.ok:
            summary.counts["config_fetch_failed"] += 1
            layout.append("configs", {
                "fetched_at": _now(), "ok": False, "depot_name": entry.name,
                "section": entry.section, "config_url": entry.url,
                "error": result.error, "status": result.status,
            })
            return

        snapshot = layout.save_config_snapshot(result.body)
        base: Dict[str, Any] = {
            "fetched_at": _now(), "ok": True, "depot_name": entry.name,
            "section": entry.section, "config_url": entry.url, "final_url": result.final_url,
            "via": result.via_mirror, "status": result.status, "content_type": result.content_type,
            "bytes": len(result.body), "sha256": result.sha256, "snapshot": snapshot,
        }
        try:
            doc = configdoc.parse_payload(result.body)
        except Exception as error:
            summary.counts["config_decode_failed"] += 1
            base.update(ok=False, error=f"payload: {type(error).__name__}: {error}"[:300])
            layout.append("configs", base)
            return

        base.update(sites=len(doc.sites), spider_sites=len(doc.spider_sites), has_spider=bool(doc.spider))
        layout.append("configs", base)

        for reference in doc.references()[:MAX_REFS_PER_CONFIG]:
            summary.counts["refs"] += 1
            resolved = resolve.resolve_reference(reference["ref"], result.final_url)
            record = {
                "fetched_at": _now(), "config_url": entry.url, "final_url": result.final_url,
                "raw": reference["ref"], "origin": reference["origin"],
                "site_key": reference["site_key"], "site_name": reference["site_name"],
                "site_api": reference["site_api"], "kind": resolved.kind,
                "url": resolved.url, "declared_md5": resolved.md5,
                "declared_md5_is_url": resolved.md5_is_url,
                "skip_reason": resolved.skip_reason,
            }
            layout.append("refs", record)
            if not resolved.fetchable:
                continue
            store_artifact(layout, resolved, record, artifact_fetcher, md5_fetcher, summary, known_artifacts)

    _map_limited(handle, entries, workers)
    return summary


def store_artifact(layout: Layout, resolved: resolve.Resolved, ref_record: Dict[str, Any],
                   fetcher: http_mod.Fetcher, md5_fetcher: http_mod.Fetcher,
                   summary: StageSummary, known_artifacts: Dict[str, Dict[str, Any]]) -> None:
    """Download, verify and content-address one plugin artifact."""
    base = dict(ref_record)
    base.update(kind="artifact", fetched_at=_now())

    cached = known_artifacts.get(resolved.url)
    if cached and cached.get("sha256") and layout.has_artifact(cached["sha256"]):
        base.update(sha256=cached["sha256"], status=cached.get("status"), size=cached.get("size"),
                    md5=cached.get("md5"), md5_ok=cached.get("md5_ok"), ok=True, cached=True,
                    stored=cached.get("stored", ""))
        layout.append("artifacts", base)
        summary.counts["artifact_duplicates"] += 1
        return

    expected = resolve.expected_md5(resolved, md5_fetcher.get)
    result = fetcher.get(resolved.url)
    if not result.ok:
        summary.counts["artifact_failed"] += 1
        base.update(ok=False, status=result.status, error=result.error, expected_md5=expected or "")
        layout.append("artifacts", base)
        return

    digest = hashlib.sha256(result.body).hexdigest()
    refusal = artifact_mod.accepts_payload(result.body)
    if refusal:
        # Record the attempt so the refusal is explainable, but keep neither the bytes
        # nor rows that later stages would treat as a member of the corpus.
        summary.counts["not_an_artifact"] += 1
        base.update(ok=False, status=result.status, error=f"not_an_artifact: {refusal}",
                    sha256=digest, size=len(result.body), content_type=result.content_type)
        layout.append("artifacts", base)
        return

    md5_ok = None if expected is None else (result.md5.lower() == expected.lower())
    if md5_ok is False:
        # Do not keep bytes whose declared integrity does not hold: either the artifact
        # was tampered with or the config is stale, and neither is a corpus member.
        summary.counts["md5_mismatch"] += 1
        base.update(ok=False, status=result.status, error="md5_mismatch", sha256=digest,
                    md5=result.md5, expected_md5=expected, size=len(result.body), md5_ok=False)
        layout.append("artifacts", base)
        return

    extension = resolved.extension_hint()
    path = layout.save_artifact(result.body, digest, extension)
    summary.counts["artifacts"] += 1
    known_artifacts[resolved.url] = {"sha256": digest, "status": result.status, "size": len(result.body),
                                     "md5": result.md5, "md5_ok": md5_ok, "stored": str(path)}
    base.update(ok=True, sha256=digest, md5=result.md5, expected_md5=expected or "", md5_ok=md5_ok,
                size=len(result.body), status=result.status, via=result.via_mirror,
                content_type=result.content_type, final_url=result.final_url,
                stored=str(path), cached=False)
    layout.append("artifacts", base)


# ----------------------------------------------------------------- inventory


def _unique_artifacts(layout: Layout) -> Dict[str, Dict[str, Any]]:
    """Stored artifacts keyed by sha256, keeping the first provenance record."""
    unique: Dict[str, Dict[str, Any]] = {}
    for row in layout.read("artifacts"):
        sha = row.get("sha256")
        if not sha or sha in unique:
            continue
        path = layout.resolve_artifact(sha)
        if path is None:
            continue
        unique[sha] = row
    return unique


def inventory_stage(layout: Layout, *, only: str = "", refresh: bool = False) -> StageSummary:
    """Stage 3: structural facts per artifact (no network)."""
    summary = StageSummary("inventory", {"artifacts": 0, "parsed": 0, "skipped": 0, "failed": 0})
    done = {} if refresh else layout.latest("inventory", "sha256")
    for sha, row in _unique_artifacts(layout).items():
        summary.counts["artifacts"] += 1
        if only and not sha.startswith(only):
            continue
        if sha in done:
            summary.counts["skipped"] += 1
            continue
        path = layout.resolve_artifact(sha)
        try:
            report = artifact_mod.inspect(path)
        except Exception as error:
            summary.counts["failed"] += 1
            layout.append("inventory", {"inspected_at": _now(), "sha256": sha, "ok": False,
                                        "error": f"{type(error).__name__}: {error}"[:300]})
            continue
        docs = report.pop("_docs", [])
        report.pop("_asset_docs", [])
        catvod_classes = sorted({cls for doc in docs for cls in doc.catvod_classes})
        layout.append("inventory", {
            "inspected_at": _now(), "sha256": sha, "ok": True,
            "source_url": row.get("url", ""), "size_bytes": report["size_bytes"],
            "head_hex": report["head_hex"], "container": report["container"],
            "loader_shape": report["loader_shape"], "elf": report["elf"],
            "zip": report["zip"], "embedded": report["embedded"], "notes": report["notes"],
            "dex_images": [
                {"source": d["source"], "version": d["version"], "class_count": d["class_count"],
                 "string_count": d["string_count"], "error": d["error"]}
                for d in report["dex"]
            ],
            "assets": report["assets"],
            "catvod_class_count": len(catvod_classes),
            "catvod_classes": catvod_classes[:200],
        })
        summary.counts["parsed"] += 1
    return summary


# ---------------------------------------------------------------------- scan


def scan_stage(layout: Layout, *, only: str = "", refresh: bool = False, endpoint_limit: int = 40) -> StageSummary:
    """Stage 4: class/string evidence and family classification (no network)."""
    summary = StageSummary("scan", {"artifacts": 0, "parsed": 0, "skipped": 0, "failed": 0,
                                    "cloud_related": 0, "assets_readable": 0, "assets_encrypted": 0})
    done = {} if refresh else layout.latest("scan", "sha256")
    for sha, row in _unique_artifacts(layout).items():
        summary.counts["artifacts"] += 1
        if only and not sha.startswith(only):
            continue
        if sha in done:
            summary.counts["skipped"] += 1
            continue
        path = layout.resolve_artifact(sha)
        try:
            report = artifact_mod.inspect(path)
        except Exception as error:
            summary.counts["failed"] += 1
            layout.append("scan", {"scanned_at": _now(), "sha256": sha, "ok": False,
                                   "error": f"{type(error).__name__}: {error}"[:300]})
            continue
        docs = report.pop("_docs", [])
        assets = report.pop("_asset_docs", [])
        classes = sorted({cls for doc in docs for cls in doc.classes})
        # Evidence sources are merged: recent bundles implement netdisk flows in bundled
        # JavaScript, so restricting to DEX strings would report them as having none.
        strings = sorted({value for doc in docs for value in doc.strings if value}
                         | {value for doc in assets for value in doc.strings if value})
        asset_strings = sum(len(doc.strings) for doc in assets)
        matches = providers.classify(classes, strings)
        cloud = providers.is_cloud_related(classes, strings, matches)
        if cloud:
            summary.counts["cloud_related"] += 1
        if any(doc.readable for doc in assets):
            summary.counts["assets_readable"] = summary.counts.get("assets_readable", 0) + 1
        if any(doc.encrypted for doc in assets):
            summary.counts["assets_encrypted"] = summary.counts.get("assets_encrypted", 0) + 1
        layout.append("scan", {
            "scanned_at": _now(), "sha256": sha, "ok": True,
            "source_url": row.get("url", ""), "site_name": row.get("site_name", ""),
            "container": report["container"], "loader_shape": report["loader_shape"],
            "class_count": len(classes), "string_count": len(strings),
            "cloud_related": cloud,
            "assets": report["assets"],
            "evidence_sources": sorted({source for source in
                                        (["dex"] if any(d.strings or d.classes for d in docs) else [])
                                        + (["assets"] if asset_strings else [])
                                        + (["encrypted-assets"] if any(a.encrypted for a in assets) else [])}),
            "catvod_classes": [c for c in classes if c.startswith("com.github.catvod.")][:120],
            "families": [
                {
                    "id": m.family.id, "label": m.family.label, "confidence": m.confidence,
                    "opendrive_provider": m.family.opendrive_provider,
                    "opendrive_kind": m.family.opendrive_kind, "coverage": m.family.coverage,
                    "class_hits": m.class_hits[:8], "host_hits": m.host_hits[:6],
                    "endpoint_hits": m.endpoint_hits[:6],
                    "classification": "unverified",
                }
                for m in matches
            ],
            "endpoint_candidates": providers.endpoint_candidates(strings, endpoint_limit),
        })
        summary.counts["parsed"] += 1
    return summary


# -------------------------------------------------------------------- report


def _repo_has_literal(repo_root: Path, candidates: List[str]) -> Dict[str, bool]:
    """Check which literals appear anywhere in our own main sources.

    Absence is a lead, never proof: rephrased constants, building a URL from parts and a
    different transport all produce false negatives.
    """
    lowered = [(c, c.lower()) for c in candidates]
    pending = {value: False for value, _ in lowered}
    roots = [repo_root / "app" / "src" / "main", repo_root / "libraries"]
    files: List[Path] = []
    for root in roots:
        if not root.is_dir():
            continue
        for pattern in REPO_SOURCE_GLOBS:
            files.extend(root.glob(pattern))
    for file_path in files:
        try:
            text = file_path.read_text(encoding="utf-8", errors="ignore").lower()
        except Exception:
            continue
        for value, probe in lowered:
            if pending.get(value):
                continue
            if probe in text:
                pending[value] = True
        if all(pending.values()):
            break
    return pending


def report_stage(layout: Layout, repo_root: Path, *, top_endpoints: int = 40) -> StageSummary:
    """Roll the journals into a corpus report and append evidence-ledger rows."""
    configs = layout.read("configs")
    refs = layout.read("refs")
    artifacts = layout.read("artifacts")
    inventory = layout.read("inventory")
    scans = layout.read("scan")

    uniq_inv = layout.latest("inventory", "sha256")
    uniq_scan = layout.latest("scan", "sha256")
    # Only artifacts whose bytes we actually kept are corpus members; refused downloads
    # still carry a sha256 row, which must not inflate the size of the corpus.
    uniq_art = {row["sha256"]: row for row in layout.latest("artifacts", "sha256").values()
                if row.get("sha256") and layout.has_artifact(row["sha256"])}

    corpus = {
        "generated_at": _now(),
        "configs_total": len(configs),
        "configs_ok": sum(1 for c in configs if c.get("ok")),
        "configs_fetch_failed": sum(1 for c in configs if not c.get("ok") and c.get("error") and "payload" not in str(c.get("error", ""))),
        "configs_decode_failed": sum(1 for c in configs if "payload" in str(c.get("error", ""))),
        "refs_total": len(refs),
        "refs_skipped": sum(1 for r in refs if r.get("skip_reason")),
        "artifact_rows": len(artifacts),
        "artifacts_unique": len(uniq_art),
        "artifacts_inventoried": len(uniq_inv),
        "artifacts_scanned": len(uniq_scan),
        "containers": _tally(i.get("container", "?") for i in uniq_inv.values()),
        "loader_shapes": _tally(i.get("loader_shape", "?") for i in uniq_inv.values()),
    }

    # Family coverage across the corpus.
    table = providers.families_by_id()
    coverage: Dict[str, Dict[str, Any]] = {}
    for family in table.values():
        coverage[family.id] = {
            "label": family.label, "opendrive_provider": family.opendrive_provider,
            "opendrive_kind": family.opendrive_kind, "coverage": family.coverage,
            "artifacts": [], "confidence": {}, "evidence": {},
        }
    for sha, scan in uniq_scan.items():
        for hit in scan.get("families", []) or []:
            bucket = coverage.get(hit["id"])
            if bucket is None:
                continue
            bucket["artifacts"].append({"sha256": sha, "source_url": scan.get("source_url", ""),
                                        "site_name": scan.get("site_name", ""),
                                        "confidence": hit["confidence"]})
            bucket["confidence"][hit["confidence"]] = bucket["confidence"].get(hit["confidence"], 0) + 1
            for key in ("class_hits", "host_hits", "endpoint_hits"):
                if hit.get(key):
                    bucket["evidence"].setdefault(key, []).extend(
                        f"{sha[:12]}:{value[:120]}" for value in hit[key][:3])

    # Endpoint leads: aggregate across artifacts, then ask whether our own sources know them.
    merged: Dict[str, Dict[str, Any]] = {}
    for sha, scan in uniq_scan.items():
        for candidate, occurrences in (scan.get("endpoint_candidates") or {}).items():
            row = merged.setdefault(candidate, {"occurrences": 0, "artifacts": []})
            row["occurrences"] += occurrences
            row["artifacts"].append(sha[:12])
    ranked = sorted(merged.items(), key=lambda kv: (-len(kv[1]["artifacts"]), kv[0]))[:top_endpoints]
    presence = _repo_has_literal(repo_root, [candidate for candidate, _ in ranked])
    endpoint_leads = [
        {"endpoint": candidate, "artifacts": sorted(set(info["artifacts"])),
         "occurrences": info["occurrences"], "present_in_our_sources": presence.get(candidate, False)}
        for candidate, info in ranked
    ]

    corpus["family_coverage"] = coverage
    corpus["endpoint_leads"] = endpoint_leads
    layout.write_json("corpus.json", corpus)

    ledger_rows = _ledger_rows(uniq_scan, endpoint_leads)
    added = layout.append_ledger(ledger_rows)
    layout.write_report("corpus.md", _render_markdown(corpus, endpoint_leads, len(added)))

    return StageSummary("report", {
        "configs": corpus["configs_total"], "artifacts_unique": corpus["artifacts_unique"],
        "scanned": corpus["artifacts_scanned"],
        "families_hit": sum(1 for c in coverage.values() if c["artifacts"]),
        "endpoint_leads": len(endpoint_leads), "ledger_rows_added": len(added),
    })


# ---------------------------------------------------------------- improve

def improve_stage(layout: Layout, repo_root: Path, *, top: int = 40) -> StageSummary:
    """Stage 5: extract per-family OpenDrive improvement leads from the scanned corpus.

    This is the stage that turns *extracted content* into something actionable for the
    OpenDrive pipeline. It re-reads the string pools (DEX + bundled assets) of every stored
    artifact — static analysis, no execution — attributes the address-like literals to the
    netdisk families already classified in `scan`, and produces:

    - **coverage gaps**: families the corpus ships but OpenDrive has no built-in provider for,
      each with the exact hosts/endpoints the plugin talks to and whether they already appear
      in our own sources;
    - **contract checks**: for families OpenDrive *does* cover, whether the endpoint signatures
      captured in ``providers.FAMILIES`` still exist in our own sources.

    Everything is an ``unverified`` lead with ``request_order = None``: this names *where to
    look*, never *what to implement*. Provider request order, headers and cookies must still
    come from readable source or a device trace (docs/catvod-opendrive-audit.md, Change rule).
    """
    summary = StageSummary("improve", {"families": 0, "gaps": 0, "contract_checks": 0,
                                        "addresses": 0, "ledger_rows_added": 0})
    scans = layout.latest("scan", "sha256")
    uniq = _unique_artifacts(layout)

    # sha256 -> {address: short-sha} and sha256 -> matched family ids (from scan).
    pools: Dict[str, Dict[str, str]] = {}
    family_artifacts: Dict[str, List[str]] = {}
    for sha, _row in uniq.items():
        scan = scans.get(sha)
        fam_ids = [f["id"] for f in (scan.get("families") or [])] if scan else []
        path = layout.resolve_artifact(sha)
        try:
            report = artifact_mod.inspect(path)
        except Exception:
            continue
        docs = report.get("_docs", [])
        assets = report.get("_asset_docs", [])
        strings = ([v for d in docs for v in d.strings if v]
                   + [v for d in assets for v in d.strings if v])
        pool = providers.mine_addresses(strings, limit=top * 4)
        pools[sha] = {addr: sha[:12] for addr in pool}
        for fid in fam_ids:
            family_artifacts.setdefault(fid, []).append(sha)

    families = providers.families_by_id()

    # One repository scan for every literal we will need to flag present/absent.
    probe_addresses: List[str] = []
    for pool in pools.values():
        probe_addresses.extend(pool.keys())
    for fam in families.values():
        probe_addresses.extend(fam.endpoints)
        probe_addresses.extend(fam.hosts)
    presence = _repo_has_literal(repo_root, probe_addresses)

    gap_leads: List[Dict[str, Any]] = []
    contract_leads: List[Dict[str, Any]] = []
    for fid, fshas in family_artifacts.items():
        fam = families.get(fid)
        if fam is None:
            continue
        if fam.opendrive_provider:
            missing = [e for e in fam.endpoints if not presence.get(e)]
            if missing:
                contract_leads.append({
                    "family_id": fid, "label": fam.label,
                    "opendrive_provider": fam.opendrive_provider,
                    "missing_endpoints": [
                        {"endpoint": e, "present_in_our_sources": presence.get(e, False)}
                        for e in missing
                    ],
                })
            continue
        # Coverage gap: aggregate the addresses this family's artifacts actually talk to.
        agg: Dict[str, set] = {}
        for sha in fshas:
            for addr, short in pools.get(sha, {}).items():
                agg.setdefault(addr, set()).add(short)
        addresses = [
            {"address": a, "artifacts": sorted(v), "present_in_our_sources": presence.get(a, False)}
            for a, v in sorted(agg.items(), key=lambda kv: (-len(kv[1]), kv[0]))[:top]
        ]
        gap_leads.append({
            "family_id": fid, "label": fam.label,
            "opendrive_provider": "", "coverage": fam.coverage,
            "artifacts": sorted({s[:12] for s in fshas}),
            "addresses": addresses,
            "recommended_action": "obtain readable source (version-pinned reference or hand jadx) "
                                   "or a live device fixture before any provider code change",
        })
        summary.counts["addresses"] += len(addresses)

    gap_leads.sort(key=lambda g: (-len(g["artifacts"]), -len(g["addresses"])))
    contract_leads.sort(key=lambda c: c["family_id"])
    summary.counts["families"] = len(family_artifacts)
    summary.counts["gaps"] = len(gap_leads)
    summary.counts["contract_checks"] = len(contract_leads)

    leads = {"generated_at": _now(), "gaps": gap_leads, "contract_checks": contract_leads}
    layout.write_json("opendrive-leads.json", leads)
    added = layout.append_ledger(_improve_ledger_rows(gap_leads, contract_leads))
    layout.write_report("opendrive-leads.md", _render_improve_markdown(leads, len(added)))
    summary.counts["ledger_rows_added"] = len(added)
    return summary


def _improve_ledger_rows(gap_leads: List[Dict[str, Any]],
                         contract_leads: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for lead in gap_leads:
        for item in lead["addresses"]:
            stable = "|".join(("extracted-address", lead["family_id"], item["address"][:160]))
            rows.append({
                "id": hashlib.sha1(stable.encode("utf-8")).hexdigest()[:12],
                "created": _now(),
                "kind": "extracted-address",
                "family": lead["family_id"],
                "family_label": lead["label"],
                "confidence": "unknown",
                "opendrive_provider": "",
                "opendrive_status": "present-in-source" if item["present_in_our_sources"] else "absent-from-source",
                "artifact_sha256": ",".join(item["artifacts"]),
                "source_url": "",
                "observed": item["address"][:160],
                "evidence_source": "string-pool (DEX + bundled assets)",
                "request_order": None,
                "classification": "unverified",
                "notes": "extracted from plugin string pool; no execution; requires readable source "
                         "or device fixture before any provider code change",
            })
    for lead in contract_leads:
        for item in lead["missing_endpoints"]:
            stable = "|".join(("contract-check", lead["family_id"], item["endpoint"][:160]))
            rows.append({
                "id": hashlib.sha1(stable.encode("utf-8")).hexdigest()[:12],
                "created": _now(),
                "kind": "contract-check",
                "family": lead["family_id"],
                "family_label": lead["label"],
                "confidence": "unknown",
                "opendrive_provider": lead["opendrive_provider"],
                "opendrive_status": "present-in-source" if item["present_in_our_sources"] else "absent-from-source",
                "artifact_sha256": "",
                "source_url": "",
                "observed": item["endpoint"][:160],
                "evidence_source": "family-signature vs our sources",
                "request_order": None,
                "classification": "unverified",
                "notes": "captured family endpoint signature not found in our sources; a question to "
                         "answer, not proof of drift or a missing capability",
            })
    return rows


def _render_improve_markdown(leads: Dict[str, Any], ledger_added: int) -> str:
    lines = [
        "# OpenDrive improvement leads (extracted from plugin corpus)",
        "",
        f"Generated: {leads['generated_at']}",
        "",
        "Static extraction only — no plugin code was executed. Every row is `unverified`",
        "until readable source or a device trace supports it (see `docs/catvod-opendrive-audit.md`,",
        "Change rule). This document names *where to look*, never *what to implement*.",
        "",
        f"Coverage gaps: {len(leads['gaps'])} family/families. "
        f"Contract checks: {len(leads['contract_checks'])} family/families.",
        "",
        "## Coverage gaps (families with no built-in OpenDrive provider)",
        "",
        "Prioritised by how many corpus artifacts and distinct addresses point at the family.",
        "",
    ]
    if not leads["gaps"]:
        lines.append("_No uncovered family was classified in the corpus._")
        lines.append("")
    for lead in leads["gaps"]:
        lines.extend([
            f"### {lead['label']}  ({len(lead['artifacts'])} artifact(s))",
            "",
            f"- OpenDrive status: {lead['coverage']}",
            "- Observed addresses (string-pool extraction; ✓ present in our sources / ✗ absent):",
            "",
            "| Address | Artifacts | In our sources |",
            "| --- | --- | --- |",
        ])
        for item in lead["addresses"]:
            lines.append(f"| `{item['address'][:120]}` | {len(item['artifacts'])} | "
                         f"{'✓' if item['present_in_our_sources'] else '✗'} |")
        lines.extend([
            "",
            f"- Recommended action: {lead['recommended_action']}",
            "",
        ])
    lines.extend([
        "## Contract checks (families with a built-in OpenDrive provider)",
        "",
        "Verifies the captured family endpoint signatures still exist in our own sources. A `✗`",
        "means the literal was not found in `app/src/main` or `libraries/**/src/main` and is a",
        "question to answer, not proof of drift.",
        "",
    ])
    if not leads["contract_checks"]:
        lines.append("_No covered family had a captured endpoint signature absent from our sources._")
        lines.append("")
    for lead in leads["contract_checks"]:
        lines.extend([
            f"### {lead['label']}  (`{lead['opendrive_provider']}`)",
            "",
            "| Endpoint | In our sources |",
            "| --- | --- |",
        ])
        for item in lead["missing_endpoints"]:
            lines.append(f"| `{item['endpoint'][:120]}` | "
                         f"{'✓' if item['present_in_our_sources'] else '✗'} |")
        lines.append("")
    lines.extend([
        "## What this is not",
        "",
        "No request order, auth header or cookie is inferred here. The pinned-evidence docs",
        "(`docs/provider-protocol-evidence.md`) record those from readable source or device traces;",
        "this extraction only supplies candidate targets.",
        "",
        f"Evidence ledger rows added this run: {ledger_added} "
        "(`report/evidence-ledger.jsonl`, append-only).",
        "",
    ])
    return "\n".join(lines)


def _ledger_rows(scans: Dict[str, Dict[str, Any]], endpoint_leads: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Build append-only findings.

    Every row is created as `unverified`. Following the change rule in
    `docs/catvod-opendrive-audit.md`, no heuristic here may promote a row to fact.
    """
    rows: List[Dict[str, Any]] = []
    for sha, scan in scans.items():
        for hit in scan.get("families", []) or []:
            for key, kind, source in (("class_hits", "class-name", "class-table"),
                                      ("host_hits", "family-host", "string-pool"),
                                      ("endpoint_hits", "family-endpoint", "string-pool")):
                for value in hit.get(key, []) or []:
                    stable = "|".join((kind, hit["id"], sha, value[:160]))
                    rows.append({
                        "id": hashlib.sha1(stable.encode("utf-8")).hexdigest()[:12],
                        "created": _now(),
                        "kind": kind,
                        "family": hit["id"],
                        "family_label": hit["label"],
                        "confidence": hit["confidence"],
                        "opendrive_provider": hit["opendrive_provider"],
                        "opendrive_status": hit["coverage"],
                        "artifact_sha256": sha,
                        "source_url": scan.get("source_url", ""),
                        "observed": value[:160],
                        "evidence_source": source,
                        "request_order": None,
                        "classification": "unverified",
                        "notes": "static evidence only; requires readable source or a device trace before it may change provider code",
                    })
    for lead in endpoint_leads:
        stable = "|".join(("endpoint-gap", lead["endpoint"], ",".join(lead["artifacts"])))
        rows.append({
            "id": hashlib.sha1(stable.encode("utf-8")).hexdigest()[:12],
            "created": _now(),
            "kind": "endpoint-lead",
            "family": "",
            "family_label": "",
            "confidence": "unknown",
            "opendrive_provider": "",
            "opendrive_status": "present-in-source" if lead["present_in_our_sources"] else "absent-from-source",
            "artifact_sha256": "",
            "source_url": "",
            "observed": lead["endpoint"],
            "evidence_source": "string-pool aggregate",
            "request_order": None,
            "classification": "unverified",
            "notes": f"seen in {len(lead['artifacts'])} artifact(s); absence from our sources is a lead, not proof of a missing capability",
        })
    return rows


def _tally(values) -> Dict[str, int]:
    out: Dict[str, int] = {}
    for value in values:
        out[value] = out.get(value, 0) + 1
    return dict(sorted(out.items(), key=lambda kv: (-kv[1], kv[0])))


def _render_markdown(corpus: Dict[str, Any], endpoint_leads: List[Dict[str, Any]], ledger_added: int) -> str:
    lines = [
        "# CatVod plugin corpus digest",
        "",
        f"Generated: {corpus['generated_at']}",
        "",
        "Static evidence only. No plugin code was executed. Every finding is `unverified`",
        "until readable source or a device trace supports it (see `docs/catvod-opendrive-audit.md`).",
        "",
        "## Corpus",
        "",
        f"- depot configs fetched: {corpus['configs_total']} (ok {corpus['configs_ok']}, "
        f"network failures {corpus['configs_fetch_failed']}, decode failures {corpus['configs_decode_failed']})",
        f"- plugin references recorded: {corpus['refs_total']} (skipped/not fetchable {corpus['refs_skipped']})",
        f"- unique artifacts: {corpus['artifacts_unique']} "
        f"(inventoried {corpus['artifacts_inventoried']}, scanned {corpus['artifacts_scanned']})",
        f"- container magic: {_inline_counts(corpus['containers'])}",
        f"- loader shapes: {_inline_counts(corpus['loader_shapes'])}",
        "",
        "## Netdisk family coverage",
        "",
        "| Family | Evidence in corpus | Confidence | OpenDrive provider | Status |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in corpus["family_coverage"].values():
        if row["artifacts"]:
            confidence = ", ".join(f"{k}×{v}" for k, v in sorted(row["confidence"].items()))
        else:
            confidence = "-"
        lines.append(
            f"| {row['label']} | {len(row['artifacts'])} artifact(s) | {confidence} | "
            f"{row['opendrive_provider'] or '—'} | {row['coverage']} |"
        )

    lines.extend([
        "",
        "Uncovered families hit by the corpus are the direct OpenDrive improvement leads:",
        "each needs a readable source or a public share fixture before any code changes.",
        "",
        "## Endpoint leads",
        "",
        "Aggregated across artifacts. `absent` means the literal was not found in",
        "`app/src/main` or `libraries/**/src/main`; it is a question to answer,",
        "not a missing feature.",
        "",
        "| Endpoint / host | Artifacts | In our sources |",
        "| --- | --- | --- |",
    ])
    for lead in endpoint_leads:
        lines.append(f"| `{lead['endpoint'][:120]}` | {len(lead['artifacts'])} | "
                     f"{'yes' if lead['present_in_our_sources'] else 'absent'} |")

    lines.extend([
        "",
        f"Evidence ledger rows added this run: {ledger_added} "
        "(`report/evidence-ledger.jsonl`, append-only).",
        "",
        "## Suggested next step",
        "",
        "Pick the highest-confidence family with no built-in provider, open the artifact",
        "with an external decompiler by hand, and only then write a protocol record into",
        "`docs/provider-protocol-evidence.md` with commit or artifact hash, file, method and",
        "exact request order.",
        "",
    ])
    return "\n".join(lines)


def _inline_counts(values: Dict[str, int]) -> str:
    return ", ".join(f"{key}={value}" for key, value in values.items()) or "—"


def _map_limited(func, entries: List[Any], workers: int) -> None:
    """Run `func` over entries, bounded in parallel when useful."""
    if workers <= 1:
        for entry in entries:
            func(entry)
        return
    from concurrent.futures import ThreadPoolExecutor

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(func, entries))
