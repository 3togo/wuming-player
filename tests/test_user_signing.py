"""Offline signing lifecycle tests using temporary stores and the real JDK."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import user_config
spec = importlib.util.spec_from_file_location('setup_signing', SCRIPTS / 'setup-signing.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


@unittest.skipUnless(shutil.which('keytool') and shutil.which('java'), 'JDK required')
class SigningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'private/config.json'
        self.env = patch.dict(os.environ, {'WUMING_CONFIG_FILE': str(self.config)})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.repo = patch.object(signing, 'ROOT', self.root / 'repo')
        self.repo.start()
        self.addCleanup(self.repo.stop)
        (signing.ROOT / 'app').mkdir(parents=True)
        self.old_umask = os.umask(0o077)
        self.addCleanup(os.umask, self.old_umask)

    def run_setup(self, *args):
        with patch.object(sys, 'argv', ['setup-signing.py', *args]), contextlib.redirect_stdout(io.StringIO()):
            signing.main()

    def test_generate_repeat_rotate_and_missing_key(self):
        user_config.write_config({'custom': {'retained': True}})
        self.run_setup()
        first = user_config.read_config()
        template_path = signing.ROOT / 'wuming-config.example.json'
        template = json.loads(template_path.read_text())
        self.assertEqual(set(template['signing']), set(first['signing']))
        self.assertNotIn(first['signing']['storePassword'], template_path.read_text())
        self.assertNotIn(first['signing']['storeFile'], template_path.read_text())
        self.assertEqual(template_path.stat().st_mode & 0o777, 0o644)
        template_path.unlink()
        key = Path(first['signing']['storeFile'])
        key_bytes = key.read_bytes()
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(key.stat().st_mode & 0o777, 0o600)
        self.run_setup()
        self.assertEqual(user_config.read_config(), first)
        self.assertEqual(json.loads(template_path.read_text()), template)
        self.assertEqual(key.read_bytes(), key_bytes)
        self.run_setup('--rotate')
        second = user_config.read_config()
        backups = list(self.config.parent.glob('config.json.*.bak'))
        self.assertEqual(len(backups), 1)
        self.assertEqual(json.loads(backups[0].read_text()), first)
        self.assertEqual(backups[0].stat().st_mode & 0o777, 0o600)
        self.assertNotEqual(second['signing']['storeFile'], str(key))
        self.assertEqual(key.read_bytes(), key_bytes)
        self.assertEqual(second['custom'], first['custom'])
        Path(second['signing']['storeFile']).unlink()
        before = self.config.read_bytes()
        with self.assertRaises(ValueError):
            self.run_setup()
        self.assertEqual(self.config.read_bytes(), before)
        self.assertFalse(self.config.with_name('config.json.lock').exists())

    def test_import_bad_password_does_not_replace_config(self):
        self.run_setup()
        settings = user_config.read_config()['signing']
        before = self.config.read_bytes()
        with patch('getpass.getpass', side_effect=['wrong-password', '']), self.assertRaises(ValueError):
            self.run_setup('--import-keystore', settings['storeFile'])
        self.assertEqual(self.config.read_bytes(), before)
        with patch('getpass.getpass', side_effect=[settings['storePassword'], '']):
            self.run_setup('--import-keystore', settings['storeFile'], '--alias', settings['keyAlias'])
        self.assertEqual(user_config.read_config()['signing']['storeFile'], settings['storeFile'])

    def test_migration_preserves_key_and_resolves_paths(self):
        self.run_setup()
        settings = user_config.read_config()['signing']
        key = Path(settings['storeFile'])
        shutil.copyfile(key, signing.ROOT / 'legacy.p12')
        props = {**settings, 'storeFile': '../legacy.p12', 'buildPython': '/opt/python/bin/python3'}
        (signing.ROOT / 'local.properties').write_text('\n'.join(f'{k}={v}' for k, v in props.items()))
        self.config.unlink()
        with self.assertRaises(ValueError):
            self.run_setup()
        self.run_setup('--migrate-local')
        migrated = user_config.read_config()
        self.assertEqual(Path(migrated['signing']['storeFile']).read_bytes(), key.read_bytes())
        self.assertEqual(migrated['buildPython'], '/opt/python/bin/python3')
        with self.assertRaises(ValueError):
            self.run_setup('--migrate-local')

    def test_project_pin_rejects_wrong_key_without_replacing_it(self):
        self.run_setup()
        settings = user_config.read_config()['signing']
        before = self.config.read_bytes()
        key_bytes = Path(settings['storeFile']).read_bytes()
        (signing.ROOT / 'updates.json').write_text(json.dumps({'certificateSha256': '0' * 64}))
        with self.assertRaises(ValueError):
            self.run_setup()
        with self.assertRaises(ValueError):
            self.run_setup('--rotate')
        self.assertEqual(before, self.config.read_bytes())
        self.assertEqual(key_bytes, Path(settings['storeFile']).read_bytes())

    def test_new_developer_must_import_project_key(self):
        (signing.ROOT / 'updates.json').write_text(json.dumps({'certificateSha256': '0' * 64}))
        with self.assertRaises(ValueError):
            self.run_setup()
        self.assertFalse(self.config.exists())
        self.run_setup('--ephemeral')
        self.assertTrue(self.config.exists())

    def test_initialize_unpublished_project_pins_and_preserves_production_identity(self):
        policy = signing.ROOT / 'updates.json'
        policy.write_text(json.dumps({'repository': '', 'certificateSha256': '', 'versionCode': 1}))
        self.run_setup('--initialize-project')
        first = user_config.read_config()
        self.assertEqual('production', first['signingPurpose'])
        pin = json.loads(policy.read_text())['certificateSha256']
        self.assertEqual(64, len(pin))
        signing.validate_project_identity(first['signing'])
        before = self.config.read_bytes(), policy.read_bytes()
        self.run_setup()
        with self.assertRaises(ValueError):
            self.run_setup('--initialize-project')
        self.assertEqual(before, (self.config.read_bytes(), policy.read_bytes()))

    def test_initialization_rejects_existing_project_or_test_key(self):
        policy = signing.ROOT / 'updates.json'
        for settings in ({'repository': 'fixture-owner/updates', 'certificateSha256': ''},
                         {'repository': '', 'certificateSha256': 'a' * 64}):
            policy.write_text(json.dumps(settings))
            with self.assertRaises(ValueError):
                self.run_setup('--initialize-project')
            self.assertFalse(self.config.exists())
        policy.write_text(json.dumps({'repository': '', 'certificateSha256': ''}))
        with self.assertRaises(ValueError):
            self.run_setup('--initialize-project', '--ephemeral')
        self.assertFalse(self.config.exists())
        self.run_setup('--ephemeral')
        before = self.config.read_bytes()
        with self.assertRaises(ValueError):
            self.run_setup('--initialize-project')
        self.assertEqual(before, self.config.read_bytes())
        self.assertEqual('', json.loads(policy.read_text())['certificateSha256'])

    def test_initialization_refuses_config_inside_source(self):
        (signing.ROOT / 'updates.json').write_text(json.dumps({'repository': '', 'certificateSha256': ''}))
        with patch.dict(os.environ, {'WUMING_CONFIG_FILE': str(signing.ROOT / 'private/config.json')}):
            with self.assertRaises(ValueError):
                self.run_setup('--initialize-project')
        self.assertFalse((signing.ROOT / 'private/config.json').exists())

    def test_java_properties_escapes_and_continuations(self):
        props = signing.ROOT / 'local.properties'
        props.write_text('key\\u0041lias: some\\ alias\nstorePassword=a\\\\b\\\n  c\\=d\n')
        self.assertEqual(signing.legacy_properties(props), {'keyAlias': 'some alias', 'storePassword': 'a\\bc=d'})

    def test_relative_config_rejected_and_invalid_json_preserved(self):
        with patch.dict(os.environ, {'WUMING_CONFIG_FILE': 'relative.json'}), self.assertRaises(ValueError):
            user_config.config_path()
        self.config.parent.mkdir(parents=True)
        self.config.write_text('invalid')
        with self.assertRaises(ValueError):
            self.run_setup()
        self.assertEqual(self.config.read_text(), 'invalid')

    def test_lock_prevents_concurrent_update(self):
        self.config.parent.mkdir(parents=True)
        lock = self.config.with_name('config.json.lock')
        lock.touch()
        with self.assertRaises(FileExistsError):
            self.run_setup()
        self.assertTrue(lock.exists())
        self.assertFalse(self.config.exists())

    def test_home_paths_are_portable_on_disk_and_expanded_for_consumers(self):
        data = {'buildPython': str(Path.home() / '.pyenv/bin/python'),
                'signing': {'storeFile': str(Path.home() / '.config/wuming/keys/shared.jks'),
                            'storePassword': '~/literal-password'},
                'custom': '/unchanged/value'}
        user_config.write_config(data)
        stored = json.loads(self.config.read_text())
        self.assertEqual(stored['buildPython'], '~/.pyenv/bin/python')
        self.assertEqual(stored['signing']['storeFile'], '~/.config/wuming/keys/shared.jks')
        self.assertEqual(user_config.read_config(), data)
        self.assertEqual(stored['signing']['storePassword'], '~/literal-password')
        self.assertEqual(data['signing']['storeFile'], str(Path.home() / '.config/wuming/keys/shared.jks'))


if __name__ == '__main__':
    unittest.main()
