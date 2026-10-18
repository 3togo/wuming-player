import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('evidence', ROOT / 'scripts/release-evidence.py')
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)

class ReleaseEvidenceTest(unittest.TestCase):
    def test_initialization_retains_same_candidate_and_rejects_modified_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tools = root / 'tools'
            tools.mkdir()
            signer = tools / 'apksigner'
            signer.write_text('#!/bin/sh\necho "Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '"\n')
            signer.chmod(0o755)
            aapt = tools / 'aapt2'
            aapt.write_text("#!/bin/sh\necho \"package: name='com.wuming.android.tv.publicedition' versionCode='1' versionName='fixture'\"\necho \"native-code: 'arm64-v8a'\"\n")
            aapt.chmod(0o755)
            apk = root / 'mobileArm64_v8a/release/app.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'candidate')
            report = root / 'report'
            report.mkdir()
            (report / 'unit-tested-commit.txt').write_text('a' * 40)
            (report / 'SHA256SUMS').write_text(evidence.digest(apk) + '  ' + str(apk) + '\n')
            ledger = evidence.initialize(report, tools)
            self.assertEqual(ledger, evidence.initialize(report, tools))
            data = json.loads(ledger.read_text())
            self.assertEqual("'arm64-v8a'", data['artifacts']['mobileArm64_v8a']['abi'])
            self.assertTrue((report / 'runtime-evidence.md').is_file())
            apk.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                evidence.initialize(report, tools)

    def test_unknown_checks_remain_unknown_and_results_require_exact_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / 'release.apk'
            apk.write_bytes(b'release')
            verification = root / 'verification.apk'
            verification.write_bytes(b'verification')
            log = root / 'observation.txt'
            log.write_text('fixture observation')
            ledger = root / 'candidate.json'
            artifact = {'sha256': evidence.digest(apk), 'certificate_sha256': ['fixture-cert'],
                'checks': {level: {check: {'status': 'not-tested'} for check in checks}
                           for level, checks in evidence.CHECKS.items()}}
            evidence.atomic_json(ledger, {'commit': 'fixture', 'artifacts': {'tv-arm64': artifact}})
            with self.assertRaises(ValueError):
                evidence.record(ledger, 'tv-arm64', 'exact-release', 'first-frame', 'passed', 'authorized', log, verification)
            with self.assertRaises(ValueError):
                evidence.record(ledger, 'tv-arm64', 'exact-release', 'first-frame', 'passed', '', log, apk)
            with self.assertRaises(ValueError):
                evidence.record(ledger, 'tv-arm64', 'verification-build', 'production-smoke', 'passed', 'authorized', log, apk)
            evidence.record(ledger, 'tv-arm64', 'verification-build', 'production-smoke', 'passed', 'authorized', log, apk, verification)
            evidence.record(ledger, 'tv-arm64', 'exact-release', 'first-frame', 'failed', 'authorized', log, apk)
            data = json.loads(ledger.read_text())['artifacts']['tv-arm64']
            self.assertEqual('not-tested', data['checks']['exact-release']['progress']['status'])
            self.assertEqual('failed', data['checks']['exact-release']['first-frame']['status'])
            self.assertEqual(evidence.digest(verification), data['checks']['verification-build']['production-smoke']['tested_apk_sha256'])
            self.assertIn('not-tested', ledger.with_suffix('.md').read_text())
