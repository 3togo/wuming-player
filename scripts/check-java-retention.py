#!/usr/bin/env python3
"""Enforce reviewed Java boundaries; this is not a bytecode compatibility checker."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = "docs/java-retention.json"


def production_sources(root):
    """Discover source sets, including flavor/verification sets, but never build outputs/tests."""
    source_sets = list(root.glob("*/src/*")) + list(root.glob("libraries/*/src/*"))
    return sorted({p for source_set in source_sets
                   if source_set.is_dir() and "test" not in source_set.name.lower()
                   and any((source_set.parent.parent / name).is_file()
                           for name in ("build.gradle", "build.gradle.kts"))
                   for p in source_set.rglob("*") if p.suffix in (".java", ".kt")})


def relative_path(root, value):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not value:
        raise ValueError(f"Expected a repository-relative path: {value}")
    return root / path


def in_required_java_folder(root, path):
    parts = path.relative_to(root).parts
    return any(parts[i] == "src" and parts[i + 2] == "java-required"
               for i in range(len(parts) - 3))


def check(root):
    data = json.loads((root / REGISTRY).read_text())
    if data.get("version") != 1 or not data.get("rules"):
        raise ValueError("Expected a nonempty version 1 Java retention registry")
    errors, ids, retained, required = [], set(), {}, set()
    sources = production_sources(root)
    kotlin = [(p, p.read_text()) for p in sources if p.suffix == ".kt"]
    for rule in data["rules"]:
        rule_id = rule["id"]
        if not re.fullmatch(r"[a-z][a-z0-9-]+", rule_id) or rule_id in ids:
            errors.append(f"Invalid or duplicate rule ID: {rule_id}")
        ids.add(rule_id)
        if rule["kind"] not in ("language-required", "compatibility-hold"):
            errors.append(f"{rule_id}: unsupported kind")
        for field in ("reason", "release_when", "evidence", "paths", "markers"):
            if not rule.get(field):
                errors.append(f"{rule_id}: missing {field}")
        for evidence in rule["evidence"]:
            if not relative_path(root, evidence).is_file():
                errors.append(f"{rule_id}: missing evidence {evidence}")
        for marker in rule["markers"]:
            path = relative_path(root, marker)
            if not path.is_file() or f"JAVA-RETENTION: {rule_id}" not in path.read_text():
                errors.append(f"{rule_id}: missing source/directory marker in {marker}")
        for name in rule["paths"]:
            path = relative_path(root, name)
            if name in retained:
                errors.append(f"{name}: duplicate retention entry")
            retained[name] = rule_id
            if rule["kind"] == "language-required":
                required.add(name)
                if not in_required_java_folder(root, path):
                    errors.append(f"{rule_id}: language-required source must live in src/<source-set>/java-required: {name}")
            if path.suffix != ".java" or path not in sources:
                errors.append(f"{rule_id}: retained production Java source missing: {name}")
                continue
            package = re.search(r"^\s*package\s+([\w.]+)\s*;", path.read_text(), re.M)
            if not package:
                errors.append(f"{name}: missing package declaration")
                continue
            # Check any Kotlin filename/source directory, not only a sibling Foo.kt.
            declaration = re.compile(r"\b(?:class|interface|object|typealias)\s+" + re.escape(path.stem) + r"\b")
            for other, content in kotlin:
                other_package = re.search(r"^\s*package\s+([\w.]+)", content, re.M)
                if other_package and package[1] == other_package[1] and declaration.search(content):
                    errors.append(f"{rule_id}: Kotlin replacement conflicts with retained Java: {other.relative_to(root)}")
        if rule.get("scope"):
            scope = relative_path(root, rule["scope"])
            for path in sources:
                if path.is_relative_to(scope) and str(path.relative_to(root)) not in rule["paths"]:
                    errors.append(f"{rule_id}: unreviewed source in retained scope: {path.relative_to(root)}")
            for name in rule["paths"]:
                if not relative_path(root, name).is_relative_to(scope):
                    errors.append(f"{rule_id}: retained path outside scope: {name}")
    for path in sources:
        name = str(path.relative_to(root))
        if in_required_java_folder(root, path) and (path.suffix != ".java" or name not in required):
            errors.append(f"{name}: java-required accepts only registered language-required Java sources")
        for marker in re.findall(r"JAVA-RETENTION: ([a-z0-9-]+)", path.read_text()):
            if retained.get(str(path.relative_to(root))) != marker:
                errors.append(f"{path.relative_to(root)}: unregistered retention marker {marker}")
    return errors, retained


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", action="store_true", help="Print current production language counts")
    args = parser.parse_args()
    try:
        errors, retained = check(ROOT)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Java retention registry error: {error}")
        return 1
    if errors:
        print("Java retention verification failed:\n" + "\n".join(errors))
        return 1
    print(f"Java retention verified: {len(retained)} protected files. See docs/language-boundaries.md.")
    if args.inventory:
        counts = Counter()
        for path in production_sources(ROOT):
            name = str(path.relative_to(ROOT))
            module = name.split("/src/")[0]
            state = "retained-java" if name in retained else "review-before-migration" if path.suffix == ".java" else "kotlin"
            counts[module, state] += 1
        for (module, state), count in sorted(counts.items()):
            print(f"{module}: {state}={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
