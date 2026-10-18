import importlib.util
import json
import os
import tempfile
import base64
import subprocess
import sys
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('publish_updates', ROOT / 'scripts/publish-updates.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class UploadWrapperTest(unittest.TestCase):
    def test_push_precedes_publish_and_failure_stops_it(self):
        cases = [([], 0), (['--resume'], 0), ([], 1), (['--dry-run'], 0)]
        for options, push_status in cases:
            with self.subTest(options=options, push_status=push_status), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                log = root / 'calls'
                checkout = root / 'build checkout'
                checkout.mkdir()
                # Use the real interpreter by absolute path so the python3 stub
                # cannot recursively invoke itself through PATH.
                fake = f'#!{sys.executable}\n' + '''import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
if name == 'python3' and sys.argv[1:2] == ['-c']:
    sys.exit(0)
with open(os.environ['UPLOAD_TEST_LOG'], 'a') as out:
    out.write(json.dumps([name, *sys.argv[1:]]) + '\\n')
sys.exit(int(os.environ['UPLOAD_TEST_PUSH_STATUS']) if name == 'git' else 0)
'''
                for name in ('git', 'gh', 'python3'):
                    tool = root / name
                    tool.write_text(fake)
                    tool.chmod(0o755)
                result = subprocess.run(
                    ['bash', str(ROOT / 'scripts/upload-apks.sh'), '--build-root', str(checkout), *options],
                    env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                         'UPLOAD_TEST_LOG': str(log), 'UPLOAD_TEST_PUSH_STATUS': str(push_status)},
                    text=True, capture_output=True)
                calls = [json.loads(line) for line in log.read_text().splitlines()]
                if '--dry-run' in options:
                    self.assertEqual([call[0] for call in calls], ['python3'])
                    self.assertNotIn('--publish', calls[-1])
                elif push_status:
                    self.assertEqual(result.returncode, 1)
                    self.assertEqual([call[0] for call in calls], ['gh', 'git'])
                    self.assertIn('no build or APK upload was started', result.stderr)
                else:
                    self.assertEqual([call[0] for call in calls], ['gh', 'git', 'python3'])
                    self.assertEqual(calls[1], ['git', '-C', str(checkout), 'push'])
                    self.assertIn('--publish', calls[-1])
                    self.assertEqual('--build-next-version' in calls[-1], '--resume' not in options)
                if not push_status:
                    self.assertEqual(result.returncode, 0, result.stderr)


class UpdatePublishingTest(unittest.TestCase):
    def test_dirty_release_stops_before_version_lookup_or_build(self):
        for channels in [('release',), ('debug', 'release')]:
            with self.subTest(channels=channels), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with patch.object(publisher, 'run', return_value=' M app/build.gradle') as git, \
                     patch.object(publisher, 'next_version') as version, \
                     patch.object(publisher.subprocess, 'run') as build:
                    with self.assertRaisesRegex(ValueError, '--allow-dirty'):
                        publisher.build_next_version(root, channels, ('leanback',))
                git.assert_called_once_with('git', '-C', str(root), 'status', '--porcelain')
                version.assert_not_called()
                build.assert_not_called()
                self.assertFalse((root / 'updates.json').exists())

    def test_clean_release_and_explicit_override_reach_build(self):
        for allow_dirty in (False, True):
            with self.subTest(allow_dirty=allow_dirty), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                policy = root / 'updates.json'
                policy.write_text(json.dumps({'repository': 'owner/repo', 'versionCode': 642}))
                before = policy.read_bytes()
                with patch.object(publisher, 'run', return_value='') as git, \
                     patch.object(publisher, 'next_version', return_value=643), \
                     patch.object(publisher.subprocess, 'run',
                                  side_effect=subprocess.CalledProcessError(1, ['gradlew'])) as build:
                    with self.assertRaises(subprocess.CalledProcessError):
                        publisher.build_next_version(root, ('release',), ('leanback',), allow_dirty)
                self.assertEqual(git.call_count, 0 if allow_dirty else 1)
                self.assertEqual('-PallowDirtyRelease=true' in build.call_args.args[0], allow_dirty)
                self.assertEqual(policy.read_bytes(), before)

    def test_next_version_paginates_without_new_cli_flags(self):
        first = [{'tag_name': 'unrelated'} for _ in range(100)]
        second = [{'tag_name': 'testing-700-debug-abcdef', 'draft': True}]
        with patch.object(publisher, 'run', side_effect=[json.dumps(first), json.dumps(second)]) as run:
            self.assertEqual(publisher.next_version({'repository': 'owner/repo', 'versionCode': 642}), 701)
        self.assertEqual(run.call_args_list[0].args,
                         ('gh', 'api', 'repos/owner/repo/releases?per_page=100&page=1'))
        self.assertEqual(run.call_args_list[1].args,
                         ('gh', 'api', 'repos/owner/repo/releases?per_page=100&page=2'))

    def test_build_bumps_above_remote_and_builds_only_selected_variants(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = {'repository': 'owner/repo', 'versionCode': 642,
                      'versionName': 'testing', 'certificateSha256': 'aaaa'}
            (root / 'updates.json').write_text(json.dumps(policy))
            stamp = '20260101abcd'
            for abi in publisher.ABIS:
                apk = root / f'app/build/outputs/apk/leanback/{abi}/debug/leanback-{abi}-{stamp}.apk'
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b'new apk')
            with patch.object(publisher, 'api', return_value=[{'tag_name': 'testing-650-debug-abcdef'}]), \
                 patch.object(publisher, 'run', side_effect=['20260101', 'abcdef123456abcd']), \
                 patch.object(publisher.subprocess, 'run') as build, \
                 patch.object(publisher, 'build_tools', return_value=Path('/sdk')), \
                 patch.object(publisher, 'inspect', return_value=(651, f'testing({stamp})', True)):
                publisher.build_next_version(root, ('debug',), ('leanback',))
            command = build.call_args.args[0]
            self.assertIn(':app:assembleLeanbackArm64_v8aDebug', command)
            self.assertIn(':app:assembleLeanbackArmeabi_v7aDebug', command)
            self.assertIn('-PwumingUploadVersionCode=651', command)
            self.assertEqual(len([arg for arg in command if arg.startswith(':app:')]), 2)
            self.assertEqual(json.loads((root / 'updates.json').read_text())['versionCode'], 651)
            self.assertEqual(len(list((root / 'debug').glob('*.apk'))), 2)

    def test_failed_build_does_not_increment_local_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = root / 'updates.json'
            policy.write_text(json.dumps({'repository': 'owner/repo', 'versionCode': 642}))
            before = policy.read_bytes()
            with patch.object(publisher, 'api', return_value=[]), \
                 patch.object(publisher.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['gradlew'])):
                with self.assertRaises(subprocess.CalledProcessError):
                    publisher.build_next_version(root, ('debug',), ('leanback',))
            self.assertEqual(policy.read_bytes(), before)

    def test_dirty_checkout_still_validates_only_requested_apks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'updates.json').write_text(json.dumps({
                'repository': 'owner/repo', 'versionCode': 643,
                'versionName': 'testing', 'certificateSha256': 'aaaa'}))
            stamp = '20260101abcd'
            with patch.object(publisher, 'run', side_effect=[
                    ' M unrelated-file', 'fullcommit', '20260101', 'abcdef123456abcd']), \
                 patch.object(publisher, 'build_tools', return_value=Path('/sdk')), \
                 patch.object(publisher, 'inspect', return_value=(643, f'testing({stamp})', True)) as inspect, \
                 patch.object(publisher, 'checksum', return_value='digest'):
                _, channels = publisher.prepare(root, ('debug',), ('leanback',))
            self.assertEqual(list(channels), ['debug'])
            self.assertEqual(inspect.call_count, 2)
            self.assertTrue(all(path.name.startswith('leanback-') for path in channels['debug'][2]))

    def test_recorded_identity_resumes_original_apks_after_head_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'updates.json').write_text(json.dumps({'repository': 'owner/repo', 'versionCode': 654,
                'versionName': 'testing', 'certificateSha256': 'aaaa'}))
            identity = {'versionCode': 654, 'commit': 'a' * 40, 'stamp': '20260920aaaa'}
            with patch.object(publisher, 'run', return_value='') as git, \
                 patch.object(publisher, 'version_stamp') as current_stamp, \
                 patch.object(publisher, 'build_tools', return_value=Path('/sdk')), \
                 patch.object(publisher, 'inspect', return_value=(654, 'testing(20260920aaaa)', True)), \
                 patch.object(publisher, 'checksum', return_value='digest'):
                _, channels = publisher.prepare(root, ('debug',), ('mobile',), build_identity=identity)
            current_stamp.assert_not_called()
            git.assert_called_once_with('git', '-C', str(root), 'status', '--porcelain')
            self.assertEqual('testing-654-debug-20260920aaaa', channels['debug'][0])
            self.assertEqual('a' * 40, channels['debug'][1]['commit'])
            self.assertTrue(all('20260920aaaa' in p.name for p in channels['debug'][2]))

    def test_partial_upload_preserves_feed_and_can_append_missing_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'leanback.apk'
            apk.write_bytes(b'apk')
            artifact = {'mode': 'leanback', 'abi': 'arm64_v8a', 'sha256': 'digest'}
            manifest = {'code': 643, 'artifacts': [artifact]}
            release = {'id': 7, 'draft': False, 'prerelease': True, 'assets': []}
            responses = [
                {'private': False, 'size': 1}, {'object': {'sha': 'head'}},
                {'tree': {'sha': 'tree'}},
                {'content': base64.b64encode(json.dumps({'code': 642}).encode()).decode()},
                {'draft': False, 'assets': [{'name': apk.name, 'digest': 'sha256:digest', 'size': 3}]},
            ]
            with patch.object(publisher, 'api', side_effect=responses) as api, \
                 patch.object(publisher, 'find_release', return_value=release), \
                 patch.object(publisher, 'run') as run:
                publisher.publish({'repository': 'owner/repo'}, {'debug': ('tag', manifest, [apk])})
            run.assert_called_once_with('gh', 'release', 'upload', 'tag', str(apk), '--repo', 'owner/repo')
            self.assertEqual(api.call_count, 5)  # No tree, commit or feed mutation.

    def test_channels_cannot_downgrade_or_replace_an_existing_version(self):
        old = {'code': 642, 'sha256': 'old'}
        publisher.validate_advance(old, dict(old))
        publisher.validate_advance(old, {'code': 643, 'sha256': 'new'})
        with self.assertRaises(ValueError):
            publisher.validate_advance(old, {'code': 641, 'sha256': 'old'})
        with self.assertRaises(ValueError):
            publisher.validate_advance(old, {'code': 642, 'sha256': 'new'})

    def test_rejects_wrong_signature_and_verification_package(self):
        apk = Path('mobile.apk')
        with patch.object(publisher, 'run', return_value='Signer #1 certificate SHA-256 digest: bad'):
            with self.assertRaises(ValueError):
                publisher.inspect(apk, Path('/sdk'), 'expected')
        with patch.object(publisher, 'run', side_effect=[
            'Signer #1 certificate SHA-256 digest: aaaa',
            "package: name='com.wuming.android.tv.publicedition.verification' versionCode='642' versionName='testing'",
        ]):
            with self.assertRaises(ValueError):
                publisher.inspect(apk, Path('/sdk'), 'aaaa')

    def test_rejects_legacy_and_private_application_ids(self):
        for package in ('com.fongmi.android.tv', 'com.wuming.android.tv'):
            with self.subTest(package=package), patch.object(publisher, 'run', side_effect=[
                'Signer #1 certificate SHA-256 digest: aaaa',
                f"package: name='{package}' versionCode='656' versionName='testing'",
            ]):
                with self.assertRaisesRegex(ValueError, 'unexpected application ID'):
                    publisher.inspect(Path('mobile.apk'), Path('/sdk'), 'aaaa')

    def test_detects_debuggable_channel(self):
        with patch.object(publisher, 'run', side_effect=[
            'Signer #1 certificate SHA-256 digest: aaaa',
            "package: name='com.wuming.android.tv.publicedition' versionCode='642' versionName='testing'\napplication-debuggable",
        ]):
            self.assertEqual((642, 'testing', True), publisher.inspect(Path('mobile.apk'), Path('/sdk'), 'aaaa'))

    def test_accepts_scheme_labels_from_new_android_build_tools(self):
        with patch.object(publisher, 'run', side_effect=[
            'V2 Signer: certificate SHA-256 digest: aaaa\nV3 Signer: certificate SHA-256 digest: aaaa',
            "package: name='com.wuming.android.tv.publicedition' versionCode='642' versionName='testing'",
        ]):
            self.assertEqual((642, 'testing', False), publisher.inspect(Path('mobile.apk'), Path('/sdk'), 'aaaa'))

    def test_resumes_drafts_from_authenticated_release_listing(self):
        draft = {'id': 7, 'tag_name': 'testing-642-debug', 'draft': True}
        with patch.object(publisher, 'api', return_value=[draft]):
            self.assertEqual(draft, publisher.find_release('repos/owner/repo', 'testing-642-debug'))
            self.assertIsNone(publisher.find_release('repos/owner/repo', 'absent'))

    def test_release_lookup_paginates(self):
        draft = {'id': 7, 'tag_name': 'wanted', 'draft': True}
        with patch.object(publisher, 'api', side_effect=[[{'tag_name': str(i)} for i in range(100)], [draft]]):
            self.assertEqual(draft, publisher.find_release('repos/owner/repo', 'wanted'))


class ParallelGithubUploadTest(unittest.TestCase):
    def test_three_overlapping_uploads_and_resume_skips_verified_asset(self):
        import threading
        barrier = threading.Barrier(3, timeout=5)
        lock = threading.Lock()
        active = peak = 0
        uploaded = []
        def transfer(*args):
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(peak, active)
            try:
                barrier.wait()
                with lock:
                    uploaded.append(Path(args[4]).name)
            finally:
                with lock:
                    active -= 1
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / f'{i}.apk' for i in range(7)]
            for path in paths:
                path.write_bytes(b'apk')
            with patch.object(publisher, 'run', side_effect=transfer):
                publisher.upload_github_assets('owner/repo', 'tag', 'debug', paths,
                    [{'sha256': 'digest'}] * 7,
                    [{'name': '0.apk', 'digest': 'sha256:digest', 'size': 3}])
        self.assertEqual(peak, 3)
        self.assertCountEqual(uploaded, [f'{i}.apk' for i in range(1, 7)])

    def test_existing_conflict_stops_all_uploads_before_workers_start(self):
        with patch.object(publisher, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'differs'):
                publisher.upload_github_assets('owner/repo', 'tag', 'debug',
                    [Path('missing.apk'), Path('conflict.apk')], [{'sha256': 'digest'}] * 2,
                    [{'name': 'conflict.apk', 'digest': 'sha256:wrong'}])
        run.assert_not_called()

    def test_upload_failure_prevents_verification_and_feed_changes(self):
        paths = [Path(f'{mode}-{abi}.apk') for mode in publisher.MODES for abi in publisher.ABIS]
        manifest = {'code': 654, 'artifacts': [
            {'mode': mode, 'abi': abi, 'sha256': 'digest'}
            for mode in publisher.MODES for abi in publisher.ABIS]}
        responses = [{'private': False, 'size': 1}, {'object': {'sha': 'head'}},
            {'tree': {'sha': 'tree'}},
            {'content': base64.b64encode(json.dumps({'code': 653}).encode()).decode()}]
        release = {'id': 7, 'draft': True, 'prerelease': True, 'assets': []}
        with patch.object(publisher, 'api', side_effect=responses) as api, \
             patch.object(publisher, 'find_release', return_value=release), \
             patch.object(publisher, 'run', side_effect=subprocess.CalledProcessError(1, ['gh'])) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                publisher.publish({'repository': 'owner/repo'}, {'debug': ('tag', manifest, paths)})
        self.assertEqual(api.call_count, 4)
        self.assertTrue(all(call.args[:3] == ('gh', 'release', 'upload') for call in run.call_args_list))
