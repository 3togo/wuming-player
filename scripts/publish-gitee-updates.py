#!/usr/bin/env python3
"""Build signed debug APKs and publish the Gitee feed after verifying every download."""
import argparse
import copy
import base64
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import requests

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('publish_updates', ROOT / 'scripts/publish-updates.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
REPOSITORY = os.environ.get('WUMING_GITEE_REPOSITORY', '')
BRANCH = 'release'


class Gitee:
    def __init__(self, token):
        self.session = requests.Session()
        self.session.headers['Authorization'] = 'Bearer ' + token

    def api(self, path='', method='GET', **kwargs):
        response = self.session.request(method, f'https://gitee.com/api/v5/repos/{REPOSITORY}{path}',
                                        timeout=(30, 300), **kwargs)
        if not response.ok:
            # Report only a scalar error message, never headers or an entire response.
            # Redact the credential even if an upstream error echoes it.
            detail = ""
            try:
                payload = response.json()
                message = payload.get('message', payload.get('error', '')) if isinstance(payload, dict) else ''
                if isinstance(message, str):
                    token = self.session.headers['Authorization'].removeprefix('Bearer ')
                    detail = ': ' + message.replace(token, '[redacted]')[:300]
            except ValueError:
                pass
            raise ValueError(f'Gitee {method} {path.split("?")[0]}: HTTP {response.status_code}{detail}')
        return response.json() if response.content else None

    def releases(self):
        result = []
        page = 1
        while True:
            batch = self.api(f'/releases?per_page=100&page={page}')
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1

    def feed(self):
        file = self.api(f'/contents/channels/debug.json?ref={BRANCH}')
        return file, json.loads(base64.b64decode(file['content']))


def choose_version(local, releases, feed, requested=None):
    highest = max(local, feed['code'])
    for release in releases:
        match = re.fullmatch(r'testing-(\d+)-(?:debug|release)-[0-9a-f]+', release['tag_name'])
        if match:
            highest = max(highest, int(match[1]))
    code = highest + 1 if requested is None else requested
    if not highest < code <= 2100000000:
        raise ValueError(f'New version must exceed local and published versions ({highest})')
    return code


def verify_download(url, apk, expected):
    # Public unauthenticated download verifies the same route used by the app.
    digest = hashlib.sha256()
    size = 0
    with requests.get(url, stream=True, timeout=(30, 300)) as response:
        if response.status_code != 200:
            raise ValueError(f'Public APK download failed: HTTP {response.status_code}')
        for chunk in response.iter_content(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    if digest.hexdigest() != expected or size != apk.stat().st_size:
        raise ValueError(f'{apk.name}: remote APK checksum or size mismatch; feed unchanged')


def publish(client, policy, channels, advance_feed=True):
    channels = copy.deepcopy(channels)
    tag, manifest, paths = channels['debug']
    for artifact in manifest['artifacts']:
        artifact['url'] = artifact['url'].replace(
            f'https://github.com/{policy["repository"]}/', f'https://gitee.com/{REPOSITORY}/', 1)
    if {(a['mode'], a['abi']) for a in manifest['artifacts']} != {(m, a) for m in common.MODES for a in common.ABIS}:
        raise ValueError('Gitee debug feed requires all six variants')
    original, old = client.feed()
    common.validate_advance(old, manifest)
    release = next((r for r in client.releases() if r['tag_name'] == tag), None)
    if release is None:
        release = client.api('/releases', 'POST', json=dict(
            tag_name=tag, name=tag, body='Testing build', prerelease=True, target_commitish=BRANCH))
    if not release['prerelease']:
        raise ValueError('Expected a testing prerelease')
    names = {a['name'] for a in release['assets']}
    for index, (apk, artifact) in enumerate(zip(paths, manifest['artifacts']), 1):
        if apk.name not in names:
            print(f'Uploading [{index}/6]: {apk.name}', flush=True)
            with apk.open('rb') as stream:
                client.api(f'/releases/{release["id"]}/attach_files', 'POST',
                           files={'file': (apk.name, stream, 'application/vnd.android.package-archive')})
        else:
            print(f'Verifying existing [{index}/6]: {apk.name}', flush=True)
        verify_download(artifact['url'], apk, artifact['sha256'])
        print(f'Verified [{index}/6]: {apk.name}', flush=True)
    def commit_feed():
        # Compare-and-swap protects concurrent feed publication; no upload can expose a partial feed.
        current, _ = client.feed()
        if current['sha'] != original['sha']:
            raise ValueError('Gitee feed changed during upload; retry to revalidate')
        if old != manifest:
            content = base64.b64encode((json.dumps(manifest, indent=2) + '\n').encode()).decode()
            client.api('/contents/channels/debug.json', 'PUT', json=dict(
                branch=BRANCH, sha=original['sha'], content=content,
                message=f'Publish testing build {policy["versionCode"]}'))
        print(f'Published Gitee debug version {policy["versionCode"]}: https://gitee.com/{REPOSITORY}/releases/tag/{tag}', flush=True)
    if advance_feed:
        commit_feed()
    return commit_feed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', type=int, help='Require this exact next version')
    parser.add_argument('--resume', action='store_true', help='Reuse validated APKs without rebuilding')
    parser.add_argument('--dry-run', action='store_true', help='Validate local APKs without network access')
    parser.add_argument('--token-file', type=Path,
                        default=Path(os.environ['GITEE_TOKEN_FILE']) if os.environ.get('GITEE_TOKEN_FILE') else None)
    args = parser.parse_args()
    lock_dir = ROOT / 'build'
    lock_dir.mkdir(exist_ok=True)
    with (lock_dir / '.apk-upload.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another APK upload is running in this checkout')
        marker = lock_dir / '.gitee-debug-built.json'
        if args.dry_run:
            common.prepare(ROOT, ('debug',))
            print('Validated all six local debug APKs.')
            return
        if not REPOSITORY or REPOSITORY.count('/') != 1:
            raise ValueError('Set WUMING_GITEE_REPOSITORY to your own owner/repository before publishing')
        token = os.environ.get('GITEE_TOKEN') or (args.token_file.read_text().strip() if args.token_file else '')
        if not token:
            raise ValueError('Set GITEE_TOKEN or GITEE_TOKEN_FILE')
        client = Gitee(token)
        if client.api()['private']:
            raise ValueError('Update repository must be public')
        policy = json.loads((ROOT / 'updates.json').read_text())
        if marker.exists():
            state = json.loads(marker.read_text())
            if state['code'] != policy['versionCode'] or (args.version and args.version != state['code']):
                raise ValueError('Pending Gitee upload differs from requested/local version')
            print(f'Resuming completed build {state["code"]}.', flush=True)
        elif not args.resume:
            _, feed = client.feed()
            code = choose_version(policy['versionCode'], client.releases(), feed, args.version)
            common.build_next_version(ROOT, ('debug',), common.MODES, next_code=code)
            # Mark only a successfully built, validated set; a build failure must rebuild.
            marker.write_text(json.dumps({'code': code}) + '\n')
        elif args.version and args.version != policy['versionCode']:
            raise ValueError('--resume version must match updates.json')
        policy, channels = common.prepare(ROOT, ('debug',))
        publish(client, policy, channels)
        marker.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, requests.RequestException, subprocess.CalledProcessError) as error:
        # requests errors can include signed URLs; only report their type.
        detail = type(error).__name__ if isinstance(error, requests.RequestException) else str(error)
        print(f'Gitee upload failed: {detail}', file=sys.stderr)
        sys.exit(1)
