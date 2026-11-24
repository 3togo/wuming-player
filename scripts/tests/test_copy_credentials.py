import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import contextlib
import io

spec = importlib.util.spec_from_file_location('copy_credentials', Path(__file__).parents[1] / 'device/copy-credentials.py')
copy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(copy)


class CredentialCopyTest(unittest.TestCase):
    def test_android_destinations_and_editions(self):
        for sdk in (26, 28, 29):
            self.assertEqual('/sdcard/無名/credentials', copy.destination(sdk, 'com.wuming.android.tv.publicedition'))
            self.assertEqual('/sdcard/無名(testing)/credentials', copy.destination(sdk, 'com.wuming.android.tv.privateedition.verification'))
        for sdk in (30, 33, 37):
            package = 'com.wuming.android.tv.privateedition.verification'
            self.assertEqual(f'/sdcard/Android/data/{package}/files/credentials', copy.destination(sdk, package))

    def test_newest_uses_modification_time_and_ignores_other_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            older = root / ('z' + copy.SUFFIX)
            newer = root / ('a' + copy.SUFFIX)
            older.touch(); newer.touch()
            os.utime(older, (100, 100)); os.utime(newer, (200, 200))
            (root / 'unrelated.json').touch()
            (root / ('link' + copy.SUFFIX)).symlink_to(newer)
            self.assertEqual(newer, copy.newest_backup(root))

    def test_no_backup_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                copy.newest_backup(Path(directory))

    def test_requires_explicit_choice_for_ambiguity(self):
        self.assertEqual('one', copy.select_one(['one'], None, 'device', '--serial'))
        self.assertEqual('two', copy.select_one(['one', 'two'], 'two', 'device', '--serial'))
        for values, selected in [([], None), (['one', 'two'], None), (['one'], 'missing')]:
            with self.assertRaises(ValueError):
                copy.select_one(values, selected, 'device', '--serial')

    def test_accepts_encrypted_envelope_and_rejects_plaintext_or_corruption(self):
        encode = lambda value: base64.b64encode(value).decode()
        envelope = dict(format='tvbox-account-credentials', version=2, kdf='PBKDF2WithHmacSHA256',
                        iterations=210000, salt=encode(b's' * 16), iv=encode(b'i' * 12),
                        ciphertext=encode(b'c' * 32))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'backup.json'
            data = json.dumps(envelope).encode()
            path.write_bytes(data)
            self.assertEqual(data, copy.read_backup(path))
            for data in [b'{}', b'not json', json.dumps({**envelope, 'iv': 'invalid'}).encode(),
                         json.dumps({**envelope, 'format': 'plaintext'}).encode(), b'x' * (2 * 1024 * 1024 + 1)]:
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    copy.read_backup(path)

    def test_copy_verifies_staged_bytes_and_preserves_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / ('backup' + copy.SUFFIX)
            source.touch()
            calls = []
            uploaded = []
            exists = [False]
            def fake_run(*args):
                calls.append(args)
                if args == ('adb', 'devices'):
                    return 'List of devices attached\nserial device'
                if args[-1] == 'pm list packages':
                    return 'package:com.wuming.android.tv.privateedition.verification'
                if args[-1] == 'getprop ro.build.version.sdk':
                    return '30'
                if 'push' in args:
                    uploaded.append(Path(args[-2]).read_bytes())
                if 'pull' in args:
                    Path(args[-1]).write_bytes(uploaded[-1])
                if 'if [ -e' in args[-1]:
                    return 'exists' if exists[0] else ''
                return ''
            with patch.object(copy, 'run', side_effect=fake_run), patch.object(copy, 'read_backup', return_value=b'encrypted'), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(0, copy.main(['--file', str(source)]))
                self.assertEqual([b'encrypted'], uploaded)
                self.assertTrue(any('mv -n' in call[-1] for call in calls))
                calls.clear(); uploaded.clear(); exists[0] = True
                self.assertEqual(1, copy.main(['--file', str(source)]))
                self.assertFalse(uploaded)

    def test_package_filter_excludes_test_runner_and_other_apps(self):
        self.assertTrue(copy.PACKAGE.fullmatch('com.wuming.android.tv.privateedition.verification'))
        self.assertFalse(copy.PACKAGE.fullmatch('com.wuming.android.tv.privateedition.verification.test'))
        self.assertFalse(copy.PACKAGE.fullmatch('org.example.app'))


if __name__ == '__main__':
    unittest.main()
