#!/usr/bin/env python3
"""Exercise packaged JNI from /tmp without WUMING_PYTHON_RUNTIME or an external Python executable."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[3]
output = root / 'build/python-native/distribution-smoke'
output.mkdir(parents=True, exist_ok=True)
java = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'
classpath = str(root / 'desktop/build/install/tvbox-desktop/lib/*')
subprocess.run([str(java / 'javac'), '-cp', classpath, '-d', str(output),
                str(Path(__file__).with_name('RelocatedPythonSmoke.java'))], check=True)
env = dict(os.environ)
env.pop('WUMING_PYTHON_RUNTIME', None)
subprocess.run([str(java / 'java'), '-cp', str(output) + ':' + classpath, 'RelocatedPythonSmoke'], cwd='/tmp', env=env, check=True)
