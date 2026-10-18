"""Offline integration test for the full abstraction chain.

Proves the part the unit tests do not cover: that a stored ``.so`` plugin, once
downloaded by the (network) `fetch` stage, is *abstracted* by `inventory` -> `scan` ->
`report` into the OpenDrive family-coverage corpus and an append-only evidence ledger.

Everything here is synthetic and runs on the standard library alone, so it is a
deterministic stand-in for a real `fetch` run and never downloads or executes anything.
"""

import hashlib
import io
import zipfile

from catvod_lab import depot, pipeline, providers, resolve
from catvod_lab.store import Layout
from dexfactory import build_dex


def _fake_elf(payload: bytes) -> bytes:
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2  # ELFCLASS64
    header[5] = 1  # ELFDATA2LSB
    import struct

    struct.pack_into("<HH", header, 16, 3, 183)  # ET_DYN, EM_AARCH64
    return bytes(header) + payload


def _zip_with_dex(*classes, strings=()) -> bytes:
    image = build_dex(list(strings), list(classes))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("classes.dex", image)
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    return buffer.getvalue()


def _store(layout: Layout, body: bytes, url: str, site_name: str) -> str:
    sha = hashlib.sha256(body).hexdigest()
    layout.save_artifact(body, sha, "so")
    layout.append("artifacts", {
        "fetched_at": "2026-01-01T00:00:00+00:00",
        "ok": True,
        "sha256": sha,
        "url": url,
        "source_url": url,
        "site_name": site_name,
        "size": len(body),
        "status": 200,
        "content_type": "application/octet-stream",
        "via": "direct",
    })
    return sha


def test_depot_loads_vod_and_live_sections():
    # Load the real bundled depot to validate the seed list shape.
    from pathlib import Path

    path = Path(__file__).resolve().parents[3] / "app" / "src" / "main" / "assets" / "depot.json"
    rows = depot.load_entries(path)
    assert rows
    assert all(e.url.startswith(("http://", "https://", "assets://")) for e in rows)
    # No duplicate URLs survive collapsing.
    assert len({e.url for e in rows}) == len(rows)


def test_resolve_refuses_documents_but_accepts_plugin_extensions():
    # The pre-filter refuses by extension: .json/.py/.txt point at nested configs or
    # Python spiders, not binaries. A plausible plugin extension (.jar/.so) is fetchable;
    # whether the bytes are really an artifact is decided later by `artifact.accepts_payload`.
    json_ref = resolve.resolve_reference("https://host/x/spider.json", "https://host/c.json")
    assert json_ref.fetchable is False
    assert "document" in json_ref.skip_reason

    py_ref = resolve.resolve_reference("https://host/x/run.py", "https://host/c.json")
    assert py_ref.fetchable is False

    assets_ref = resolve.resolve_reference("assets://spider/custom_spider.jar", "https://host/c.json")
    assert assets_ref.fetchable is False
    assert "app-local" in assets_ref.skip_reason

    # A GitHub *blob* URL ends in .jar, so it passes the pre-filter; the HTML body it serves
    # is rejected downstream by `artifact.accepts_payload` (covered by test_artifact).
    jar_ref = resolve.resolve_reference(
        "https://ghproxy.com/https://github.com/x/opp/main/xxx.jar",
        "https://example.com/config.json",
    )
    assert jar_ref.fetchable is True
    assert jar_ref.skip_reason == ""


def test_resolve_page_relative_jar_against_final_url():
    # Relative references resolve against the *directory* of the final config URL.
    resolved = resolve.resolve_reference("./jar/pg.jar", "https://host.example/configs/main.json")
    assert resolved.fetchable
    assert resolved.url == "https://host.example/configs/jar/pg.jar"


def test_chain_abstracts_so_plugins_into_opendrive_family_coverage(tmp_path):
    layout = Layout.create(tmp_path)

    # Three guarded shapes, each a different netdisk family the OpenDrive pipeline cares about.
    quark = _zip_with_dex("com.github.catvod.spider.Quark", strings=("https://pan.quark.cn/s/x",))
    alipan = build_dex(["https://www.alipan.com/share"], ["com.github.catvod.spider.AliYun"]) + b"\x00" * 512
    uc = _fake_elf(b"\x00" * 4096 + build_dex(["https://drive.uc.cn/file/a"], ["com.github.catvod.spider.UCApi"]))

    _store(layout, quark, "https://host/q/libquark.so", "夸克")
    _store(layout, alipan, "https://host/a/classes.dex", "阿里云盘")
    _store(layout, uc, "https://host/u/libuc.so", "UC")

    inv = pipeline.inventory_stage(layout)
    assert inv.counts["artifacts"] == 3
    assert inv.counts["parsed"] == 3

    scn = pipeline.scan_stage(layout)
    assert scn.counts["artifacts"] == 3
    assert scn.counts["cloud_related"] == 3

    # Every artifact must be attributed to a known family with at least medium confidence,
    # because class-name evidence plus a host/endpoint hit is the high-confidence rule.
    scans = layout.read("scan")
    hits_by_sha: dict[str, list] = {}
    for row in scans:
        hits_by_sha.setdefault(row["sha256"], []).extend(f["id"] for f in row.get("families", []))
    families = {f for hits in hits_by_sha.values() for f in hits}
    assert {"quark", "aliyun", "uc"}.issubset(families)

    rep = pipeline.report_stage(layout, layout.root.parents[3])
    assert rep.counts["artifacts_unique"] == 3
    assert rep.counts["scanned"] == 3
    assert rep.counts["families_hit"] >= 3

    # The digest and the append-only ledger must exist and carry unverified findings only.
    assert (layout.root / "report" / "corpus.json").is_file()
    assert (layout.root / "report" / "corpus.md").is_file()
    ledger = list(layout.read_ledger())
    assert ledger
    assert all(row["classification"] == "unverified" for row in ledger)
    # No finding may promote itself to a fact.
    assert all(row["request_order"] is None for row in ledger)


def test_chain_does_not_attribute_a_non_cloud_plugin(tmp_path):
    layout = Layout.create(tmp_path)
    body = _zip_with_dex("com.example.weather.LocalForecast", strings=("https://api.weatherapi.com/v1",))
    _store(layout, body, "https://host/w/lib.so", "Weather")
    pipeline.inventory_stage(layout)
    scn = pipeline.scan_stage(layout)
    assert scn.counts["cloud_related"] == 0
    scans = layout.read("scan")
    assert scans and not scans[0].get("families")


def test_report_ranks_absent_endpoints_as_leads_not_missing_features(tmp_path):
    layout = Layout.create(tmp_path)
    # A provider whose endpoint is brand-new to our sources: the report must surface it as a
    # lead ("absent-from-source") and never claim OpenDrive "is missing X".
    body = _zip_with_dex("com.github.catvod.spider.Foo", strings=("https://cdn.novel-drive.example/open/file/get",))
    _store(layout, body, "https://host/f/lib.so", "Foo")
    pipeline.inventory_stage(layout)
    pipeline.scan_stage(layout)
    rep = pipeline.report_stage(layout, layout.root.parents[3])
    assert rep.counts["endpoint_leads"] >= 1
    md = (layout.root / "report" / "corpus.md").read_text(encoding="utf-8")
    assert "absent" in md or "present" in md


def test_improve_extracts_family_content_into_gap_leads(tmp_path):
    layout = Layout.create(tmp_path)
    repo_root = layout.root.parents[3]
    # A gap family (PikPak, no built-in OpenDrive provider) with a real address pool.
    pikpak = _zip_with_dex(
        "com.github.catvod.spider.PikPak",
        strings=(
            "https://api-drive.mypikpak.com/drive/v1/files/",
            "/v1/auth/token",
            "https://mypikpak.com/",
        ),
    )
    _store(layout, pikpak, "https://host/p/libpikpak.so", "PikPak")
    pipeline.inventory_stage(layout)
    pipeline.scan_stage(layout)
    pipeline.report_stage(layout, repo_root)
    imp = pipeline.improve_stage(layout, repo_root, top=20)

    assert imp.counts["families"] == 1
    assert imp.counts["gaps"] == 1
    assert imp.counts["contract_checks"] == 0

    md = (layout.root / "report" / "opendrive-leads.md").read_text(encoding="utf-8")
    assert "PikPak" in md
    assert "api-drive.mypikpak.com" in md
    # A gap lead must say what it is NOT: a recommendation, not an implementation.
    assert "Recommended action" in md
    assert "no plugin code was executed" in md.lower()

    # The extracted addresses must land in the append-only ledger as unverified leads.
    ledger = list(layout.read_ledger())
    gap_rows = [r for r in ledger if r["kind"] == "extracted-address" and r["family"] == "pikpak"]
    assert gap_rows
    assert all(r["classification"] == "unverified" for r in gap_rows)
    assert all(r["request_order"] is None for r in gap_rows)
    # The observed address is attributed, not guessed.
    assert any("api-drive.mypikpak.com" in r["observed"] for r in gap_rows)


def test_improve_contract_checks_covered_family_endpoints_against_sources(tmp_path):
    import json

    layout = Layout.create(tmp_path)
    repo_root = layout.root.parents[3]
    # A covered family (Quark) whose declared signature is checked against our own sources.
    quark = _zip_with_dex(
        "com.github.catvod.spider.Quark",
        strings=("https://pan.quark.cn/s/x", "/1/clouddrive/file/download"),
    )
    _store(layout, quark, "https://host/q/libquark.so", "Quark")
    pipeline.inventory_stage(layout)
    pipeline.scan_stage(layout)
    imp = pipeline.improve_stage(layout, repo_root, top=20)

    # A covered family is never a *coverage gap*: it has a built-in OpenDrive provider.
    assert imp.counts["gaps"] == 0

    leads = json.loads((layout.root / "report" / "opendrive-leads.json").read_text(encoding="utf-8"))
    assert leads["gaps"] == []
    # Quark's declared endpoint signature is absent from our sources here, so the contract
    # check correctly raises it as a question to answer (not proof of drift).
    quark_check = next((c for c in leads["contract_checks"] if c["family_id"] == "quark"), None)
    assert quark_check is not None
    assert quark_check["missing_endpoints"]

    md = (layout.root / "report" / "opendrive-leads.md").read_text(encoding="utf-8")
    assert "Contract checks" in md
    # The absent endpoint is rendered with the ✗ marker in the contract-check table.
    assert "\u2717" in md
