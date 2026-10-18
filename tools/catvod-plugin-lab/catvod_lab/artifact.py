"""Artifact inspection: container shape, DEX discovery and embedded-content carving.

The on-disk extension tells us nothing. A CatVod plugin has been observed as a plain jar,
a zip, an apk, an extension-less stream and, most importantly for this lab, a **`.so`**.
Those arrive in at least three shapes:

- a ZIP renamed to `.so` (magic still starts with ``PK``);
- an ELF whose payload embeds one or more DEX images or a nested ZIP;
- a DEX renamed to `.so`.

Everything is therefore decided from magic bytes, and any file that is not already parsed
as a container is additionally **carved**: scanned for embedded ZIP and DEX signatures,
because the interesting payload of a guarded `.so` almost always sits past the ELF header.
"""

from __future__ import annotations

import collections
import hashlib
import io
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from . import dex

ZIP_MAGIC = b"PK\x03\x04"
ELF_MAGIC = b"\x7fELF"
GZIP_MAGIC = b"\x1f\x8b"
DEX_ENTRY = re.compile(r"(^|/)(classes\d*\.dex)$")
SO_ENTRY = re.compile(r"^lib/[^/]+/lib[^/]+\.so$")

# Carving budgets. A guarded artifact may contain many false-positive "PK" hits; we stop
# early rather than spend unbounded time or memory on one hostile file.
CARVE_LIMIT = 24
CARVE_READ_CAP = 256 * 1024 * 1024
DEX_READ_CAP = 128 * 1024 * 1024

# Asset budgets: bundles may carry hundreds of payloads and some are hundreds of MB.
MAX_ASSETS = 64
MAX_ASSET_READ = 4 * 1024 * 1024
MAX_ASSET_STRINGS = 4000

_ELF_MACHINES = {
    3: "x86",
    8: "mips",
    40: "arm",
    62: "x86_64",
    183: "aarch64",
}


@dataclass
class ElfInfo:
    """Fields read from the ELF header only — no section walking, no mapping."""

    bits: int = 0
    endian: str = ""
    machine: str = ""
    etype: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return {"bits": self.bits, "endian": self.endian, "machine": self.machine, "etype": self.etype}


@dataclass
class AssetDoc:
    """One non-code payload bundled with the plugin, usually under `assets/`.

    Recent bundles keep their netdisk logic in JavaScript or in an encrypted blob
    unpacked at runtime, so a dex-only view silently misses most of the protocol.
    Readable assets are mined like a string pool; unreadable ones are still recorded,
    because "this family ships its API client encrypted" is itself evidence.
    """

    source: str
    size: int = 0
    readable: bool = False
    print_ratio: float = 0.0
    encrypted: bool = False
    strings: List[str] = field(default_factory=list)


@dataclass
class DexDoc:
    """One DEX image located inside an artifact."""

    source: str
    version: str = ""
    class_count: int = 0
    string_count: int = 0
    classes: List[str] = field(default_factory=list)
    strings: List[str] = field(default_factory=list)
    error: str = ""

    @property
    def catvod_classes(self) -> List[str]:
        return [c for c in self.classes if c.startswith("com.github.catvod.")]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def detect_container(head: bytes) -> str:
    """Classify leading bytes into a container family."""
    if head.startswith(ZIP_MAGIC):
        return "zip"
    if head.startswith(ELF_MAGIC):
        return "elf"
    if dex.looks_like_dex(head):
        return "dex"
    if head.startswith(GZIP_MAGIC):
        return "gzip"
    try:
        lowered = head[:64].lstrip().decode("utf-8").lower()
    except UnicodeDecodeError:
        return "unknown"
    if lowered.startswith(("<html", "<!doctype html", "<?xml")):
        return "text"
    return "unknown"


TEXT_MAGICS = (b"<!doctype", b"<html", b"<?xml", b"<!--", b"{", b"[", b"<!entity")
MIN_ARTIFACT_BYTES = 512


def accepts_payload(body: bytes) -> str:
    """Decide whether downloaded bytes may join the corpus.

    Returns "" when acceptable, otherwise the reason. This is the second gate after
    `resolve.refusal_reason`: hosts answer 200 with an HTML error page or with another
    configuration far more often than they answer with a plugin, and storing those makes
    the corpus unusable.

    Plugin containers (zip/dex/elf/gzip) always pass. Anything else passes only when it is
    neither text nor tiny, because a guarded or encrypted launcher is legitimately opaque
    to every known magic.
    """
    if not body:
        return "empty body"
    head = body[:4096]
    kind = detect_container(head)
    if kind in ("zip", "dex", "elf", "gzip"):
        return ""
    probe = head.lstrip().lower()
    if any(probe.startswith(magic) for magic in TEXT_MAGICS):
        return f"text/error page instead of an artifact ({probe[:32].decode('utf-8', 'ignore')!r})"
    try:
        head[:512].decode("utf-8")
    except UnicodeDecodeError:
        pass
    else:
        return "text payload instead of an artifact"
    if len(body) < MIN_ARTIFACT_BYTES:
        return f"smaller than any real plugin ({len(body)} bytes)"
    return ""


def elf_info(head: bytes) -> ElfInfo:
    """Parse the ELF identification plus type/machine fields."""
    info = ElfInfo()
    if len(head) < 20 or not head.startswith(ELF_MAGIC):
        return info
    info.bits = {1: 32, 2: 64}.get(head[4], 0)
    info.endian = {1: "lsb", 2: "msb"}.get(head[5], "")
    endian_char = "<" if info.endian == "lsb" else ">"
    try:
        import struct

        etype, machine = struct.unpack_from(endian_char + "HH", head, 16)
    except Exception:
        return info
    info.etype = {0: "none", 1: "rel", 2: "exec", 3: "dyn"}.get(etype, str(etype))
    info.machine = _ELF_MACHINES.get(machine, str(machine))
    return info


def _dex_documents_from_zip(path: Path, names: List[str]) -> List[DexDoc]:
    docs: List[DexDoc] = []
    with zipfile.ZipFile(path) as archive:
        for name in names:
            try:
                info = archive.getinfo(name)
            except KeyError:
                continue
            if info.file_size > DEX_READ_CAP:
                docs.append(DexDoc(source=f"zip:{name}", error=f"too large: {info.file_size} bytes"))
                continue
            body = archive.read(name)
            parsed = dex.parse(body, source=f"zip:{name}")
            docs.append(DexDoc(
                source=f"zip:{name}",
                version=parsed.version,
                class_count=len(parsed.classes),
                string_count=len(parsed.strings),
                classes=parsed.classes,
                strings=parsed.strings,
                error=parsed.error,
            ))
    return docs


def printable_ratio(sample: bytes) -> float:
    """Share of bytes that are ordinary text; near zero means encrypted or compressed."""
    if not sample:
        return 0.0
    text = sum(1 for byte in sample if 32 <= byte < 127 or byte in (9, 10, 13))
    return round(text / len(sample), 3)


def text_strings(text: str, limit: int = MAX_ASSET_STRINGS) -> List[str]:
    """Split a readable asset into the tokens a string pool would have given us."""
    found: List[str] = []
    for line in text.splitlines():
        token = line.strip()
        if not token or any(ch.isspace() for ch in token) and len(token) > 200:
            continue
        found.append(token[:400])
        if len(found) >= limit:
            break
    return found


def assets_from_zip(path: Path, names: List[str]) -> List[AssetDoc]:
    """Read bundled payloads (typically under `assets/`) that are not DEX or native libs."""
    docs: List[AssetDoc] = []
    candidates = [n for n in names
                  if not DEX_ENTRY.search(n) and not SO_ENTRY.match(n) and not n.startswith("META-INF/")]
    with zipfile.ZipFile(path) as archive:
        for name in candidates[:MAX_ASSETS]:
            try:
                info = archive.getinfo(name)
                size = info.file_size
                if size > MAX_ASSET_READ:
                    docs.append(AssetDoc(source=name, size=size, encrypted=True))
                    continue
                body = archive.read(name)
            except Exception:
                continue
            ratio = printable_ratio(body[:65536])
            readable = ratio > 0.85
            doc = AssetDoc(source=name, size=size, readable=readable, print_ratio=ratio,
                           encrypted=not readable)
            if readable:
                doc.strings = text_strings(body.decode("utf-8", "replace"))
            docs.append(doc)
    return docs


def _carve(data: bytes, already_parsed: bool) -> Tuple[List[Dict[str, Any]], List[DexDoc]]:
    """Locate embedded ZIP archives and DEX images anywhere in the bytes."""
    embedded: List[Dict[str, Any]] = []
    docs: List[DexDoc] = []

    positions: List[int] = []
    pos = data.find(ZIP_MAGIC)
    while pos >= 0 and len(positions) < CARVE_LIMIT:
        positions.append(pos)
        pos = data.find(ZIP_MAGIC, pos + 1)

    for offset in positions:
        if offset == 0 and already_parsed:
            continue
        buffer = io.BytesIO(data[offset:])
        try:
            with zipfile.ZipFile(buffer) as archive:
                names = archive.namelist()
                dex_names = [n for n in names if DEX_ENTRY.search(n)]
                embedded.append({
                    "offset": offset,
                    "kind": "zip",
                    "entries": len(names),
                    "dex_entries": len(dex_names),
                    "native_libs": sum(1 for n in names if SO_ENTRY.match(n)),
                    "has_manifest": "AndroidManifest.xml" in names,
                    "sample_entries": sorted(names)[:12],
                })
                for name in dex_names[:8]:
                    try:
                        body = archive.read(name)
                    except Exception:
                        continue
                    parsed = dex.parse(body, source=f"carved+{offset}:{name}")
                    docs.append(DexDoc(
                        source=f"carved+{offset}:{name}",
                        version=parsed.version,
                        class_count=len(parsed.classes),
                        string_count=len(parsed.strings),
                        classes=parsed.classes,
                        strings=parsed.strings,
                        error=parsed.error,
                    ))
        except Exception:
            continue

    found = 0
    probe = data.find(dex.DEX_MAGIC)
    while probe >= 0 and found < CARVE_LIMIT:
        declared = dex.file_size_at(data, probe)
        if declared and 0 < declared <= len(data) - probe:
            image = data[probe:probe + declared]
            parsed = dex.parse(image, source=f"dex@{probe}")
            if parsed.classes or not parsed.error:
                docs.append(DexDoc(
                    source=f"dex@{probe}",
                    version=parsed.version,
                    class_count=len(parsed.classes),
                    string_count=len(parsed.strings),
                    classes=parsed.classes,
                    strings=parsed.strings,
                    error=parsed.error,
                ))
                found += 1
        probe = data.find(dex.DEX_MAGIC, probe + 1)
    return embedded, docs


def inspect(path: Path, carve: bool = True) -> Dict[str, Any]:
    """Inspect one artifact file and return its structural report."""
    path = Path(path)
    size = path.stat().st_size
    with open(path, "rb") as handle:
        head = handle.read(4096)

    report: Dict[str, Any] = {
        "sha256": sha256_of(path),
        "size_bytes": size,
        "head_hex": head[:16].hex(),
        "container": detect_container(head),
        "elf": None,
        "zip": None,
        "dex": [],
        "embedded": [],
        "assets": {},
        "notes": [],
    }

    if report["container"] == "elf":
        report["elf"] = elf_info(head).as_dict()

    zip_names: List[str] = []
    if report["container"] == "zip":
        try:
            with zipfile.ZipFile(path) as archive:
                zip_names = archive.namelist()
                report["zip"] = {
                    "entries": len(zip_names),
                    "dex_entries": sum(1 for n in zip_names if DEX_ENTRY.search(n)),
                    "native_libs": sum(1 for n in zip_names if SO_ENTRY.match(n)),
                    "has_manifest": "AndroidManifest.xml" in zip_names,
                    "has_resources": "resources.arsc" in zip_names,
                    "top_level": sorted({n.split("/")[0] for n in zip_names})[:16],
                }
        except zipfile.BadZipFile as error:
            report["notes"].append(f"bad zip: {error}")
            report["container"] = "broken-zip"

    asset_docs: List[AssetDoc] = []
    if zip_names:
        asset_docs = assets_from_zip(path, zip_names)
        readable = [a for a in asset_docs if a.readable]
        report["assets"] = {
            "count": len(asset_docs),
            "assets_dir": sum(1 for a in asset_docs if a.source.startswith("assets/")),
            "readable": len(readable),
            "encrypted_or_binary": sum(1 for a in asset_docs if a.encrypted),
            "kinds": dict(sorted(collections.Counter(
                ("." + a.source.rsplit(".", 1)[-1].lower()) if "." in a.source.rsplit("/", 1)[-1] else "(none)"
                for a in asset_docs).items(), key=lambda kv: (-kv[1], kv[0]))[:8]),
            "sample": [a.source for a in asset_docs[:12]],
        }

    docs: List[DexDoc] = []
    if report["container"] == "dex" and size <= DEX_READ_CAP:
        body = path.read_bytes()
        parsed = dex.parse(body, source="file")
        docs.append(DexDoc(source="file", version=parsed.version, class_count=len(parsed.classes),
                           string_count=len(parsed.strings), classes=parsed.classes,
                           strings=parsed.strings, error=parsed.error))
    elif zip_names and size <= DEX_READ_CAP:
        dex_names = [n for n in zip_names if DEX_ENTRY.search(n)]
        if dex_names:
            docs.extend(_dex_documents_from_zip(path, dex_names))

    if carve and not docs and size <= CARVE_READ_CAP:
        report["notes"].append("no dex in the outer container; carved for embedded dex/zip")
        embedded, carved = _carve(path.read_bytes(), already_parsed=report["container"] == "zip")
        report["embedded"] = embedded
        docs.extend(carved)
    elif carve and size > CARVE_READ_CAP:
        report["notes"].append(f"skipped carving: artifact larger than {CARVE_READ_CAP} bytes")

    sources = [d.source for d in docs]
    if any(source.startswith("zip:") for source in sources):
        loader_shape = "zip-with-dex"
    elif report["container"] == "dex":
        loader_shape = "raw-dex"
    elif sources:
        # Anything else came from carving, whether we found the DEX directly or inside a
        # nested archive: either way the plugin payload hides behind a non-JVM container.
        loader_shape = "embedded-dex"
    else:
        loader_shape = "none"
    report["loader_shape"] = loader_shape
    report["dex"] = [
        {
            "source": d.source,
            "version": d.version,
            "class_count": d.class_count,
            "string_count": d.string_count,
            "catvod_class_count": len(d.catvod_classes),
            "catvod_classes": sorted(d.catvod_classes)[:200],
            "error": d.error,
        }
        for d in docs
    ]
    report["_docs"] = docs  # stripped before persistence
    report["_asset_docs"] = asset_docs
    return report
