#!/usr/bin/env python3
"""Check role coverage (including inherited styles) and single ownership of UI sizes."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"
ROLES = {"display", "headline", "title", "body", "label", "caption", "micro"}


def style_sizes(root, source):
    styles = {}
    for layer in dict.fromkeys(("main", source)):
        for path in sorted((root / f"app/src/{layer}/res").glob("values*/*.xml")):
            for style in ET.parse(path).getroot().findall("style"):
                name = style.get("name")
                parent = style.get("parent", name.rpartition(".")[0])
                props = {item.get("name"): item.text for item in style.findall("item")}
                styles.setdefault(name, []).append((parent, props))

    def resolve(name, seen=()):
        name = name.removeprefix("@style/")
        if name not in styles or name in seen:
            return set()
        sizes = set()
        for parent, props in styles[name]:
            if "android:textSize" in props:
                sizes.add(props["android:textSize"])
            else:
                sizes.update(resolve(parent, (*seen, name)))
        return sizes
    return resolve


# These are non-UI paint/player operations or the user's required localized rail.
# Entries are receiver-specific so adding another setter in these files still fails.
SETTER_EXCEPTIONS = {
    ("app/src/main/java/com/wuming/android/tv/utils/ResUtil.java", "textWidthPaint"),
    ("app/src/main/java/com/wuming/android/tv/player/PlayerManager.kt", "danPlayer"),
    ("app/src/mobile/java/com/wuming/android/tv/ui/activity/HomeActivity.java", "textView"),
}


def check(root):
    errors, count = [], 0
    for source in ("main", "mobile", "leanback"):
        resolve = style_sizes(root, source)
        for path in (root / f"app/src/{source}/res").glob("layout*/*.xml"):
            for view in ET.parse(path).iter():
                explicit = view.get(A + "textSize")
                sizes = {explicit} if explicit else resolve(view.get("style", ""))
                roles = {size.removeprefix("@dimen/font_") for size in sizes
                         if size and size.startswith("@dimen/font_")}
                tags = [tag.get(A + "value") for tag in view.findall("tag")
                        if tag.get(A + "id") == "@id/tag_typography_role"]
                kind = view.tag.rsplit(".", 1)[-1]
                text_control = kind.endswith(("TextView", "EditText", "Button", "CheckBox", "RadioButton", "Switch", "CheckedTextView")) and kind not in ("ImageButton", "FloatingActionButton")
                if text_control and explicit and not explicit.startswith("@dimen/font_"):
                    errors.append(f"{path.relative_to(root)}: {view.get(A + 'id')} must use a semantic font dimension")
                if text_control and not tags:
                    errors.append(f"{path.relative_to(root)}: {view.get(A + 'id')} has no typography role")
                if not roles and not tags:
                    continue
                count += 1
                if len(tags) != 1 or tags[0] not in ROLES or (roles and roles != {tags[0]}):
                    errors.append(f"{path.relative_to(root)}: {view.get(A + 'id')} needs role {sorted(roles)}")
        for path in (root / f"app/src/{source}/java").rglob("*"):
            if path.suffix not in (".kt", ".java"):
                continue
            relative = path.relative_to(root).as_posix()
            if relative == "app/src/main/java/com/wuming/android/tv/utils/TextScale.kt":
                continue
            for line, text in enumerate(path.read_text().splitlines(), 1):
                for match in re.finditer(r"([\w.]+)\??\.setTextSize\s*\(", text):
                    if (relative, match[1]) not in SETTER_EXCEPTIONS:
                        errors.append(f"{relative}:{line}: use TextScale.bind/setRole instead of a runtime size setter")

    def detail_roles(folder):
        path = root / f"app/src/mobile/res/{folder}/activity_video.xml"
        if not path.exists():
            return {}
        return {view.get(A + "id"): view.get(A + "textSize")
                for view in ET.parse(path).iter()
                if view.get(A + "id") and view.get(A + "textSize", "").startswith("@dimen/font_")}

    portrait, landscape = detail_roles("layout"), detail_roles("layout-land")
    for key in portrait.keys() & landscape.keys():
        if portrait[key] != landscape[key]:
            errors.append(f"Video detail changes text role with rotation: {key}")
    return errors, count


if __name__ == "__main__":
    errors, count = check(ROOT)
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"Typography contract passed: {count} semantic views, inherited styles, runtime setters, rotation-stable detail roles.")
