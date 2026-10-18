import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class AcceptanceBaselineContractTest(unittest.TestCase):
    def test_release_script_uses_offline_unit_acceptance(self):
        script = (ROOT / "scripts/verify-release.sh").read_text(encoding="utf-8")

        self.assertIn(":unitAcceptanceBaseline", script)
        self.assertNotIn("adb -s", script)
        self.assertNotIn("run-instrumented-tests.sh", script)
        self.assertNotIn("smoke", script.lower())

    def test_release_tasks_depend_on_unit_acceptance(self):
        build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

        self.assertIn('task.dependsOn(":unitAcceptanceBaseline", "validateReleaseSource")', build)
        self.assertNotIn("mandatory device release gate", build)


if __name__ == "__main__":
    unittest.main()
