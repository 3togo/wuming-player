"""Regression tests for typography guard blind spots, using isolated source fixtures."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("typography", Path(__file__).parents[1] / "check-typography.py")
typography = importlib.util.module_from_spec(spec)
spec.loader.exec_module(typography)


class TypographyGuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def styles(self):
        self.write("app/src/main/res/values/styles.xml", '''<resources>
          <style name="Action"><item name="android:textSize">@dimen/font_label</item></style>
          <style name="Action.Child" />
        </resources>''')

    def layout(self, text):
        self.write("app/src/mobile/res/layout/fixture.xml", '<TextView xmlns:android="http://schemas.android.com/apk/res/android" ' + text)

    def test_inherited_style_cannot_bypass_role_coverage(self):
        self.styles()
        self.layout('style="@style/Action.Child" />')
        errors, _ = typography.check(self.root)
        self.assertTrue(any("needs role ['label']" in error for error in errors))

    def test_default_platform_text_also_requires_a_role(self):
        self.layout('/>')
        errors, _ = typography.check(self.root)
        self.assertTrue(any("no typography role" in error for error in errors))

    def test_valid_inherited_role_passes(self):
        self.styles()
        self.layout('style="@style/Action.Child"><tag android:id="@id/tag_typography_role" android:value="label" /></TextView>')
        self.assertEqual(([], 1), typography.check(self.root))

    def test_new_setter_in_exception_file_is_rejected(self):
        self.write("app/src/mobile/java/com/wuming/android/tv/ui/activity/HomeActivity.java", '''
          textView.setTextSize(unit, localizedRailSize);
          category.setTextSize(unit, screenHeight * 0.03f);
        ''')
        errors, _ = typography.check(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("runtime size setter", errors[0])


if __name__ == "__main__":
    unittest.main()
