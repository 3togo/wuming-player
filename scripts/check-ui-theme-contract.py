#!/usr/bin/env python3
"""Enforce semantic Android UI theming without hiding existing migration debt.

The checker scans every hand-written Android UI resource plus Java/Kotlin UI
code.  Existing direct palette usage is recorded as counted fingerprints in a
baseline: moving code does not break the build, removing debt is harmless, and
adding another occurrence does.  Narrow branding and media-overlay exceptions
are kept here so they remain reviewable rather than being scattered as ignores.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
BASELINE = APP / "config" / "ui-theme-contract-baseline.json"

SEMANTIC_THEME_ITEMS = {
    "colorAccent",
    "colorPrimary",
    "colorPrimaryDark",
    "materialAlertDialogTheme",
    "alertDialogTheme",
    "bottomSheetDialogTheme",
    "dialogSurface",
    "dialogSurfaceVariant",
    "dialogOnSurface",
    "dialogOnSurfaceVariant",
    "dialogOutline",
    "dialogRowFocused",
    "dialogRowSelected",
    "dialogSelectionOutline",
}

# These visuals are intentionally rendered over photos/video or are fixed brand
# artwork.  Keeping the list filename-based prevents an entire directory from
# silently opting out of the contract.
EXEMPT_RESOURCE_PATHS = [
    re.compile(r"/drawable[^/]*/(?:ic_launcher[^/]*|ic_banner[^/]*|icon_alist|icon_circle_node)\.xml$"),
    re.compile(r"/drawable[^/]*/ic_notification\.xml$"),
    re.compile(r"/drawable[^/]*/(?:ic_control[^/]*|ic_widget[^/]*|shape_live|shadow)\.xml$"),
    re.compile(r"/layout[^/]*/(?:activity_video|activity_live|view_control[^/]*|view_widget[^/]*|dialog_control)\.xml$"),
]

VISUAL_ATTRIBUTE = re.compile(
    r"(?P<name>(?:android|app):(?:[A-Za-z0-9_]*color[A-Za-z0-9_]*|background(?:Tint)?|foreground|tint))"
    r"\s*=\s*[\"'](?P<value>[^\"']+)[\"']",
    re.IGNORECASE,
)
STYLE_COLOR_ITEM = re.compile(
    r"<item\s+name=[\"'](?P<name>[A-Za-z0-9_:]*(?:color|background|foreground|tint)[A-Za-z0-9_:]*)[\"']\s*>"
    r"\s*(?P<value>[^<]+?)\s*</item>",
    re.IGNORECASE,
)
LITERAL_COLOR = re.compile(r"^#[0-9a-f]{3,8}$", re.IGNORECASE)
DIRECT_COLOR_RESOURCE = re.compile(r"^@(?:android:)?color/(?!transparent$)[A-Za-z0-9_]+$", re.IGNORECASE)
SOURCE_PATTERNS = {
    "source-direct-color": re.compile(
        r"(?:(?<!android\.)\bR\.color\.(?!transparent\b)[A-Za-z0-9_]+|"
        r"android\.R\.color\.(?!transparent\b)[A-Za-z0-9_]+|Color\.(?:BLACK|WHITE|GRAY))"
    ),
    "source-literal-color": re.compile(
        r"(?:Color\.parseColor\(\s*[\"']#[0-9a-f]{3,8}[\"']|Color\.rgb\(|Color\.argb\(|0x[0-9a-f]{6,8})",
        re.IGNORECASE,
    ),
}
ATTR_DECLARATION = re.compile(r"<attr\s+name=[\"'](?P<name>[A-Za-z0-9_]+)[\"']")
ATTR_REFERENCE = re.compile(r"\?attr/(?P<name>[A-Za-z0-9_]+)")
STYLE_ITEM = re.compile(r"<item\s+name=[\"'](?P<name>[A-Za-z0-9_]+)[\"']")


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def is_exempt_resource(path: Path) -> bool:
    candidate = "/" + relative(path)
    return any(pattern.search(candidate) for pattern in EXEMPT_RESOURCE_PATHS)


def resource_files() -> list[Path]:
    result: list[Path] = []
    for path in APP.glob("src/**/res/**/*.xml"):
        parts = path.parts
        source_set = parts[parts.index("src") + 1].lower()
        if "res_backup" in parts or "build" in parts or source_set.endswith("test"):
            continue
        resource_dir = path.parent.name
        if resource_dir.startswith(("layout", "drawable", "color", "menu")):
            result.append(path)
        elif resource_dir.startswith("values") and path.name == "styles.xml":
            result.append(path)
    return sorted(result)


def source_files() -> list[Path]:
    files: list[Path] = []
    for suffix in ("*.java", "*.kt"):
        for path in APP.glob(f"src/**/{suffix}"):
            parts = path.parts
            source_set = parts[parts.index("src") + 1].lower()
            if source_set.endswith("test"):
                continue
            files.append(path)
    return sorted(files)


def fingerprint(rule: str, path: Path, token: str) -> str:
    return f"{rule}|{relative(path)}|{token.lower()}"


def collect_debt() -> Counter[str]:
    debt: Counter[str] = Counter()
    for path in resource_files():
        if is_exempt_resource(path):
            continue
        in_styles = path.parent.name.startswith("values")
        for line in path.read_text(encoding="utf-8").splitlines():
            for match in VISUAL_ATTRIBUTE.finditer(line):
                value = match.group("value")
                if value.startswith("?attr/") or value.startswith("?android:attr/"):
                    continue
                if LITERAL_COLOR.fullmatch(value):
                    debt[fingerprint("xml-literal-color", path, value)] += 1
                elif not in_styles and DIRECT_COLOR_RESOURCE.fullmatch(value):
                    debt[fingerprint("xml-direct-color", path, value)] += 1
            if in_styles:
                for match in STYLE_COLOR_ITEM.finditer(line):
                    value = match.group("value").strip()
                    if LITERAL_COLOR.fullmatch(value):
                        debt[fingerprint("style-literal-color", path, value)] += 1

    for path in source_files():
        text = path.read_text(encoding="utf-8")
        for rule, pattern in SOURCE_PATTERNS.items():
            for match in pattern.finditer(text):
                token = re.sub(r"\s+", "", match.group(0))
                debt[fingerprint(rule, path, token)] += 1
    return debt


def declared_custom_attrs() -> set[str]:
    attrs = APP / "src" / "main" / "res" / "values" / "attrs.xml"
    return {match.group("name") for match in ATTR_DECLARATION.finditer(attrs.read_text(encoding="utf-8"))}


def referenced_visual_custom_attrs() -> set[str]:
    declared = declared_custom_attrs()
    referenced: set[str] = set()
    for path in resource_files():
        text = path.read_text(encoding="utf-8")
        for match in VISUAL_ATTRIBUTE.finditer(text):
            value = match.group("value")
            attr = ATTR_REFERENCE.fullmatch(value)
            if attr and attr.group("name") in declared:
                referenced.add(attr.group("name"))
    return referenced


def structural_errors() -> list[str]:
    errors: list[str] = []
    required = SEMANTIC_THEME_ITEMS | referenced_visual_custom_attrs()
    for flavor in ("mobile", "leanback"):
        styles = APP / "src" / flavor / "res" / "values" / "styles.xml"
        text = styles.read_text(encoding="utf-8")
        base_style = re.search(
            r"<style\s+name=[\"']Theme\.Base[\"'][^>]*>(?P<body>.*?)</style>",
            text,
            re.DOTALL,
        )
        if not base_style:
            errors.append(f"{relative(styles)}: missing Theme.Base")
            continue
        assigned = {match.group("name") for match in STYLE_ITEM.finditer(base_style.group("body"))}
        missing = sorted(required - assigned)
        if missing:
            errors.append(f"{relative(styles)}: Theme.Base is missing {', '.join(missing)}")

    shared_styles = APP / "src" / "main" / "res" / "values" / "styles.xml"
    if 'name="AppDialogStyle"' not in shared_styles.read_text(encoding="utf-8"):
        errors.append(f"{relative(shared_styles)}: missing AppDialogStyle")
    return errors


def load_baseline() -> Counter[str]:
    if not BASELINE.exists():
        raise SystemExit(f"Missing {relative(BASELINE)}; run with --write-baseline once")
    payload = json.loads(BASELINE.read_text(encoding="utf-8"))
    return Counter({key: int(value) for key, value in payload.get("allowedOccurrences", {}).items()})


def write_baseline(debt: Counter[str]) -> None:
    BASELINE.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "formatVersion": 1,
        "description": "Maximum pre-migration direct color occurrences. Counts may decrease; increases fail.",
        "allowedOccurrences": dict(sorted(debt.items())),
    }
    BASELINE.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {relative(BASELINE)} with {sum(debt.values())} occurrences in {len(debt)} fingerprints.")


def debt_regressions(debt: Counter[str], baseline: Counter[str]) -> list[str]:
    regressions: list[str] = []
    for key, count in sorted(debt.items()):
        allowed = baseline.get(key, 0)
        if count > allowed:
            regressions.append(f"{key} ({count} found, {allowed} allowed)")
    return regressions


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-baseline", action="store_true")
    args = parser.parse_args()

    debt = collect_debt()
    structure = structural_errors()
    if structure:
        print("UI theme contract structural violations:", file=sys.stderr)
        for error in structure:
            print(f"  {error}", file=sys.stderr)
        return 1

    if args.write_baseline:
        write_baseline(debt)
        return 0

    baseline = load_baseline()
    regressions = debt_regressions(debt, baseline)
    if regressions:
        print("New direct UI colors violate the theme contract. Use semantic ?attr tokens:", file=sys.stderr)
        for regression in regressions:
            print(f"  {regression}", file=sys.stderr)
        return 1

    print(
        "UI theme contract passed: "
        f"{len(resource_files())} resources and {len(source_files())} source files scanned; "
        f"{sum(debt.values())} baselined occurrences remain."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
