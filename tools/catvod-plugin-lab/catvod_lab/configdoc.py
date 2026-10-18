"""Configuration payload decoding and document parsing.

These rules are copied field-for-field from production so the harvested corpus is
exactly what the app itself would download and load:

- BOM strip and the `{8 base64}**` envelope:
  ``libraries/source-core/src/main/kotlin/com/fongmi/tv/core/source/TvBoxConfigPayloadDecoder.kt``
- `spider` field and per-site `jar`/`ext` fallback:
  ``libraries/source-core/src/main/kotlin/com/fongmi/tv/core/source/TvBoxConfigDecoder.kt``
- `type`/`api` runtime classification:
  ``TvBoxSourceRuntimePolicy.kt`` (same file as the payload decoder)

Configs in the wild are not valid JSON (BOMs, ``//`` comments, trailing commas, and the
"image + base64" envelope), so parsing is deliberately tolerant. A config that cannot be
decoded is reported, never silently dropped.
"""

from __future__ import annotations

import base64
import binascii
import json
import re
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional

BOM = "﻿"
_ENVELOPE_MARKER = re.compile(r"[A-Za-z0-9]{8}\*\*")


class PayloadError(Exception):
    """The response is neither JSON nor a configuration envelope we understand."""


def decode_payload(raw: bytes) -> str:
    """Decode a raw configuration response into a JSON string.

    Mirrors ``TvBoxConfigPayloadDecoder.decode``: strip the UTF-8 BOM, accept direct
    JSON, otherwise extract the base64 payload after the ``XXXXXXXX**`` marker.
    """
    if not raw:
        raise PayloadError("configuration response is empty")
    text = raw.decode("utf-8", errors="replace")
    direct = text.lstrip(BOM).strip()
    if direct.startswith("{"):
        return direct

    # The envelope view is byte-preserving (Kotlin reads the same bytes as ISO-8859-1).
    latin = raw.decode("latin-1")
    match = _ENVELOPE_MARKER.search(latin)
    if match is None:
        raise PayloadError("not JSON and no supported encoded envelope")
    encoded = latin[match.end():].strip()
    if not encoded:
        raise PayloadError("encoded configuration payload is empty")
    try:
        # b64decode already ignores characters outside the alphabet, matching the
        # MIME decoder used by the Kotlin side.
        decoded_bytes = base64.b64decode(encoded)
    except (binascii.Error, ValueError):
        raise PayloadError("encoded configuration payload is invalid")
    decoded = decoded_bytes.decode("utf-8", errors="replace").strip()
    if not decoded.startswith("{"):
        raise PayloadError("decoded configuration is not a JSON object")
    return decoded


def strip_json_noise(text: str) -> str:
    """Remove line/block comments (string-aware) and trailing commas."""
    out: List[str] = []
    i, n = 0, len(text)
    in_string = False
    escaped = False
    while i < n:
        ch = text[i]
        if in_string:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            i = n if end < 0 else end
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        out.append(ch)
        i += 1
    cleaned = "".join(out)
    return re.sub(r",(\s*[}\]])", r"\1", cleaned)


def loads_lenient(text: str) -> Dict[str, Any]:
    """Parse a configuration document that may not be strict JSON."""
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        cleaned = strip_json_noise(text)
        value = json.loads(cleaned)
    if not isinstance(value, dict):
        raise PayloadError("configuration is not a JSON object")
    return value


def _string_of(node: Any, name: str, default: str = "") -> str:
    value = node.get(name)
    return value.strip() if isinstance(value, str) else default


def _int_of(node: Any, name: str, default: int = 0) -> int:
    value = node.get(name)
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return default
    return default


def _ext_of(node: Any) -> str:
    """`ext` may be a string, a nested object, or missing (Kotlin: node.toString())."""
    value = node.get("ext")
    if isinstance(value, str):
        return value.strip()
    if value is not None:
        return json.dumps(value, ensure_ascii=False, sort_keys=True)
    return ""


@dataclass
class Site:
    """A single ``sites[]`` entry, reduced to what decides plugin loading."""

    key: str = ""
    name: str = ""
    type: int = 0
    api: str = ""
    ext: str = ""
    jar: str = ""
    searchable: int = 1
    categories: List[str] = field(default_factory=list)

    @property
    def runtime(self) -> str:
        return classify_runtime(self.type, self.api)

    @property
    def required_jar(self) -> str:
        """The plugin reference this site demands, mirroring ``parseFallback``.

        Only spider-runtime sites load a jar, and the fallback prefers `jar` before
        falling back to `ext`.
        """
        if self.runtime != "spider_runtime_required":
            return ""
        return self.jar.strip() or self.ext.strip()

    @classmethod
    def from_obj(cls, node: Any) -> "Site":
        if not isinstance(node, dict):
            raise PayloadError("site entry is not an object")
        categories = node.get("categories")
        listed = [str(c).strip() for c in categories if isinstance(c, str)] if isinstance(categories, list) else []
        return cls(
            key=_string_of(node, "key"),
            name=_string_of(node, "name"),
            type=_int_of(node, "type", 0),
            api=_string_of(node, "api"),
            ext=_ext_of(node),
            jar=_string_of(node, "jar"),
            searchable=_int_of(node, "searchable", 1),
            categories=[c for c in listed if c],
        )


def classify_runtime(type_value: int, api: str) -> str:
    """Mirror ``TvBoxSourceRuntimePolicy.classify``."""
    if type_value != 3:
        if type_value in (0, 1) and api.lower().startswith(("http://", "https://")):
            return "maccms_api"
        if type_value == 4 and api.strip():
            return "remote_spider_http"
        return "unsupported_type"
    if api.split("?")[0].lower().endswith(".js"):
        return "direct_javascript"
    return "spider_runtime_required"


@dataclass
class ConfigDoc:
    """Decoded configuration document."""

    spider: str = ""
    sites: List[Site] = field(default_factory=list)
    lives: int = 0
    raw: Dict[str, Any] = field(default_factory=dict)

    @property
    def spider_sites(self) -> List[Site]:
        return [s for s in self.sites if s.runtime == "spider_runtime_required"]

    def references(self) -> List[Dict[str, str]]:
        """Every plugin reference this configuration would use.

        A site owns its own `jar` when present; otherwise it inherits the top-level
        `spider` field, exactly as ``Site.objectFrom(entry, spiderJar)`` does.
        """
        refs: List[Dict[str, str]] = []
        seen = set()

        def add(ref: str, origin: str, site: Optional[Site]) -> None:
            ref = ref.strip()
            if not ref or ref in seen:
                return
            seen.add(ref)
            refs.append({
                "ref": ref,
                "origin": origin,
                "site_key": site.key if site else "",
                "site_name": site.name if site else "",
                "site_api": site.api if site else "",
            })

        top_level = self.spider.strip()
        for site in self.spider_sites:
            owned = site.required_jar
            add(owned if owned else top_level, "site-jar" if owned else "top-level-spider", site)
        if top_level and not self.spider_sites:
            add(top_level, "top-level-spider", None)
        return refs


def parse_config(text: str) -> ConfigDoc:
    """Decode a configuration document from already-decoded JSON text."""
    raw = loads_lenient(text)
    sites: List[Site] = []
    nodes = raw.get("sites")
    if isinstance(nodes, list):
        for entry in nodes:
            try:
                sites.append(Site.from_obj(entry))
            except PayloadError:
                continue
    lives = raw.get("lives")
    return ConfigDoc(
        spider=_string_of(raw, "spider"),
        sites=sites,
        lives=len(lives) if isinstance(lives, list) else 0,
        raw=raw,
    )


def parse_payload(raw: bytes) -> ConfigDoc:
    """Decode a raw configuration response end to end."""
    return parse_config(decode_payload(raw))


def family_hint(name: str, key: str) -> str:
    """Cheap human label for a site, used only in reports."""
    return (name or key or "<unnamed>").strip()
