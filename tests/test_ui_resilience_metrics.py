import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "test-ui-resilience.py"
SPEC = importlib.util.spec_from_file_location("ui_resilience", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class UiResilienceMetricParserTest(unittest.TestCase):

    DEVICE = {"manufacturer": "Acme", "model": "Phone", "sdk": "34"}

    def test_launch_timings_are_optional_and_ordered(self):
        output = "Status: ok\nThisTime: 321\nTotalTime: 456\nWaitTime: 500\n"
        self.assertEqual((321, 456, 500), MODULE.UiResilienceTest.parse_launch_timings(output))
        self.assertEqual((None, None, None),
                         MODULE.UiResilienceTest.parse_launch_timings("Status: ok"))

    def test_modern_memory_summary_is_parsed(self):
        output = "TOTAL PSS: 123,456 TOTAL RSS: 234,567 TOTAL SWAP PSS: 0"
        self.assertEqual((123456, 234567),
                         MODULE.UiResilienceTest.parse_process_memory(output))

    def test_package_identity_omits_unavailable_fields(self):
        output = "versionCode=42 minSdk=26 targetSdk=35\nversionName=1.2.3"
        self.assertEqual({"versionCode": "42", "versionName": "1.2.3"},
                         MODULE.UiResilienceTest.parse_package_info(output))

    def test_matching_device_reports_time_and_memory_regressions(self):
        baseline = self.baseline([
            MODULE.LaunchMetric("cold-1", 90, 100, 110, 1000, 2000),
            MODULE.LaunchMetric("cold-2", 95, 100, 115, 1000, 2000),
        ])
        current = [
            MODULE.LaunchMetric("cold-1", 100, 130, 140, 1300, 2200),
            MODULE.LaunchMetric("warm-background-foreground", 50, 50, 60, 900, 1800),
        ]

        result = MODULE.compare_performance_baseline(
            current, baseline, self.DEVICE, "pkg", "mobile", 20, 15)

        self.assertEqual("compared", result["status"])
        self.assertEqual(2, len(result["regressions"]))

    def test_different_device_skips_comparison(self):
        baseline = self.baseline([
            MODULE.LaunchMetric("cold-1", 90, 100, 110, 1000, 2000)
        ])
        baseline["device"]["model"] = "Other"

        result = MODULE.compare_performance_baseline(
            [], baseline, self.DEVICE, "pkg", "mobile", 20, 15)

        self.assertEqual("skipped", result["status"])
        self.assertEqual([], result["regressions"])

    def test_matching_device_accepts_improvement(self):
        baseline = self.baseline([
            MODULE.LaunchMetric("cold-1", 90, 100, 110, 1000, 2000)
        ])
        current = [MODULE.LaunchMetric("cold-1", 80, 90, 100, 900, 1800)]

        result = MODULE.compare_performance_baseline(
            current, baseline, self.DEVICE, "pkg", "mobile", 20, 15)

        self.assertEqual([], result["regressions"])

    def baseline(self, metrics):
        return {
            "package": "pkg",
            "mode": "mobile",
            "device": dict(self.DEVICE),
            "launch_metrics": [MODULE.asdict(metric) for metric in metrics],
        }


if __name__ == "__main__":
    unittest.main()
