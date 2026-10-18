import os
import hashlib
import json
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ReleaseArtifactMatrixTest(unittest.TestCase):
    def run_release(self, missing_arm32=False, removed_sdk=False, unresolved_license=False, untracked_notice=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            (root / 'verify.sh').write_text((ROOT / 'scripts/verify-release.sh').read_text())
            # The ledger CLI has its own APK/signature tests; this fixture checks gate wiring.
            (root / 'scripts').mkdir()
            (root / 'scripts/check-removed-sdks.py').write_text((ROOT / 'scripts/check-removed-sdks.py').read_text())
            (root / 'scripts/check-public-provenance.py').write_text((ROOT / 'scripts/check-public-provenance.py').read_text())
            (root / 'docs').mkdir()
            (root / 'docs/public-third-party-inventory.json').write_text(json.dumps({
                'stableReleaseReviewComplete': True, 'releaseBlockers': []}))
            (root / 'docs/public-provenance-manifest.json').write_text('{"artifacts": []}')
            if untracked_notice:
                # A locally present ignored notice must not hide an incomplete source export.
                notice = b'Copyright fixture; redistributable test notice.'
                (root / 'ignored-notice.txt').write_bytes(notice)
                (root / 'docs/public-provenance-manifest.json').write_text(json.dumps({
                    'artifacts': [], 'supportingArtifacts': [{
                        'path': 'ignored-notice.txt',
                        'sha256': hashlib.sha256(notice).hexdigest()}]}))
            (root / 'vendor/cling/upstream').mkdir(parents=True)
            (root / 'vendor/cling/upstream/inventory.json').write_text('{"files": []}')
            (root / 'third_party/corresponding-source').mkdir(parents=True)
            (root / 'third_party/corresponding-source/inventory.json').write_text('[]')
            (root / 'third_party/javascript').mkdir()
            (root / 'third_party/javascript/inventory.json').write_text('{"artifacts": []}')
            (root / 'docs/public-maven-inventory.json').write_text(json.dumps({
                'components': [], 'unresolved': ['unknown:license:1'] if unresolved_license else []}))
            with zipfile.ZipFile(root / 'fixture.apk', 'w') as apk:
                apk.writestr('classes.dex', b'dex-fixture')
                if removed_sdk:
                    apk.writestr('lib/arm64-v8a/libjpa.so', b'removed-sdk-fixture')
            (root / 'scripts/release-evidence.py').write_text('import pathlib\npathlib.Path("ledger-invoked").touch()\n')
            git = bin_dir / 'git'
            git.write_text('#!/bin/sh\nif [ "$1" = rev-parse ]; then echo fixture-commit; fi\n')
            git.chmod(0o755)
            gradle = root / 'gradlew'
            gradle.write_text('''#!/bin/sh
printf '%s\\n' "$@" >> invocations.txt
case "$*" in
  *assemble*)
    for variant in mobileArm64_v8a leanbackArm64_v8a leanbackArmeabi_v7a; do
      if [ "$variant" = leanbackArmeabi_v7a ] && [ "$MISSING_ARM32" = 1 ]; then continue; fi
      mkdir -p "app/build/outputs/apk/$variant/release"
      cp fixture.apk "app/build/outputs/apk/$variant/release/app.apk"
    done;;
esac
''')
            gradle.chmod(0o755)
            result = subprocess.run(['bash', 'verify.sh'], cwd=root, env={
                **os.environ, 'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'],
                'MISSING_ARM32': '1' if missing_arm32 else '0',
            }, capture_output=True, text=True)
            report = root / 'app/build/reports/acceptance-baseline'
            hashes = (report / 'SHA256SUMS').read_text() if (report / 'SHA256SUMS').exists() else ''
            receipt = (report / 'unit-tested-commit.txt').exists()
            return result, hashes, receipt, ((root / 'invocations.txt').read_text()
                if (root / 'invocations.txt').exists() else '')

    def test_every_supported_variant_has_lint_build_and_checksum(self):
        result, hashes, receipt, invocations = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(receipt)
        self.assertEqual(3, len(hashes.splitlines()))
        for variant in ('MobileArm64_v8a', 'LeanbackArm64_v8a', 'LeanbackArmeabi_v7a'):
            self.assertIn(':app:lint' + variant + 'Release', invocations)
            self.assertIn(':app:assemble' + variant + 'Release', invocations)
            self.assertIn(variant[0].lower() + variant[1:], hashes)

    def test_missing_arm32_fails_without_success_receipt(self):
        result, hashes, receipt, _ = self.run_release(missing_arm32=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('leanbackArmeabi_v7a', result.stderr)
        self.assertFalse(receipt)
        self.assertEqual('', hashes)

    def test_removed_sdk_in_final_apk_fails_without_success_receipt(self):
        result, hashes, receipt, _ = self.run_release(removed_sdk=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('removed native library', result.stderr)
        self.assertFalse(receipt)
        self.assertEqual('', hashes)

    def test_unreviewed_runtime_license_stops_before_building(self):
        result, hashes, receipt, invocations = self.run_release(unresolved_license=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Unresolved Maven license', result.stderr)
        self.assertFalse(receipt)
        self.assertEqual('', hashes)
        self.assertEqual('', invocations)

    def test_locally_present_notice_missing_from_git_stops_before_building(self):
        result, hashes, receipt, invocations = self.run_release(untracked_notice=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Provenance input missing from Git snapshot', result.stderr)
        self.assertFalse(receipt)
        self.assertEqual('', hashes)
        self.assertEqual('', invocations)
