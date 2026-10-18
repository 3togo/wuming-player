import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('gitee_publisher', Path(__file__).resolve().parents[1] / 'scripts/publish-gitee-updates.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class GiteePublishingTest(unittest.TestCase):
    def setUp(self):
        repository = patch.object(publisher, 'REPOSITORY', 'fixture-owner/updates')
        repository.start()
        self.addCleanup(repository.stop)

    def test_unconfigured_public_publisher_never_reads_or_uses_a_token(self):
        import sys
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(publisher, 'ROOT', Path(directory)), \
             patch.object(publisher, 'REPOSITORY', ''), \
             patch.object(sys, 'argv', ['publish-gitee-updates.py']), \
             patch.object(publisher, 'Gitee') as client:
            with self.assertRaisesRegex(ValueError, 'WUMING_GITEE_REPOSITORY'):
                publisher.main()
            client.assert_not_called()

    def test_api_error_reports_reason_without_exposing_token(self):
        client = publisher.Gitee('fixture-secret')
        response = Mock(ok=False, status_code=400)
        response.json.return_value = {'message': 'Rejected fixture-secret'}
        with patch.object(client.session, 'request', return_value=response):
            with self.assertRaises(ValueError) as error:
                client.api('/releases/1/attach_files', 'POST')
        self.assertIn('Rejected [redacted]', str(error.exception))
        self.assertNotIn('fixture-secret', str(error.exception))

    def test_version_includes_partial_releases_and_live_feed(self):
        releases = [{'tag_name': 'testing-654-debug-20260920abcd'}]
        self.assertEqual(655, publisher.choose_version(651, releases, {'code': 650}))
        self.assertEqual(657, publisher.choose_version(651, releases, {'code': 656}))
        with self.assertRaises(ValueError):
            publisher.choose_version(651, releases, {'code': 650}, 652)

    def publication(self, client, directory):
        paths, artifacts = [], []
        for mode in publisher.common.MODES:
            for abi in publisher.common.ABIS:
                apk = Path(directory) / f'{mode}-{abi}.apk'
                apk.write_bytes(b'APK')
                paths.append(apk)
                artifacts.append(dict(mode=mode, abi=abi, sha256='digest',
                    url=f'https://github.com/3togo/TV-updates/releases/download/tag/{apk.name}'))
        manifest = dict(code=652, artifacts=artifacts)
        client.feed.return_value = ({'sha': 'original'}, {'code': 650})
        client.releases.return_value = [dict(tag_name='tag', id=1, prerelease=True,
            assets=[{'name': p.name} for p in paths])]
        return {'repository': '3togo/TV-updates', 'versionCode': 652}, {'debug': ('tag', manifest, paths)}

    def test_bad_existing_asset_never_advances_feed(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Mock()
            policy, channels = self.publication(client, directory)
            with patch.object(publisher, 'verify_download', side_effect=ValueError('mismatch')):
                with self.assertRaises(ValueError):
                    publisher.publish(client, policy, channels)
            client.api.assert_not_called()

    def test_all_six_verified_before_feed_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Mock()
            policy, channels = self.publication(client, directory)
            with patch.object(publisher, 'verify_download') as verify:
                publisher.publish(client, policy, channels)
                self.assertEqual(6, verify.call_count)
                self.assertTrue(all(c.args[0].startswith('https://gitee.com/fixture-owner/updates/') for c in verify.call_args_list))
            self.assertEqual('PUT', client.api.call_args.args[1])
            self.assertEqual('original', client.api.call_args.kwargs['json']['sha'])

    def test_concurrent_feed_change_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Mock()
            policy, channels = self.publication(client, directory)
            client.feed.side_effect = [({'sha': 'original'}, {'code': 650}), ({'sha': 'changed'}, {'code': 653})]
            with patch.object(publisher, 'verify_download'):
                with self.assertRaisesRegex(ValueError, 'changed during upload'):
                    publisher.publish(client, policy, channels)
            client.api.assert_not_called()
