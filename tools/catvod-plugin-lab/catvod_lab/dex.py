"""Minimal, read-only DEX reader.

Enough to answer the two questions this lab needs, with no external tooling and no code
execution:

- what classes exist (including ``com.github.catvod.spider.*`` — the plugin entry points
  ``JarLoader`` would instantiate);
- what string literals exist (API hosts, endpoints, user-agents, quality labels).

It reads the item tables, never the instruction stream, so obfuscated or guarded code
that would defeat a decompiler still yields its class and string tables. Anything it
cannot parse is reported instead of being silently dropped.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import List, Optional

DEX_MAGIC = b"dex\n"
KNOWN_VERSIONS = (b"035", b"037", b"038", b"039")

_HEADER_SIZE = 0x70
_OFF_STRING_IDS_SIZE = 56
_OFF_STRING_IDS_OFF = 60
_OFF_TYPE_IDS_SIZE = 64
_OFF_TYPE_IDS_OFF = 68
_OFF_CLASS_DEFS_SIZE = 96
_OFF_CLASS_DEFS_OFF = 100
_OFF_FILE_SIZE = 32
_OFF_ENDIAN_TAG = 40

DEFAULT_MAX_STRINGS = 400_000
DEFAULT_MAX_CLASSES = 200_000


@dataclass
class Dex:
    """Parsed view of one DEX image."""

    source: str = ""
    version: str = ""
    classes: List[str] = field(default_factory=list)
    strings: List[str] = field(default_factory=list)
    truncated_classes: bool = False
    truncated_strings: bool = False
    error: str = ""

    def package_classes(self, prefix: str) -> List[str]:
        dotted = prefix if prefix.endswith(".") else prefix + "."
        return [c for c in self.classes if c.startswith(dotted)]


def looks_like_dex(data: bytes, offset: int = 0) -> bool:
    """True when `data` starts with a DEX header at `offset`."""
    if len(data) - offset < _HEADER_SIZE:
        return False
    if data[offset:offset + 4] != DEX_MAGIC:
        return False
    return data[offset + 4:offset + 7] in KNOWN_VERSIONS and data[offset + 7] == 0


def _endian(data: bytes, offset: int) -> str:
    tag = struct.unpack_from("<I", data, offset + _OFF_ENDIAN_TAG)[0]
    return "<" if tag == 0x12345678 else ">"


def _read_uleb128(data: bytes, pos: int) -> tuple:
    """Read one unsigned LEB128 value; returns ``(value, next_pos)``."""
    value = 0
    shift = 0
    while pos < len(data):
        byte = data[pos]
        value |= (byte & 0x7F) << shift
        pos += 1
        if byte & 0x80 == 0:
            break
        shift += 7
        if shift > 63:
            break
    return value, pos


def _read_string(data: bytes, pos: int) -> str:
    """Read a NUL-terminated MUTF-8 string starting at `pos`."""
    end = data.find(b"\x00", pos)
    if end < 0:
        end = len(data)
    raw = data[pos:end]
    try:
        return raw.decode("utf-8", "replace")
    except Exception:
        return raw.decode("latin-1", "replace")


def parse(data: bytes, source: str = "", max_strings: int = DEFAULT_MAX_STRINGS,
          max_classes: int = DEFAULT_MAX_CLASSES) -> Dex:
    """Parse one DEX image from `data`.

    Parsing is defensive: a malformed header yields a `Dex` carrying `error` and whatever
    partial tables were readable rather than raising into the caller.
    """
    dex = Dex(source=source)
    if not looks_like_dex(data):
        dex.error = "not a DEX image"
        return dex
    if len(data) < _HEADER_SIZE:
        dex.error = f"truncated header ({len(data)} bytes)"
        return dex

    dex.version = data[4:7].decode("ascii", "replace")
    endian = _endian(data, 0)
    unpack = (lambda fmt, off: struct.unpack_from(endian + fmt, data, off)[0])

    try:
        string_count = unpack("I", _OFF_STRING_IDS_SIZE)
        string_off = unpack("I", _OFF_STRING_IDS_OFF)
        type_count = unpack("I", _OFF_TYPE_IDS_SIZE)
        type_off = unpack("I", _OFF_TYPE_IDS_OFF)
        class_count = unpack("I", _OFF_CLASS_DEFS_SIZE)
        class_off = unpack("I", _OFF_CLASS_DEFS_OFF)
    except struct.error as error:
        dex.error = f"unreadable header: {error}"
        return dex

    size = len(data)

    # String table: `string_ids[i]` points at a ULEB128 length followed by MUTF-8 bytes.
    strings: List[str] = []
    for i in range(min(string_count, max_strings)):
        item = string_off + 4 * i
        if item + 4 > size:
            break
        try:
            data_off = unpack("I", item)
        except struct.error:
            break
        if data_off >= size:
            continue
        try:
            _, pos = _read_uleb128(data, data_off)
            strings.append(_read_string(data, pos))
        except Exception:
            continue
    dex.strings = strings
    dex.truncated_strings = string_count > len(strings)

    # Type table: `type_ids[i]` is an index into the string table.
    types: List[str] = []
    for i in range(min(type_count, max_strings)):
        item = type_off + 4 * i
        if item + 4 > size:
            break
        try:
            index = unpack("I", item)
        except struct.error:
            break
        types.append(strings[index] if 0 <= index < len(strings) else "")

    # Class table: the first field of `class_def_item` is the owning type index.
    classes: List[str] = []
    for i in range(min(class_count, max_classes)):
        item = class_off + 32 * i
        if item + 4 > size:
            break
        try:
            index = unpack("I", item)
        except struct.error:
            break
        descriptor = types[index] if 0 <= index < len(types) else ""
        if descriptor.startswith("L") and descriptor.endswith(";"):
            classes.append(descriptor[1:-1].replace("/", "."))
    dex.classes = classes
    dex.truncated_classes = class_count > len(classes)
    return dex


def file_size_at(data: bytes, offset: int = 0) -> Optional[int]:
    """Declared `file_size` of a DEX image starting at `offset`, for carving validation."""
    if len(data) - offset < _HEADER_SIZE or not looks_like_dex(data, offset):
        return None
    try:
        return struct.unpack_from("<I", data, offset + _OFF_FILE_SIZE)[0]
    except struct.error:
        return None
