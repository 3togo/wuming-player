#!/usr/bin/env python3
"""Reject user-visible text that relies on glyphs a stock Android/TV font may not contain.

Only app-authored text is checked. Catalogue, EPG and site content arrive at runtime and
may legitimately contain anything, so those paths are out of scope.

Anything outside SAFE_RANGES is a "tofu" risk: the platform falls back to a box, a blank
space or a wrong-glyph substitution on devices that ship a stripped Roboto/Noto set
(cheap TV boxes, leanback_lite targets, and OEM Android builds without NotoColorEmoji).
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"

# Codepoint ranges every stock Android/TV typeface covers: Roboto, Noto Sans and
# Noto Sans CJK. Values are (inclusive_start, inclusive_end).
SAFE_RANGES = [
    (0x0009, 0x000A),  # Tab and line feed: ordinary whitespace inside a string body
    (0x000D, 0x000D),  # Carriage return
    (0x0020, 0x007E),  # Basic Latin (printable)
    (0x00A0, 0x024F),  # Latin-1 Supplement, Latin Extended-A/B
    (0x0370, 0x03FF),  # Greek and Coptic
    (0x0400, 0x04FF),  # Cyrillic
    (0x2010, 0x2015),  # Hyphens and dashes
    (0x2018, 0x201F),  # Curved quotes
    (0x2022, 0x2022),  # Bullet
    (0x2026, 0x2026),  # Horizontal ellipsis
    (0x2032, 0x2033),  # Prime / double prime
    (0x20AC, 0x20AC),  # Euro sign
    (0x3000, 0x303F),  # CJK symbols and punctuation
    (0x3040, 0x30FF),  # Kana
    (0x3100, 0x312F),  # Bopomofo
    (0x4E00, 0x9FFF),  # CJK unified ideographs
    (0xAC00, 0xD7AF),  # Hangul syllables
    (0xFF00, 0xFFEF),  # Halfwidth and fullwidth forms
]

# Text attributes that reach the screen directly.
TEXT_ATTRS = ["text", "hint", "contentDescription", "title", "textOn", "textOff"]

# Source files whose literals are parsed protocol/format syntax rather than UI copy.
SOURCE_EXEMPTIONS = {
    "app/src/main/java/com/wuming/android/tv/bean/VodShortName.kt",
}

STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def unsafe(text):
    """Return a sorted list of (codepoint, character) pairs that are not font-safe."""
    found = {}
    for char in text:
        point = ord(char)
        if not any(start <= point <= end for start, end in SAFE_RANGES):
            found.setdefault(point, char)
    return sorted(found.items())


def describe(violations):
    return ", ".join(f"U+{point:04X} {char!r}" for point, char in violations)


def check_resources(root):
    errors = []
    for path in sorted((root / "app/src").glob("*/res/values*/*.xml")):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for element in tree.getroot():
            if element.tag not in ("string", "string-array", "plurals", "item"):
                continue
            for node in ([element] if element.tag == "string" else element.findall("item")):
                body = "".join(node.itertext())
                violations = unsafe(body)
                if violations:
                    name = element.get("name", "<item>")
                    errors.append(f"{path.relative_to(root)}: <{element.tag} name=\"{name}\"> "
                                  f"uses unsupported glyphs: {describe(violations)}")
    return errors


def check_layouts(root):
    errors = []
    for path in sorted((root / "app/src").glob("*/res/layout*/*.xml")):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for view in tree.getroot().iter():
            for attr in TEXT_ATTRS:
                for prefix in (A, "{http://schemas.android.com/tools}"):
                    value = view.get(prefix + attr)
                    if not value or value.startswith(("@", "?")):
                        continue
                    violations = unsafe(value)
                    if violations:
                        errors.append(f"{path.relative_to(root)}: {view.tag.rsplit('.', 1)[-1]} "
                                      f"{attr}=\"{value}\" uses unsupported glyphs: {describe(violations)}")
    return errors


def check_sources(root):
    errors = []
    for path in sorted((root / "app/src").rglob("*")):
        if path.suffix not in (".kt", ".java"):
            continue
        relative = path.relative_to(root).as_posix()
        if "/test/" in relative or "/androidTest/" in relative or relative in SOURCE_EXEMPTIONS:
            continue
        for line, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = text.strip()
            if stripped.startswith(("*", "//", "/*")):
                continue
            for match in STRING_LITERAL.finditer(text):
                literal = match[1]
                if "{" in literal and "}" in literal:
                    continue  # Structured format syntax, not display copy.
                violations = unsafe(literal)
                if violations:
                    errors.append(f"{relative}:{line}: literal {literal!r} uses unsupported "
                                  f"glyphs: {describe(violations)}")
    return errors


if __name__ == "__main__":
    problems = check_resources(ROOT) + check_layouts(ROOT) + check_sources(ROOT)
    if problems:
        raise SystemExit("Unsupported glyphs found:\n" + "\n".join(problems))
    print("Glyph coverage passed: string resources, layout text and source literals "
          "use only stock-font codepoints.")
