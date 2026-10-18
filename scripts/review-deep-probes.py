#!/usr/bin/env python3
"""Disposable offline review probes, not an acceptance suite or an Android device test.
Requires locally built playback/user-library core jars and the catvod debug runtime jar.
Prints observed current behavior; does not intentionally add failing tests to CI.
"""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def dependency(group, artifact, version):
    base = Path.home() / '.gradle/caches/modules-2/files-2.1' / group / artifact / version
    return next(base.glob(f'*/{artifact}-{version}.jar'))


def main():
    javac = Path(shutil.which('javac')).resolve()
    java = javac.with_name('java')
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    jars = [ROOT / 'libraries/playback-core/build/libs/tvbox-playback-core-0.1.0-SNAPSHOT.jar',
            ROOT / 'libraries/user-library-core/build/libs/tvbox-user-library-core-0.1.0-SNAPSHOT.jar',
            ROOT / 'catvod/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar',
            dependency('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10'),
            dependency('com.google.code.gson', 'gson', '2.13.2'),
            next(sdk.glob('platforms/android-*/android.jar'))]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(jar)
    with tempfile.TemporaryDirectory(prefix='tv-deep-review-') as directory:
        work = Path(directory)
        cp = os.pathsep.join(map(str, [work, *jars]))
        subprocess.run([str(javac), '-cp', cp, '-d', str(work), str(ROOT / 'docs/reviews/probes/DeepReviewProbe.java')], check=True)

        def probe(*arguments):
            result = subprocess.run([str(java), '-cp', cp, 'DeepReviewProbe', *arguments], text=True, capture_output=True)
            if result.returncode:
                raise RuntimeError(result.stderr)
            return json.loads(result.stdout)

        wal_results = []
        for initial, committed in [(0, 46), (46, 47), (29, 46)]:
            dbpath = work / f'wal-{initial}-{committed}.db'
            db = sqlite3.connect(dbpath)
            try:
                db.execute('CREATE TABLE retained(value TEXT)')
                db.execute(f'PRAGMA user_version={initial}')
                db.commit()
                db.execute('PRAGMA journal_mode=WAL')
                db.execute('PRAGMA wal_autocheckpoint=0')
                db.execute(f'PRAGMA user_version={committed}')
                db.execute("INSERT INTO retained VALUES ('committed')")
                db.commit()
                files = list(work.glob(dbpath.name + '*'))
                before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
                main_version = int.from_bytes(dbpath.read_bytes()[60:64], 'big')
                result = probe('wal', str(dbpath))
                after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
                result.update(main_header=main_version, sqlite_committed_version=db.execute('PRAGMA user_version').fetchone()[0],
                              original_bytes_unchanged=before == after)
                wal_results.append(result)
            finally:
                db.close()

        def snapshot_fixture(transaction):
            path = work / f'snapshot-{transaction}.db'
            reader = sqlite3.connect(path, isolation_level=None)
            writer = sqlite3.connect(path, isolation_level=None)
            restored = sqlite3.connect(':memory:')
            try:
                reader.execute('PRAGMA journal_mode=WAL')
                schema = 'CREATE TABLE MediaResource(uri TEXT PRIMARY KEY); CREATE TABLE MediaSource(id TEXT PRIMARY KEY, resourceUri TEXT REFERENCES MediaResource(uri));'
                reader.executescript(schema)
                writer.execute('PRAGMA foreign_keys=ON')
                if transaction:
                    reader.execute('BEGIN')
                resources = reader.execute('SELECT * FROM MediaResource').fetchall()
                writer.execute('BEGIN')
                writer.execute("INSERT INTO MediaResource VALUES ('fixture-resource')")
                writer.execute("INSERT INTO MediaSource VALUES ('fixture-source', 'fixture-resource')")
                writer.execute('COMMIT')
                sources = reader.execute('SELECT * FROM MediaSource').fetchall()
                if transaction:
                    reader.execute('COMMIT')
                restored.execute('PRAGMA foreign_keys=ON')
                restored.executescript(schema)
                try:
                    restored.executemany('INSERT INTO MediaResource VALUES (?)', resources)
                    restored.executemany('INSERT INTO MediaSource VALUES (?, ?)', sources)
                    outcome = 'restorable'
                except sqlite3.IntegrityError as error:
                    outcome = str(error)
                return dict(read_transaction=transaction, resources=len(resources), sources=len(sources), restore=outcome)
            finally:
                restored.close()
                writer.close()
                reader.close()

        report = dict(scope='Offline JVM calls to built app/core code; typed preference double and disposable host SQLite; no ADB, accounts or production data.',
                      wal_preflight=wal_results, backup_snapshot=[snapshot_fixture(False), snapshot_fixture(True)],
                      preference_roundtrip=probe('preferences'), loopback_transport=probe('transport'),
                      artifact_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in jars[:3]})
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
