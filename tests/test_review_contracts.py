"""Offline guards for APP-003/009/010/011; runtime migrations remain instrumentation."""
import importlib.util
from pathlib import Path
import re
import unittest
import tempfile

ROOT = Path(__file__).resolve().parents[1]

class ReviewContractsTest(unittest.TestCase):
    def test_migration_registry_and_fixture_continuity(self):
        db = (ROOT / 'app/src/main/java/com/wuming/android/tv/db/AppDatabase.kt').read_text()
        current = int(re.search(r'VERSION(?::\s*Int)?\s*=\s*(\d+)', db)[1])
        migrations = (ROOT / 'app/src/main/java/com/wuming/android/tv/db/Migrations.kt').read_text()
        registered = re.findall(r'MIGRATION_(\d+)_(\d+)', migrations.split('fun all()')[1])
        self.assertEqual([(str(i), str(i + 1)) for i in range(30, current)], registered)
        self.assertIn('.addMigrations(*Migrations.all())', db)
        self.assertNotIn('fallbackToDestructiveMigration', db)
        for version in range(35, current + 1):
            self.assertTrue((ROOT / f'app/schemas/com.wuming.android.tv.db.AppDatabase/{version}.json').is_file())
        for version in range(35, 46):
            historical = ROOT / f'app/schemas/com.fongmi.android.tv.db.AppDatabase/{version}.json'
            current_path = ROOT / f'app/schemas/com.wuming.android.tv.db.AppDatabase/{version}.json'
            self.assertEqual(historical.read_bytes(), current_path.read_bytes())

    def test_active_documentation_links_and_identifiers(self):
        build = (ROOT / 'app/build.gradle').read_text()
        package = re.search(r'applicationId "([^"]+)"', build)[1]
        self.assertIn(f'`{package}`', (ROOT / 'README.md').read_text())
        for name in ['README.md', 'AGENTS.md', 'docs/README.md', 'docs/desktop-sync-architecture.md']:
            path = ROOT / name
            text = path.read_text()
            self.assertNotIn('com.fongmi.android', text, name)
            self.assertNotIn('com.fongmi.tv.core', text, name)
            for target in re.findall(r'\]\(([^)]+)\)', text):
                if '://' not in target and not target.startswith('#'):
                    self.assertTrue((path.parent / target.split('#')[0]).exists(), f'{name}: {target}')

    def test_smoke_identity_tracks_build_without_accepting_normal_package(self):
        spec = importlib.util.spec_from_file_location('smoke_review', ROOT / 'scripts/run-smoke-tests.py')
        smoke = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(smoke)
        build = (ROOT / 'app/build.gradle').read_text()
        package = re.search(r'applicationId "([^"]+)"', build)[1]
        namespace = re.search(r"namespace = '([^']+)'", build)[1]
        self.assertEqual(package + '.verification', smoke.PACKAGE)
        self.assertEqual(smoke.PACKAGE + '/' + namespace + '.ui.activity.HomeActivity', smoke.COMPONENT)
        self.assertIsNotNone(smoke.METHOD.fullmatch(namespace + '.Fixture#test'))
        self.assertNotEqual(package, smoke.PACKAGE)

    def test_failure_summary_keeps_module_and_failure_counts(self):
        spec = importlib.util.spec_from_file_location('summary_review', ROOT / 'scripts/test-report-summary.py')
        summary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(summary)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = root / 'libraries/playback-core/build/test-results/test/TEST-Fixture.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="3" failures="1" errors="0" skipped="1"><system-out>not copied</system-out></testsuite>')
            rows = summary.summarize(root)
            self.assertEqual(1, len(rows))
            self.assertEqual(1, rows[0]['failures'])
            self.assertEqual(1, rows[0]['skipped'])
            self.assertTrue(rows[0]['report'].startswith('libraries/playback-core/'))
            self.assertNotIn('not copied', str(rows))
