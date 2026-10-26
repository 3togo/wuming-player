"""Exercise Linux edition packaging with tiny distributions, without Gradle or installation."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which('dpkg-deb'), 'Debian packaging tools required')
class DesktopDebTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = self.root / 'scripts/desktop/build-desktop-deb.sh'
        self.script.parent.mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/desktop/build-desktop-deb.sh', self.script)
        self.dist = self.root / 'desktop/build/install/tvbox-desktop'
        for name, content in {
            'desktop/build/install/tvbox-desktop/bin/tvbox-desktop': '#!/bin/sh\nexit 0\n',
            'desktop/build/install/tvbox-desktop/lib/fixture.txt': 'shared desktop runtime',
            'app/src/main/ic_launcher-playstore.png': 'fixture icon',
            'LICENSE.md': 'fixture license',
            'desktop/README.md': 'fixture documentation',
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        (self.dist / 'bin/tvbox-desktop').chmod(0o755)
        (self.dist / 'share').mkdir()

    def package(self, identity):
        (self.dist / 'share/desktop-edition.json').write_text(json.dumps({'desktopPackage': identity}))
        return subprocess.run(['bash', str(self.script), '--skip-build', '--version', '1.0-1'],
                              capture_output=True, text=True)

    def test_editions_use_disjoint_install_paths_and_preserve_launcher_arguments(self):
        installed = []
        for identity in ['tvbox-desktop', 'tvbox-desktop-private']:
            with self.subTest(identity=identity):
                result = self.package(identity)
                self.assertEqual(0, result.returncode, result.stderr)
                archive, = (self.root / 'desktop/build/deb').glob(identity + '_*.deb')
                self.assertEqual(identity, subprocess.check_output(
                    ['dpkg-deb', '-f', str(archive), 'Package'], text=True).strip())
                checksum = archive.with_suffix('.deb.sha256').read_text().split()[0]
                self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), checksum)
                target = self.root / ('unpacked-' + identity)
                subprocess.run(['dpkg-deb', '-x', str(archive), str(target)], check=True)
                launcher = target / 'usr/bin' / identity
                self.assertIn(f'exec /usr/lib/{identity}/bin/tvbox-desktop "$@"', launcher.read_text())
                entry = target / f'usr/share/applications/{identity}.desktop'
                self.assertIn('Name=無名', entry.read_text())
                self.assertIn(f'Exec={identity}\n', entry.read_text())
                installed.append({p.relative_to(target) for p in target.rglob('*') if p.is_file()})
        self.assertFalse(installed[0] & installed[1])

    def test_invalid_identity_cannot_escape_package_paths(self):
        for identity in ['../escape', 'bad\nPackage: injected', '', 'with space', None]:
            with self.subTest(identity=identity):
                self.assertNotEqual(0, self.package(identity).returncode)
        self.assertFalse((self.root / 'desktop/build/deb').exists())

    def test_existing_distribution_requires_identity_metadata(self):
        result = subprocess.run(['bash', str(self.script), '--skip-build', '--version', '1.0-1'],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'desktop/build/deb').exists())
