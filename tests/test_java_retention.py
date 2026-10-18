"""Mutation checks for the migration guard, independent of Gradle/Android tooling."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("java_retention", ROOT / "scripts/check-java-retention.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class JavaRetentionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.write("sample/build.gradle", "")
        self.source = "sample/src/main/java-required/example/Boundary.java"
        self.write(self.source, "package example;\n// JAVA-RETENTION: example-api\npublic class Boundary {}")
        self.write("sample/src/test/java/example/Contract.java", "// contract fixture")
        self.rule = dict(id="example-api", kind="language-required", reason="nullable varargs",
                         release_when="contract deliberately retired",
                         evidence=["sample/src/test/java/example/Contract.java"],
                         paths=[self.source], markers=[self.source])
        self.save([self.rule])

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def save(self, rules):
        self.write(guard.REGISTRY, json.dumps(dict(version=1, rules=rules)))

    def errors(self):
        return "\n".join(guard.check(self.root)[0])

    def test_current_repository_register_is_valid(self):
        self.assertEqual([], guard.check(ROOT)[0])

    def test_valid_boundary_and_unprotected_migration(self):
        self.write("sample/src/main/kotlin/example/Helper.kt", "package example\nclass Helper")
        self.assertEqual("", self.errors())

    def test_deleted_or_converted_java_is_rejected(self):
        (self.root / self.source).unlink()
        self.write("sample/src/main/kotlin/example/Boundary.kt", "package example\nclass Boundary")
        self.assertIn("retained production Java source missing", self.errors())

    def test_kotlin_replacement_in_different_filename_is_rejected(self):
        self.write("sample/src/main/kotlin/example/Anything.kt", "package example\nclass Boundary")
        self.assertIn("Kotlin replacement conflicts", self.errors())

    def test_missing_marker_or_evidence_is_rejected(self):
        self.write(self.source, "package example;\npublic class Boundary {}")
        (self.root / self.rule["evidence"][0]).unlink()
        self.assertIn("missing source/directory marker", self.errors())
        self.assertIn("missing evidence", self.errors())

    def test_duplicate_and_incomplete_rules_are_rejected(self):
        self.rule["release_when"] = ""
        self.save([self.rule, self.rule])
        errors = self.errors()
        self.assertIn("duplicate rule ID", errors)
        self.assertIn("duplicate retention entry", errors)
        self.assertIn("missing release_when", errors)

    def test_scoped_hold_rejects_unregistered_java_and_kotlin(self):
        self.rule["scope"] = "sample/src/main/java-required/example"
        self.save([self.rule])
        self.write("sample/src/main/java-required/example/Extra.java", "package example; class Extra {}")
        self.write("sample/src/main/java-required/example/ExtraKotlin.kt", "package example\nclass ExtraKotlin")
        self.assertEqual(2, self.errors().count("unreviewed source in retained scope"))

    def test_required_source_cannot_move_back_to_ordinary_java(self):
        moved = "sample/src/main/java/example/Boundary.java"
        (self.root / moved).parent.mkdir(parents=True, exist_ok=True)
        (self.root / self.source).rename(self.root / moved)
        self.rule["paths"] = self.rule["markers"] = [moved]
        self.save([self.rule])
        self.assertIn("language-required source must live", self.errors())

    def test_required_folder_rejects_unregistered_java_and_kotlin(self):
        for extension in ("java", "kt"):
            self.write(f"sample/src/main/java-required/example/Extra.{extension}", "package example;")
        self.assertEqual(2, self.errors().count("accepts only registered language-required Java sources"))

    def test_compatibility_hold_cannot_be_mistaken_for_java_requirement(self):
        self.rule["kind"] = "compatibility-hold"
        self.save([self.rule])
        self.assertIn("accepts only registered language-required Java sources", self.errors())

    def test_unknown_source_marker_is_rejected(self):
        self.write("sample/src/main/java/example/Other.java",
                   "package example;\n// JAVA-RETENTION: unknown-api\nclass Other {}")
        self.assertIn("unregistered retention marker", self.errors())

    def test_build_outputs_and_test_sources_are_not_production(self):
        for name in ["sample/build/generated/src/main/kotlin/example/Boundary.kt",
                     "scratch/src/main/kotlin/example/Boundary.kt",
                     "sample/src/test/kotlin/example/Boundary.kt",
                     "sample/src/mobileAndroidTest/java/example/Boundary.kt"]:
            self.write(name, "package example\nclass Boundary")
        self.assertEqual("", self.errors())

    def test_paths_cannot_escape_repository(self):
        self.rule["paths"] = ["../Boundary.java"]
        self.save([self.rule])
        with self.assertRaisesRegex(ValueError, "repository-relative"):
            guard.check(self.root)


if __name__ == "__main__":
    unittest.main()
