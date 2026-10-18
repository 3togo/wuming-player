import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('mirrored_publisher', Path(__file__).resolve().parents[1] / 'scripts/publish-mirrored-updates.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class MirroredPublishingTest(unittest.TestCase):
    def setUp(self):
        repository = patch.object(publisher.gitee, 'REPOSITORY', 'fixture-owner/updates')
        repository.start()
        self.addCleanup(repository.stop)

    def test_unconfigured_public_mirror_stops_before_auth_or_network(self):
        import sys
        import tempfile
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(publisher, 'ROOT', Path(directory)), \
             patch.object(publisher.gitee, 'REPOSITORY', ''), \
             patch.object(sys, 'argv', ['publish-mirrored-updates.py']), \
             patch.object(publisher.common, 'run') as run, \
             patch.object(publisher.gitee, 'Gitee') as client:
            with self.assertRaisesRegex(ValueError, 'WUMING_GITEE_REPOSITORY'):
                publisher.main()
            run.assert_not_called()
            client.assert_not_called()

    def channels(self):
        return {'debug': ('tag', {'artifacts': [{'url': 'https://github.com/3togo/TV-updates/releases/download/tag/a.apk', 'sha256': 'digest'}]}, [Path('a.apk')])}

    def test_both_assets_staged_before_either_feed_changes(self):
        events = []
        def github(*args, **kwargs):
            self.assertFalse(kwargs['advance_feed'])
            events.append('github-assets')
            return lambda: events.append('github-feed')
        def gitee(*args, **kwargs):
            self.assertFalse(kwargs['advance_feed'])
            events.append('gitee-assets')
            return lambda: events.append('gitee-feed')
        with patch.object(publisher.common, 'publish', side_effect=github), patch.object(publisher.gitee, 'publish', side_effect=gitee):
            publisher.publish_both(Mock(), {'repository': '3togo/TV-updates'}, self.channels())
        self.assertEqual(['github-assets', 'gitee-assets', 'github-feed', 'gitee-feed'], events)

    def test_failed_second_upload_never_advances_first_feed(self):
        commit = Mock()
        with patch.object(publisher.common, 'publish', return_value=commit), \
             patch.object(publisher.gitee, 'publish', side_effect=ValueError('upload failed')):
            with self.assertRaises(ValueError):
                publisher.publish_both(Mock(), {'repository': '3togo/TV-updates'}, self.channels())
        commit.assert_not_called()

    def test_mirrors_reference_same_release_and_do_not_mutate_input(self):
        original = self.channels()
        output = publisher.mirrored_channels({'repository': '3togo/TV-updates'}, original)
        artifact = output['debug'][1]['artifacts'][0]
        self.assertEqual(['https://github.com/3togo/TV-updates/releases/download/tag/a.apk',
                          'https://gitee.com/fixture-owner/updates/releases/download/tag/a.apk'], artifact['mirrors'])
        self.assertNotIn('mirrors', original['debug'][1]['artifacts'][0])
        self.assertEqual('digest', artifact['sha256'])

    def test_next_version_includes_both_hosts(self):
        client = Mock()
        client.feed.return_value = ({}, {'code': 653})
        client.releases.return_value = [{'tag_name': 'testing-655-debug-abcd'}]
        with patch.object(publisher.common, 'next_version', return_value=658):
            self.assertEqual(658, publisher.next_version(client, {'versionCode': 653}))
            with self.assertRaises(ValueError):
                publisher.next_version(client, {'versionCode': 653}, 654)


class RetentionTest(unittest.TestCase):
    def release(self, code, channel='debug', **kwargs):
        return dict(id=code, tag_name=f'testing-{code}-{channel}-abcd', prerelease=True,
                    assets=[dict(id=code, name='mobile.apk', browser_download_url=f'https://host/{code}.apk')], **kwargs)

    def test_keeps_three_numeric_versions_and_protects_other_channels_and_future(self):
        releases = [self.release(c) for c in [98, 101, 99, 100, 97, 102]]
        releases += [self.release(96, 'release'), self.release(95, draft=True)]
        result = publisher.retention_candidates(releases, 101, {'https://host/97.apk'})
        self.assertEqual([98], [r['id'] for r, a in result])

    def test_empty_releases_do_not_displace_retained_apks_and_non_apks_survive(self):
        releases = [self.release(c) for c in [1, 2, 3, 4, 5]]
        releases[-1]['assets'] = []
        releases[0]['assets'].append(dict(id=50, name='notes.txt'))
        result = publisher.retention_candidates(releases, 5, set())
        self.assertEqual([1], [a['id'] for r, a in result])

    def test_mismatched_feeds_abort_before_deletion(self):
        import base64, json
        client = Mock()
        client.feed.return_value = ({}, {'code': 653})
        payload = base64.b64encode(json.dumps({'code': 654}).encode()).decode()
        with patch.object(publisher.common, 'api', return_value={'content': payload}), \
             patch.object(publisher.common, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'both debug feeds'):
                publisher.prune_old_apks(client, {'repository': '3togo/TV-updates'})
        run.assert_not_called()
        client.api.assert_not_called()

    def test_pruning_uses_asset_endpoints_and_is_repeatable(self):
        import base64, json
        client = Mock()
        feed = {'code': 4, 'artifacts': [{'url': 'https://host/4.apk'}] * 6}
        client.feed.return_value = ({}, feed)
        releases = [self.release(c) for c in range(1, 5)]
        client.releases.return_value = releases
        file = {'content': base64.b64encode(json.dumps(feed).encode()).decode()}
        with patch.object(publisher.common, 'api', side_effect=lambda path: file if '/contents/' in path else releases), \
             patch.object(publisher.common, 'run') as run:
            publisher.prune_old_apks(client, {'repository': '3togo/TV-updates'})
            run.assert_called_once_with('gh', 'api', 'repos/3togo/TV-updates/releases/assets/1', '--method', 'DELETE')
            client.api.assert_called_once_with('/releases/1/attach_files/1', 'DELETE')
            releases[0]['assets'] = []
            run.reset_mock(); client.api.reset_mock()
            publisher.prune_old_apks(client, {'repository': '3togo/TV-updates'})
            run.assert_not_called(); client.api.assert_not_called()

    def test_gitee_attachment_ids_resolved_before_any_deletion(self):
        import base64, json
        client = Mock()
        feed = {'code': 4, 'artifacts': [{'url': 'https://host/4.apk'}] * 6}
        client.feed.return_value = ({}, feed)
        releases = [self.release(c) for c in range(1, 5)]
        gitee_releases = __import__('copy').deepcopy(releases)
        del gitee_releases[0]['assets'][0]['id']
        client.releases.return_value = gitee_releases
        client.api.return_value = []
        file = {'content': base64.b64encode(json.dumps(feed).encode()).decode()}
        with patch.object(publisher.common, 'api', side_effect=lambda path: file if '/contents/' in path else releases), \
             patch.object(publisher.common, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'unambiguously'):
                publisher.prune_old_apks(client, {'repository': '3togo/TV-updates'})
            run.assert_not_called()
            client.api.assert_called_once_with('/releases/1/attach_files?per_page=100&page=1')


    def test_legacy_gitee_debug_without_prerelease_flag_is_pruned(self):
        releases = [self.release(c) for c in range(1, 5)]
        releases[0]['prerelease'] = False
        result = publisher.retention_candidates(releases, 4, set())
        self.assertEqual([1], [r['id'] for r, a in result])
