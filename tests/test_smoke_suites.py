import contextlib
import importlib.util
import io
import json
import pathlib
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("smoke", ROOT / "scripts/run-smoke-tests.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
ONE = "com.wuming.android.tv.Fixture#first"
TWO = "com.wuming.android.tv.Fixture#second"


def event(test, code):
    cls, method = test.split("#")
    return f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n"


def output(tests, code=0):
    return "".join(event(t, 1) + event(t, code) for t in tests) + "INSTRUMENTATION_CODE: -1\n"


class RunnerResultTest(unittest.TestCase):
    def test_exact_single_run_passes(self):
        self.assertTrue(smoke.instrumentation_result(output([ONE, TWO]), [ONE, TWO])["success"])

    def test_missing_test_never_counts_as_pass(self):
        self.assertFalse(smoke.instrumentation_result(output([ONE]), [ONE, TWO])["success"])

    def test_duplicate_cannot_replace_missing_test(self):
        self.assertFalse(smoke.instrumentation_result(output([ONE, ONE]), [ONE, TWO])["success"])

    def test_skip_assumption_failure_error_and_assertion_are_not_passes(self):
        for status in [-1, -2, -3, -4]:
            with self.subTest(status=status):
                self.assertFalse(smoke.instrumentation_result(output([ONE], status), [ONE])["success"])

    def test_setup_crash_and_timeout_cannot_pass_with_adb_exit_zero(self):
        for text in ["", "INSTRUMENTATION_CODE: -1\n", event(ONE, 1),
                     output([ONE]) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"]:
            self.assertFalse(smoke.instrumentation_result(text, [ONE])["success"])

    def test_missing_start_unexpected_method_and_multiple_runs_fail(self):
        for text in [event(ONE, 0) + "INSTRUMENTATION_CODE: -1\n",
                     output([ONE, TWO]), output([ONE]) + output([ONE])]:
            self.assertFalse(smoke.instrumentation_result(text, [ONE])["success"])

    def test_shell_failure_invalidates_complete_statuses(self):
        self.assertFalse(smoke.instrumentation_result(output([ONE]), [ONE], 1)["success"])

    def test_empty_selection_is_not_success(self):
        self.assertFalse(smoke.instrumentation_result("INSTRUMENTATION_CODE: -1\n", [])["success"])


class SuiteSelectionTest(unittest.TestCase):
    def test_deep_includes_production_and_adds_coverage(self):
        for mode in ["mobile", "leanback"]:
            prod = smoke.load_suite("production", mode)
            deep = smoke.load_suite("deep", mode)
            self.assertEqual(prod, deep[:len(prod)])
            self.assertGreater(len(deep), len(prod))

    def test_every_method_exists_in_its_variant_and_is_a_test(self):
        for mode in ["mobile", "leanback"]:
            source_sets = ["androidTest", mode + "AndroidTest"]
            for group in smoke.load_suite("deep", mode):
                for name in group["tests"]:
                    cls, method = name.split("#")
                    candidates = [ROOT / "app/src" / source / "java" / (cls.replace(".", "/") + ".java")
                                  for source in source_sets]
                    source = next((p for p in candidates if p.exists()), None)
                    self.assertIsNotNone(source, name)
                    tests = re.findall(r"@Test(?:\([^)]*\))?\s+public void (\w+)\(", source.read_text())
                    self.assertIn(method, tests, name)

    def test_live_and_opt_in_fixtures_are_not_silently_counted(self):
        for mode in ["mobile", "leanback"]:
            selection = str(smoke.load_suite("deep", mode))
            self.assertNotIn("NativeProviderLoaderInstrumentedTest", selection)
            self.assertNotIn("realBackendPlaysSeeksAndStopsLanRenderer", selection)
            self.assertNotIn("optIn", selection)

    def test_dry_run_never_builds_or_contacts_devices(self):
        with patch.object(subprocess, "run", side_effect=AssertionError("Unexpected tool call")), \
                patch.object(subprocess, "Popen", side_effect=AssertionError("Unexpected tool call")), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, smoke.main(["--tier", "production", "--mode", "leanback", "--dry-run"]))
        self.assertEqual(11, json.loads(stdout.getvalue())["test_count"])

    def test_extended_opt_ins_have_exact_selection_without_device_calls(self):
        with patch.object(subprocess, "run", side_effect=AssertionError("Unexpected tool call")), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, smoke.main(["--tier", "production", "--mode", "leanback",
                                          "--tv-navigation", "--live-channel", "Big Buck Bunny - Blender Foundation (CC BY 3.0)", "--dry-run"]))
        plan = json.loads(stdout.getvalue())
        self.assertEqual(16, plan["test_count"])
        self.assertEqual({"live_channel": "Big Buck Bunny - Blender Foundation (CC BY 3.0)", "live_seconds": "15"}, plan["groups"][-1]["arguments"])
        for group in plan["groups"]:
            for name in group["tests"]:
                cls, method = name.split("#")
                paths = [ROOT / "app/src" / source / "java" / (cls.replace(".", "/") + ".java")
                         for source in ["androidTest", "leanbackAndroidTest"]]
                source = next(path for path in paths if path.exists())
                self.assertRegex(source.read_text(), r"@Test\s+public void " + method + r"\(")

    def test_deep_remote_opt_in_does_not_duplicate_dialog_tests(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            smoke.main(["--tier", "deep", "--mode", "leanback", "--tv-navigation", "--dry-run"])
        plan = json.loads(stdout.getvalue())
        methods = [test for group in plan["groups"] for test in group["tests"]]
        self.assertEqual(len(methods), len(set(methods)))
        self.assertEqual(34, len(methods))

    def test_persistence_selection_uses_only_existing_fixture_methods(self):
        with patch.object(subprocess, "run", side_effect=AssertionError("Unexpected device call")), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            smoke.main(["--tier", "production", "--mode", "mobile", "--persistence", "--dry-run"])
        selection = json.loads(stdout.getvalue())
        self.assertEqual(20, selection["test_count"])
        for group in selection["groups"]:
            for test in group["tests"]:
                cls, method = test.split("#")
                paths = [ROOT / "app/src" / source / "java" / (cls.replace(".", "/") + ".java")
                         for source in ["androidTest", "mobileAndroidTest"]]
                source = next(path for path in paths if path.exists())
                self.assertRegex(source.read_text(), r"@Test\s+public void " + method + r"\(")
        self.assertNotIn("remoteAccessCredentials", str(selection))

    def test_long_playback_is_bounded_and_extends_host_deadline(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            smoke.main(["--tier", "production", "--mode", "mobile", "--live-channel", "Big Buck Bunny - Blender Foundation (CC BY 3.0)",
                        "--live-seconds", "120", "--dry-run"])
        group = json.loads(stdout.getvalue())["groups"][-1]
        self.assertEqual(220, group["timeout_seconds"])
        self.assertEqual("120", group["arguments"]["live_seconds"])
        for values in [["--live-seconds", "301"], ["--live-seconds", "120"]]:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                smoke.main(["--tier", "production", "--mode", "mobile", "--dry-run", *values])

    def test_invalid_opt_ins_stop_before_device_access(self):
        for extra in [["--tv-navigation"], ["--live-channel", "missing; unsafe"]]:
            with patch.object(subprocess, "run", side_effect=AssertionError("Unexpected tool call")), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                smoke.main(["--tier", "production", "--mode", "mobile", "--dry-run", *extra])

    def test_device_execution_requires_explicit_serial_before_any_tools(self):
        with patch.object(subprocess, "run", side_effect=AssertionError("Unexpected tool call")), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            smoke.main(["--tier", "deep", "--mode", "mobile"])

    def test_duplicate_manifest_method_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "suites.json"
            path.write_text(json.dumps({"version": 1, "production": [{
                "id": "sample", "mode": "all", "ui": False, "timeout_seconds": 10,
                "tests": [ONE, ONE]}], "deep": []}))
            with self.assertRaises(ValueError):
                smoke.load_suite("production", "mobile", path)


class ArtifactIdentityTest(unittest.TestCase):
    def test_actual_manifest_must_match_not_just_metadata(self):
        for package in [smoke.PACKAGE, "com.wuming.android.tv", "unexpected.package"]:
            result = subprocess.CompletedProcess([], 0, "package: name='" + package + "' versionCode='1'", "")
            with patch.object(smoke.shutil, "which", return_value="/fake/aapt"), \
                    patch.object(subprocess, "run", return_value=result):
                if package == smoke.PACKAGE:
                    smoke.verify_apk_package(pathlib.Path("fixture.apk"), smoke.PACKAGE)
                else:
                    with self.assertRaises(ValueError):
                        smoke.verify_apk_package(pathlib.Path("fixture.apk"), smoke.PACKAGE)

    def test_bad_apk_stops_before_any_device_operations(self):
        with patch.object(smoke, "resolve_apk", return_value=pathlib.Path("fixture.apk")), \
                patch.object(smoke, "verify_apk_package", side_effect=ValueError("wrong package")), \
                patch.object(smoke.DeviceRun, "prepare") as prepare, \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
            smoke.main(["--tier", "production", "--mode", "mobile", "--serial", "explicit-device", "--no-build"])
        prepare.assert_not_called()


class CleanupTest(unittest.TestCase):
    def test_cleanup_restores_power_even_if_stop_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            device = smoke.DeviceRun("explicit-device", pathlib.Path(directory))
            device.saved = "15"
            device.summary["success"] = True
            calls = []

            def checked(*args):
                calls.append(args)
                if "force-stop" in args:
                    raise RuntimeError("stop failed")
                return "15\n" if "get" in args else ""

            device.checked = checked
            device.cleanup()
            self.assertIn(("shell", "settings", "put", "global", "stay_on_while_plugged_in", "15"), calls)
            self.assertFalse(device.summary["success"])
            self.assertEqual("15", device.summary["restored_stay_on"])
            self.assertTrue((pathlib.Path(directory) / "summary.json").is_file())

    def test_absent_power_setting_is_deleted_not_written_as_string(self):
        with tempfile.TemporaryDirectory() as directory:
            device = smoke.DeviceRun("explicit-device", pathlib.Path(directory))
            device.saved = "null"
            with patch.object(device, "checked", return_value="null\n") as checked:
                device.cleanup()
            checked.assert_any_call("shell", "settings", "delete", "global", "stay_on_while_plugged_in")
            self.assertEqual([], device.summary["cleanup_errors"])


if __name__ == "__main__":
    unittest.main()
