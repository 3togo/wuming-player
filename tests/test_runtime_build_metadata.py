"""Built runtime archives must not expose the builder's personal metadata paths."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_runtime", ROOT / "scripts/python-runtime/prepare.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeBuildMetadataTest(unittest.TestCase):
    def test_bundle_redacts_build_paths_only_in_sysconfig_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "libraries/python-runtime/src/main/python").mkdir(parents=True)
            licenses = root / "scripts/python-runtime/licenses"
            licenses.mkdir(parents=True)
            (licenses / "fixture.txt").write_text("Fixture notice")
            (root / "scripts/python-runtime/requirements.txt").write_text("fixture==1\n")
            home = root / "home"
            stdlib = home / "lib/python3.13"
            stdlib.mkdir(parents=True)
            original = f"build_time_vars = {{'CC': '/home/fixture-builder/sdk/clang', 'srcdir': '{root}/source'}}\n"
            (stdlib / "_sysconfigdata__android_fixture.py").write_text(original)
            (stdlib / "ordinary_fixture.py").write_text(original)
            output = root / "archive"
            with patch.object(runtime, "ROOT", root), patch.object(Path, "home", return_value=Path("/home/fixture-builder")):
                runtime.bundle(home, output)
            with zipfile.ZipFile(output / "runtime.zip") as archive:
                metadata = archive.read("lib/python3.13/_sysconfigdata__android_fixture.py").decode()
                self.assertNotIn("/home/fixture-builder", metadata)
                self.assertNotIn(str(root), metadata)
                self.assertIn("/build/user/sdk/clang", metadata)
                self.assertIn("/build/source/source", metadata)
                self.assertEqual(original, archive.read("lib/python3.13/ordinary_fixture.py").decode())
                self.assertEqual(b"Fixture notice", archive.read("licenses/fixture.txt"))
