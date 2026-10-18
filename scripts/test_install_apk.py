#!/usr/bin/env python3
"""Installer regression tests. All Android/Gradle tools are mocked; no devices are touched."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install_apk.sh').resolve()

class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='installer test ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.jsonl'
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        INSTALL_TEST_ROOT=str(self.root), INSTALL_TEST_MODE='normal_success')
        self.write(self.bin / 'adb', '''#!/usr/bin/env python3
import os,sys,json
from pathlib import Path
root=Path(os.environ['INSTALL_TEST_ROOT']);args=sys.argv[1:];mode=os.environ['INSTALL_TEST_MODE']
with (root/'calls.jsonl').open('a') as f:f.write(json.dumps(['adb']+args)+'\\n')
if args==['devices']:
 print('List of devices attached\\n172.16.30.86:5555\\tdevice\\nunauthorized-device\\tunauthorized');sys.exit()
if args[:2]!=['-s','172.16.30.86:5555']:sys.exit(95)
if args[2:]==['shell','getprop','ro.build.characteristics']:print('tv');sys.exit()
if args[2:]==['shell','pm','list','features']:print('feature:android.software.leanback');sys.exit()
if args[2:4]==['install','-r']:
 package=Path(args[4]).read_text().strip()
 if mode=='normal_success':print('Success');sys.exit()
 if package=='com.example.tv' or (mode=='double_mismatch' and package=='com.example.tv.verification'):
  print('Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]' if mode=='storage_error' else 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]');sys.exit(1)
 print('Success');sys.exit()
sys.exit(96)
''')
        self.write(self.bin / 'aapt', '''#!/usr/bin/env python3
import sys
from pathlib import Path
print("package: name='"+Path(sys.argv[-1]).read_text().strip()+"' versionCode='1'")
''')
        self.write(self.bin / 'apksigner', '''#!/usr/bin/env python3
print('Signer #1 certificate SHA-256 digest: '+'a'*64)
''')
        self.write(self.root / 'gradlew', '''#!/usr/bin/env python3
import os,sys,json,re
from pathlib import Path
root=Path(os.environ['INSTALL_TEST_ROOT']);args=sys.argv[1:];mode=os.environ['INSTALL_TEST_MODE']
with (root/'calls.jsonl').open('a') as f:f.write(json.dumps(['gradle']+args)+'\\n')
if mode=='build_error':sys.exit(1)
suffix='.verification'
if '-I' in args:
 text=Path(args[args.index('-I')+1]).read_text()
 if "project.path == System.getenv('INSTALL_APK_MODULE')" not in text or os.environ.get('INSTALL_APK_MODULE')!=':app':sys.exit(74)
 suffix=re.search("applicationIdSuffix = '([^']+)'",text).group(1)
package='com.example.tv'+suffix
if mode=='wrong_package':package='com.example.tv'
p=root/'app/build/outputs/apk/leanbackArmeabi_v7a/verification/leanback-armeabi_v7a-test.apk'
p.parent.mkdir(parents=True,exist_ok=True);p.write_text(package)
''')
        apk = self.root / 'app/build/outputs/apk/leanbackArmeabi_v7a/debug/leanback-armeabi_v7a-test.apk'
        apk.parent.mkdir(parents=True)
        apk.write_text('com.example.tv')
        runner = self.root / 'app/build/outputs/apk/androidTest/leanbackArmeabi_v7a/debug/leanback-armeabi_v7a-runner.apk'
        runner.parent.mkdir(parents=True)
        runner.write_text('com.example.tv.test')

    def write(self, path, text):
        path.write_text(text)
        path.chmod(0o755)

    def run_installer(self, mode):
        self.env['INSTALL_TEST_MODE'] = mode
        result = subprocess.run(['bash', str(SCRIPT), '1'], cwd=self.root, env=self.env,
                                text=True, capture_output=True, timeout=20)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertFalse(any('uninstall' in call or 'clear' in call for call in calls))
        return result, calls

    def test_normal_update_does_not_build_or_install_runner(self):
        result, calls = self.run_installer('normal_success')
        self.assertEqual(0, result.returncode, result.stdout+result.stderr)
        self.assertFalse(any(c[0]=='gradle' for c in calls))
        self.assertFalse(any('runner.apk' in ' '.join(c) for c in calls))

    def test_signing_conflict_installs_separate_testing_app(self):
        result, calls = self.run_installer('mismatch')
        self.assertEqual(0, result.returncode, result.stdout+result.stderr)
        self.assertIn('com.example.tv.verification', result.stdout)
        self.assertEqual(2, sum('install' in c for c in calls))
        self.assertTrue(any(':app:assembleLeanbackArmeabi_v7aVerification' in c for c in calls))

    def test_second_conflict_uses_stable_certificate_suffix(self):
        result, calls = self.run_installer('double_mismatch')
        self.assertEqual(0, result.returncode, result.stdout+result.stderr)
        self.assertIn('com.example.tv.verification.saaaaaaaaaaaa', result.stdout)
        self.assertEqual(3, sum('install' in c for c in calls))

    def test_storage_error_does_not_trigger_signature_recovery(self):
        result, calls = self.run_installer('storage_error')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(c[0]=='gradle' for c in calls))

    def test_failed_build_preserves_existing_app(self):
        result, calls = self.run_installer('build_error')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, sum('install' in c for c in calls))

    def test_verification_must_have_different_package_id(self):
        result, calls = self.run_installer('wrong_package')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('separate application ID', result.stderr)
        self.assertEqual(1, sum('install' in c for c in calls))

if __name__ == '__main__':
    unittest.main(verbosity=2)
