import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class DesktopLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = self.root / 'scripts/desktop/run-tvbox-desktop.sh'
        self.script.parent.mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/desktop/run-tvbox-desktop.sh', self.script)
        self.wrapper = self.root / 'gradlew'
        self.wrapper.write_text('#!/bin/sh\nexit 0\n')
        self.wrapper.chmod(0o755)
        launcher = self.root / 'desktop/build/install/tvbox-desktop/bin/tvbox-desktop'
        launcher.parent.mkdir(parents=True)
        launcher.write_text('''#!/usr/bin/env python3
import fcntl, os
with open(os.environ['LOCK_TO_PROBE'], 'a') as mutex:
    fcntl.flock(mutex, fcntl.LOCK_EX | fcntl.LOCK_NB)
print('Detached runtime can acquire the workspace lock')
''')
        launcher.chmod(0o755)
        self.lock = self.root / 'compose.lock'

    def launch(self, mutex):
        return subprocess.run(['bash', str(self.script), '--smoke'], capture_output=True, text=True,
                              env={**os.environ, 'WUMING_WORKSPACE_LOCK_FD': str(mutex.fileno()),
                                   'LOCK_TO_PROBE': str(self.lock), 'XDG_RUNTIME_DIR': str(self.root)},
                              pass_fds=(mutex.fileno(),), timeout=15)

    def test_detached_runtime_does_not_keep_the_private_workspace_locked(self):
        with self.lock.open('a') as mutex:
            fcntl.flock(mutex, fcntl.LOCK_EX)
            result = self.launch(mutex)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('Detached runtime can acquire', result.stdout)
            self.assertIn('Logs:', result.stderr)
        self.assertFalse(list(self.root.glob('tvbox-desktop-runtime.*')))

    def test_build_failure_does_not_release_the_composition_lock_early(self):
        self.wrapper.write_text('#!/bin/sh\nexit 7\n')
        with self.lock.open('a') as mutex:
            fcntl.flock(mutex, fcntl.LOCK_EX)
            result = self.launch(mutex)
            self.assertEqual(7, result.returncode)
            with self.lock.open('a') as contender:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(contender, fcntl.LOCK_EX | fcntl.LOCK_NB)
