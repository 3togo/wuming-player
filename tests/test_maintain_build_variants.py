import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts"))
try:
    import maintain
except ImportError:  # the GUI module needs python3-tk even for the pure task mapping
    maintain = None


@unittest.skipIf(maintain is None, "maintain.py requires python3-tk")
class MaintainBuildVariantsTest(unittest.TestCase):
    def test_default_selection_is_the_minimal_debug_pair(self):
        self.assertEqual(
            maintain.assemble_tasks(maintain.DEFAULT_VARIANTS, "debug"),
            [":app:assembleMobileArm64_v8aDebug",
             ":app:assembleLeanbackArmeabi_v7aDebug"])

    def test_all_release_covers_every_mode_and_abi(self):
        self.assertEqual(
            set(maintain.assemble_tasks("all", "release")),
            {f":app:assemble{mode}{abi}Release"
             for mode in ("Mobile", "Leanback", "Leanback_lite")
             for abi in ("Arm64_v8a", "Armeabi_v7a")})

    def test_groups_and_aliases_expand_to_one_task_each(self):
        self.assertEqual(maintain.assemble_tasks("mobile64 m64 l64", "debug"),
                         maintain.assemble_tasks("m64 l64", "debug"))
        self.assertEqual(maintain.assemble_tasks("lite", "release"),
                         [":app:assembleLeanback_liteArm64_v8aRelease",
                          ":app:assembleLeanback_liteArmeabi_v7aRelease"])

    def test_separators_do_not_matter(self):
        self.assertEqual(maintain.assemble_tasks("m64,meabi+leabi", "debug"),
                         maintain.assemble_tasks("m64 meabi leabi", "debug"))

    def test_verification_build_type_reaches_the_task_name(self):
        self.assertEqual(maintain.assemble_tasks("m64", "verification"),
                         [":app:assembleMobileArm64_v8aVerification"])

    def test_unknown_variant_is_reported_with_the_valid_list(self):
        with self.assertRaises(ValueError) as caught:
            maintain.assemble_tasks("m64 q64", "debug")
        message = str(caught.exception)
        self.assertIn("q64", message)
        self.assertIn("lleabi", message)

    def test_empty_selection_raises(self):
        with self.assertRaises(ValueError):
            maintain.assemble_tasks("  ", "debug")

    def test_unknown_build_type_raises(self):
        with self.assertRaises(ValueError):
            maintain.assemble_tasks("m64", "nightly")


if __name__ == "__main__":
    unittest.main()
