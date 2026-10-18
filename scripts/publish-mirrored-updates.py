#!/usr/bin/env python3
"""Build once, verify both GitHub/Gitee distributions, then advance their debug feeds."""
import argparse
import base64
import re
import copy
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('gitee_updates', ROOT / 'scripts/publish-gitee-updates.py')
gitee = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gitee)
common = gitee.common


def mirrored_channels(policy, channels):
    channels = copy.deepcopy(channels)
    for _, manifest, paths in channels.values():
        for artifact in manifest['artifacts']:
            github_url = artifact['url']
            gitee_url = github_url.replace(f'https://github.com/{policy["repository"]}/',
                                           f'https://gitee.com/{gitee.REPOSITORY}/', 1)
            artifact['mirrors'] = [github_url, gitee_url]
    return channels


def publish_both(client, policy, channels):
    channels = mirrored_channels(policy, channels)
    # Upload and verify BOTH copies before either feed can advertise the release.
    # Each final commit is optimistic. If one commit fails, --resume repairs it;
    # clients accept a lagging feed and both advertised APK copies already exist.
    github_commit = common.publish(policy, channels, advance_feed=False)
    gitee_commit = gitee.publish(client, policy, channels, advance_feed=False)
    github_commit()
    gitee_commit()



def retention_candidates(releases, live_code, protected_urls):
    """Keep three APK-bearing debug versions; leave stable/unknown/future releases alone."""
    eligible = []
    for release in releases:
        match = re.fullmatch(r'testing-(\d+)-debug-[0-9a-f]+', release['tag_name'])
        # Historical Gitee debug releases were not marked prerelease; the exact
        # channel tag is authoritative, not that host-specific presentation flag.
        if not match or release.get('draft'):
            continue
        code = int(match[1])
        apks = [asset for asset in release['assets'] if asset['name'].endswith('.apk')]
        if code <= live_code and apks:
            eligible.append((code, release, apks))
    keep = set(sorted({code for code, _, _ in eligible}, reverse=True)[:3])
    return [(release, asset) for code, release, apks in eligible if code not in keep
            for asset in apks if asset['browser_download_url'] not in protected_urls]


def prune_old_apks(client, policy):
    # Never reclaim space before BOTH feeds commit: a failed upload must leave
    # advertised APKs usable. Keep tags/metadata for monotonic version allocation.
    endpoint = f'repos/{policy["repository"]}'
    github_file = common.api(endpoint + '/contents/channels/debug.json?ref=main')
    github_feed = json.loads(base64.b64decode(github_file['content']))
    _, gitee_feed = client.feed()
    feeds = [github_feed, gitee_feed]
    if github_feed['code'] != gitee_feed['code']:
        raise ValueError('Retention requires both debug feeds at the same version; resume publication first')
    protected = set()
    for feed in feeds:
        if len(feed['artifacts']) != 6:
            raise ValueError('Retention requires a complete six-APK debug feed')
        for artifact in feed['artifacts']:
            protected.update([artifact['url']] + artifact.get('mirrors', []))
    releases = []
    page = 1
    while True:
        batch = common.api(endpoint + f'/releases?per_page=100&page={page}')
        releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    # Compute both plans before deleting anything. Only APK assets are removed;
    # unrelated attachments, release-channel builds and future uploads survive.
    plans = [(host, retention_candidates(items, github_feed['code'], protected))
             for host, items in [('GitHub', releases), ('Gitee', client.releases())]]
    # Gitee's release list omits attachment IDs; resolve all IDs before deleting.
    attachments = {}
    for host, assets in plans:
        if host != 'Gitee':
            continue
        for release, asset in assets:
            if 'id' in asset:
                continue
            rid = release['id']
            if rid not in attachments:
                items, page = [], 1
                while True:
                    batch = client.api(f'/releases/{rid}/attach_files?per_page=100&page={page}')
                    items.extend(batch)
                    if len(batch) < 100:
                        break
                    page += 1
                attachments[rid] = items
            matches = [a for a in attachments[rid] if a['name'] == asset['name']]
            if len(matches) != 1:
                raise ValueError('Cannot identify obsolete Gitee attachment unambiguously')
            asset['id'] = matches[0]['id']
    for host, assets in plans:
        for release, asset in assets:
            if host == 'GitHub':
                common.run('gh', 'api', endpoint + f'/releases/assets/{asset["id"]}', '--method', 'DELETE')
            else:
                client.api(f'/releases/{release["id"]}/attach_files/{asset["id"]}', 'DELETE')
            print(f'Retention {host}: removed {release["tag_name"]}/{asset["name"]}', flush=True)
        print(f'{host}: retained latest three debug APK versions ({len(assets)} old APKs removed).', flush=True)


def next_version(client, policy, requested=None):
    _, feed = client.feed()
    # Include GitHub drafts and partial releases as well as both local/Gitee state.
    github_highest = common.next_version(policy) - 1
    return gitee.choose_version(max(policy['versionCode'], github_highest), client.releases(), feed, requested)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', type=int)
    parser.add_argument('--cleanup-only', action='store_true', help='Keep the latest three debug APK versions on both hosts without building')
    parser.add_argument('--resume', action='store_true', help='Reuse existing signed APKs, never rebuild')
    parser.add_argument('--dry-run', action='store_true', help='Validate local APKs without contacting either host')
    parser.add_argument('--token-file', type=Path,
                        default=Path(os.environ['GITEE_TOKEN_FILE']) if os.environ.get('GITEE_TOKEN_FILE') else None)
    args = parser.parse_args()
    lock_dir = ROOT / 'build'
    lock_dir.mkdir(exist_ok=True)
    with (lock_dir / '.apk-upload.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another APK publisher is running in this checkout')
        receipt = lock_dir / '.mirrored-debug-identity.json'
        if args.dry_run:
            identity = json.loads(receipt.read_text()) if receipt.exists() else None
            common.prepare(ROOT, ('debug',), build_identity=identity)
            print('Validated six local signed debug APKs; no network calls.')
            return
        if not gitee.REPOSITORY or gitee.REPOSITORY.count('/') != 1:
            raise ValueError('Set WUMING_GITEE_REPOSITORY to your own owner/repository before publishing')
        common.run('gh', 'auth', 'status', '--hostname', 'github.com')
        token = os.environ.get('GITEE_TOKEN') or (args.token_file.read_text().strip() if args.token_file else '')
        if not token:
            raise ValueError('Set GITEE_TOKEN or GITEE_TOKEN_FILE')
        client = gitee.Gitee(token)
        if client.api()['private']:
            raise ValueError('Gitee update repository must be public')
        policy = json.loads((ROOT / 'updates.json').read_text())
        if args.cleanup_only:
            prune_old_apks(client, policy)
            return
        marker = lock_dir / '.mirrored-debug-built.json'
        identity = json.loads(receipt.read_text()) if args.resume and receipt.exists() else None
        if marker.exists():
            state = json.loads(marker.read_text())
            if state['code'] != policy['versionCode'] or (args.version and state['code'] != args.version):
                raise ValueError('Pending mirrored publication differs from requested/local version')
            identity = state.get('identity')
            if identity is None:
                raise ValueError('Pending build lacks its original identity; recover it before resuming')
            print(f'Resuming mirrored publication {state["code"]}.', flush=True)
        elif not args.resume:
            code = next_version(client, policy, args.version)
            identity = dict(versionCode=code, commit=common.run('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
                            stamp=common.version_stamp(ROOT))
            common.build_next_version(ROOT, ('debug',), common.MODES, next_code=code)
            if common.run('git', '-C', str(ROOT), 'rev-parse', 'HEAD') != identity['commit']:
                raise ValueError('Source commit changed during build; do not publish ambiguous artifacts')
            marker.write_text(json.dumps({'code': code, 'identity': identity}) + '\n')
        elif args.version and args.version != policy['versionCode']:
            raise ValueError('--resume version must match updates.json')
        if identity is not None:
            receipt.write_text(json.dumps(identity) + '\n')
        policy, channels = common.prepare(ROOT, ('debug',), build_identity=identity)
        publish_both(client, policy, channels)
        prune_old_apks(client, policy)
        marker.unlink(missing_ok=True)
        print(f'Both hosts now serve debug version {policy["versionCode"]}.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, gitee.requests.RequestException) as error:
        if isinstance(error, gitee.requests.RequestException):
            detail = type(error).__name__
        elif isinstance(error, subprocess.CalledProcessError):
            detail = f'{error.cmd[0]} exited {error.returncode}: {(error.stderr or "").strip()}'
        else:
            detail = str(error)
        print(f'Mirrored publication failed: {detail}', file=sys.stderr)
        sys.exit(1)
