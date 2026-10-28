"""Regression tests for the glyph-coverage guard, using isolated source fixtures."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("glyphs", Path(__file__).parents[1] / "check-text-glyphs.py")
glyphs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(glyphs)

# Named escapes keep this file ASCII so the guard's own fixtures cannot hide an encoding problem.
EYE = "\U0001f440"
RIGHT_ARROW = "→"
LEFT_ARROW = "←"
MINUS = "−"
CHECK = "✓"
CIRCLE = "○"
BOX = "─"
PRIVATE_USE = "\ue000"
MIDDLE_DOT = "·"
ELLIPSIS = "…"
DOUBLE_QUOTES = "“”"
KATAKANA_DOT = "・"
FULLWIDTH_ZERO = "０"
HANGUL = "가나다"
CYRILLIC = "А Б В"
GREEK = "αβγ"
EURO = "€"
TRADITIONAL = "這裡什麼都沒有"
MIXED = "Windtalkers 風語者"


class GlyphRangeTest(unittest.TestCase):
    def test_stock_font_text_is_accepted(self):
        for text in ["Nothing here", TRADITIONAL, MIXED, MIDDLE_DOT, ELLIPSIS, DOUBLE_QUOTES,
                     KATAKANA_DOT, CYRILLIC, GREEK, EURO, HANGUL, FULLWIDTH_ZERO]:
            with self.subTest(text=text):
                self.assertEqual([], glyphs.unsafe(text))

    def test_tofu_prone_glyphs_are_rejected(self):
        cases = {
            "Nothing here " + EYE: 0x1F440,   # astral emoji
            RIGHT_ARROW: 0x2192,               # arrow
            LEFT_ARROW: 0x2190,
            MINUS + "1": 0x2212,               # minus sign
            CHECK: 0x2713,                     # check mark
            CIRCLE: 0x25CB,                    # geometric shape
            BOX: 0x2500,                       # box drawing
            PRIVATE_USE: 0xE000,               # private use area
        }
        for text, point in cases.items():
            with self.subTest(text=text):
                self.assertEqual([point], [p for p, _ in glyphs.unsafe(text)])

    def test_line_breaks_and_tabs_inside_a_string_are_accepted(self):
        for text in ["first line\nsecond line", "a\tb", "a\r\nb"]:
            with self.subTest(text=text):
                self.assertEqual([], glyphs.unsafe(text))

    def test_report_names_each_offending_codepoint(self):
        description = glyphs.describe(glyphs.unsafe(CHECK + CIRCLE))
        self.assertIn("U+2713", description)
        self.assertIn("U+25CB", description)


class GlyphGuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def test_string_resource_glyph_is_reported(self):
        self.write("app/src/main/res/values/strings.xml", '<resources>\n'
                   '  <string name="error_empty">Nothing here ' + EYE + '</string>\n</resources>\n')
        errors = glyphs.check_resources(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("U+1F440", errors[0])
        self.assertIn("error_empty", errors[0])

    def test_string_array_items_are_checked(self):
        self.write("app/src/main/res/values/arrays.xml", '<resources>\n'
                   '  <string-array name="modes"><item>Normal</item><item>'
                   + RIGHT_ARROW + ' Fast</item></string-array>\n</resources>\n')
        self.assertEqual(1, len(glyphs.check_resources(self.root)))

    def test_layout_literal_text_is_checked_but_references_are_not(self):
        self.write("app/src/mobile/res/layout/fixture.xml", '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">\n'
                   '  <TextView android:text="' + RIGHT_ARROW + '" />\n'
                   '  <TextView android:text="@string/play_forward" />\n</LinearLayout>\n')
        errors = glyphs.check_layouts(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("U+2192", errors[0])

    def test_source_literal_glyph_is_reported_with_location(self):
        self.write("app/src/leanback/java/com/wuming/android/tv/ui/activity/PlaylistActivity.java",
                   '        text.setText((selected ? "' + CHECK + '  " : "' + CIRCLE + '  ") + name);\n')
        errors = glyphs.check_sources(self.root)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("PlaylistActivity.java:1" in error for error in errors))
        self.assertIn("U+2713", errors[0])
        self.assertIn("U+25CB", errors[1])

    def test_comments_and_format_syntax_are_not_treated_as_copy(self):
        self.write("app/src/main/java/com/wuming/android/tv/bean/Parser.kt",
                   '\n        // Parses "' + RIGHT_ARROW + '" separators inside catalogue feeds.\n'
                   '        val template = "{0} · {1}"\n')
        self.assertEqual([], glyphs.check_sources(self.root))

    def test_a_real_literal_in_the_same_file_is_still_reported(self):
        self.write("app/src/main/java/com/wuming/android/tv/bean/Parser.kt",
                   '\n        // Parses "' + RIGHT_ARROW + '" separators.\n'
                   '        val separator = "' + RIGHT_ARROW + '"\n')
        errors = glyphs.check_sources(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("Parser.kt:3", errors[0])

    def test_clean_fixture_passes(self):
        self.write("app/src/main/res/values/strings.xml",
                   '<resources><string name="ok">Settings, then LAN relay</string></resources>')
        self.write("app/src/main/res/layout/ok.xml",
                   '<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:text="@string/ok" />')
        self.assertEqual([], glyphs.check_resources(self.root) + glyphs.check_layouts(self.root))


if __name__ == "__main__":
    unittest.main()
