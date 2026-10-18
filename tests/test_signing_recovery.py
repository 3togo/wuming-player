"""Exercise encrypted export and isolated restore with a real disposable key."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which('keytool') and (shutil.which('7z') or shutil.which('7zz')),
                     'requires keytool and 7-Zip')
class SigningRecoveryTest(unittest.TestCase):
    def test_round_trip_pin_validation_and_existing_identity_preservation(self):
        source = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root = base / 'source'
            scripts = root / 'scripts'
            scripts.mkdir(parents=True)
            for name in ['setup-signing.py', 'user_config.py', 'export-signing.py', 'install-signing.sh']:
                shutil.copyfile(source / 'scripts' / name, scripts / name)
            (root / 'updates.json').write_text(json.dumps({'repository': '', 'certificateSha256': ''}))
            env = os.environ.copy()
            env['WUMING_CONFIG_FILE'] = str(base / 'original/config.json')
            def run(command, *, input=None, ok=True):
                result = subprocess.run(command, env=env, cwd=root, input=input,
                                        capture_output=True, text=True)
                # Do not print subprocess output: recovery material must remain private.
                self.assertEqual(result.returncode == 0, ok, 'Unexpected signing operation result')
                return result
            run(['python3', str(scripts / 'setup-signing.py'), '--initialize-project'])
            backup = base / 'export'
            run(['python3', str(scripts / 'export-signing.py'), str(backup), '--generate-password'])
            password = (backup / 'zip-password.txt').read_text()
            restored = base / 'restored/config.json'
            env['WUMING_CONFIG_FILE'] = str(restored)
            command = ['bash', str(backup / 'install-signing.sh'), str(backup / 'wuming-signing.zip')]
            run(command, input=password)
            original = json.loads((base / 'original/config.json').read_text())
            recovered = json.loads(restored.read_text())
            a = Path(original['signing']['storeFile']).expanduser()
            b = Path(recovered['signing']['storeFile']).expanduser()
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertEqual('production', recovered['signingPurpose'])
            self.assertEqual(0o600, restored.stat().st_mode & 0o777)
            run(['python3', str(scripts / 'setup-signing.py')])
            unchanged = restored.read_bytes(), b.read_bytes()
            run(command, input=password, ok=False)
            self.assertEqual(unchanged, (restored.read_bytes(), b.read_bytes()))
            # A mismatched rendered pin must reject the key without creating config.
            pin = json.loads((root / 'updates.json').read_text())['certificateSha256']
            invalid = base / 'wrong-installer.sh'
            invalid.write_text((backup / 'install-signing.sh').read_text().replace(pin, '0' * 64))
            rejected = base / 'rejected/config.json'
            env['WUMING_CONFIG_FILE'] = str(rejected)
            run(['bash', str(invalid), str(backup / 'wuming-signing.zip')], input=password, ok=False)
            self.assertFalse(rejected.exists())
            self.assertEqual([], list((rejected.parent / 'keys').iterdir()))


if __name__ == '__main__':
    unittest.main()
