#!/usr/bin/env python3
"""Validate APKs and publish immutable testing releases, then advance the update channels."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import runpy
import os
from pathlib import Path
import re
import subprocess
import sys
import shutil
import tempfile
import fcntl

ROOT = Path(__file__).resolve().parent.parent
MODES = ('mobile', 'leanback', 'leanback_lite')
ABIS = ('arm64_v8a', 'armeabi_v7a')
PACKAGE = runpy.run_path(str(ROOT / 'scripts/edition-config.py'))['application_id'](ROOT)


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def version_stamp(root):
    """Return the 12-char version stamp: YYYYMMDD (8) + first 4 chars of commit hash."""
    date = run('git', '-C', str(root), 'show', '-s', '--format=%cd', '--date=format:%Y%m%d', 'HEAD')
    full = run('git', '-C', str(root), 'rev-parse', 'HEAD')
    return date + full[:4]


def api(path, data=None):
    args = ['gh', 'api', path]
    if data is not None:
        args += ['--method', 'POST', '--input', '-']
    output = run(*args, input=json.dumps(data) if data is not None else None)
    return json.loads(output) if output else None


def build_tools():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        local = ROOT / 'local.properties'
        for line in local.read_text().splitlines() if local.exists() else []:
            if line.startswith('sdk.dir='):
                sdk = line.split('=', 1)[1]
    if not sdk:
        raise ValueError('Set ANDROID_HOME to the Android SDK')
    folders = sorted((Path(sdk) / 'build-tools').iterdir(), key=lambda p: tuple(int(v) for v in re.findall(r'\d+', p.name)))
    return next(p for p in reversed(folders) if (p / 'apksigner').is_file() and (p / 'aapt2').is_file())


def inspect(apk, tools, expected_certificate):
    signature = run(str(tools / 'apksigner'), 'verify', '--print-certs', str(apk))
    certificates = {value.lower() for value in re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', signature)}
    if certificates != {expected_certificate}:
        raise ValueError(f'{apk.name}: signing certificate differs from the project identity')
    info = run(str(tools / 'aapt2'), 'dump', 'badging', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
    if not package or package[1] != PACKAGE:
        raise ValueError(f'{apk.name}: unexpected application ID')
    return int(package[2]), package[3], 'application-debuggable' in info


def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(root=ROOT, selected_channels=('debug', 'release'), modes=MODES, build_identity=None):
    policy = json.loads((root / 'updates.json').read_text())
    if run('git', '-C', str(root), 'status', '--porcelain'):
        print('Note: checkout has local changes; validating the built APKs directly.', file=sys.stderr)
    if build_identity is not None:
        if build_identity['versionCode'] != policy['versionCode']:
            raise ValueError('Recorded build version differs from updates.json')
        commit, stamp = build_identity['commit'], build_identity['stamp']
        if not re.fullmatch(r'[0-9a-f]{40}', commit) or not re.fullmatch(r'\d{8}[0-9a-f]{4}', stamp):
            raise ValueError('Invalid recorded build identity')
        if stamp[8:] != commit[:4]:
            raise ValueError('Recorded build stamp differs from commit')
    else:
        commit = run('git', '-C', str(root), 'rev-parse', 'HEAD')
        stamp = version_stamp(root)
    tools = build_tools()
    result = {}
    for channel in selected_channels:
        tag = f'testing-{policy["versionCode"]}-{channel}-{stamp}'
        manifest = dict(schema=1, channel=channel, code=policy['versionCode'],
                        name=policy['versionName'], desc='Testing build', commit=commit, artifacts=[])
        paths = []
        for mode in modes:
            for abi in ABIS:
                name = f'{mode}-{abi}-{stamp}.apk'
                apk = root / channel / name
                print(f'Validating {channel}: {name}', flush=True)
                code, version, debuggable = inspect(apk, tools, policy['certificateSha256'])
                if code != policy['versionCode'] or version != f'{policy["versionName"]}({stamp})' or debuggable != (channel == 'debug'):
                    raise ValueError(f'{apk}: wrong version or build channel')
                manifest['artifacts'].append(dict(mode=mode, abi=abi, packageName=PACKAGE,
                    url=f'https://github.com/{policy["repository"]}/releases/download/{tag}/{name}',
                    sha256=checksum(apk),
                    certificateSha256=policy['certificateSha256']))
                paths.append(apk)
        result[channel] = (tag, manifest, paths)
    return policy, result


def next_version(policy):
    """Read every release page using API flags supported by older GitHub CLIs."""
    highest = policy['versionCode']
    page = 1
    while True:
        releases = api(f'repos/{policy["repository"]}/releases?per_page=100&page={page}')
        for release in releases:
            match = re.fullmatch(r'testing-(\d+)-(?:debug|release)-[0-9a-f]+', release['tag_name'])
            if match:
                highest = max(highest, int(match[1]))
        if len(releases) < 100:
            break
        page += 1
    if highest >= 2100000000:
        raise ValueError('Android versionCode limit reached')
    return highest + 1


def build_next_version(root, selected_channels, modes, allow_dirty=False, next_code=None):
    # Fail before remote version lookup or any expensive Gradle work. Keep the
    # Gradle guard too, since the checkout can change after this preflight.
    if 'release' in selected_channels and not allow_dirty:
        if run('git', '-C', str(root), 'status', '--porcelain'):
            raise ValueError(
                'Release builds require a clean working tree (the default includes release). '
                'Commit your changes first, rerun scripts/upload-apks.sh with --allow-dirty '
                'to build these local changes, or select --debug for debug only. '
                'No build or upload was started.')
    policy_path = root / 'updates.json'
    policy = json.loads(policy_path.read_text())
    # Include drafts and partial uploads, which may be ahead of the live feeds.
    next_code = next_version(policy) if next_code is None else next_code
    if not policy['versionCode'] < next_code <= 2100000000:
        raise ValueError('Build version must increase and remain within Android limits')
    tasks = [f':app:assemble{mode[0].upper() + mode[1:]}{abi[0].upper() + abi[1:]}{channel.title()}'
             for channel in selected_channels for mode in modes for abi in ABIS]
    print(f'Building version {next_code}: ' + ', '.join(tasks), flush=True)
    # Override only the build version, so release source-cleanliness checks remain valid.
    # Write updates.json only after successful assembly; a failed build leaves it unchanged.
    gradle_args = [str(root / 'gradlew'), *tasks,
                    f'-PwumingUploadVersionCode={next_code}', '--no-configuration-cache',
                    '--console=plain']
    if allow_dirty:
        gradle_args.append('-PallowDirtyRelease=true')
    subprocess.run(gradle_args, cwd=root, check=True)
    stamp = version_stamp(root)
    tools = build_tools()
    copies = []
    for channel in selected_channels:
        for mode in modes:
            for abi in ABIS:
                name = f'{mode}-{abi}-{stamp}.apk'
                matches = list((root / 'app/build/outputs/apk').glob(f'**/{channel}/{name}'))
                if len(matches) != 1:
                    raise ValueError(f'Expected exactly one freshly built {name} for {channel}')
                code, version, debug = inspect(matches[0], tools, policy['certificateSha256'])
                if (code, version, debug) != (next_code, f'{policy["versionName"]}({stamp})', channel == 'debug'):
                    raise ValueError(f'{name}: build did not produce the requested version')
                copies.append((matches[0], root / channel / name))
    for source, destination in copies:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    policy['versionCode'] = next_code
    fd, temporary = tempfile.mkstemp(prefix='.updates-', dir=root)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(policy, out, indent=2)
            out.write('\n')
        os.chmod(temporary, policy_path.stat().st_mode & 0o777)
        os.replace(temporary, policy_path)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(f'Updated updates.json versionCode to {next_code}.', flush=True)


def validate_advance(old, new):
    if old['code'] > new['code'] or (old['code'] == new['code'] and old != new):
        raise ValueError('Increment versionCode in updates.json before publishing different APKs')


def find_release(endpoint, tag):
    # GitHub's tag lookup excludes drafts. The authenticated release list includes them.
    page = 1
    while True:
        releases = api(endpoint + f'/releases?per_page=100&page={page}')
        for release in releases:
            if release['tag_name'] == tag:
                return release
        if len(releases) < 100:
            return None
        page += 1


def upload_github_assets(repository, tag, channel, paths, artifacts, remote_assets):
    assets = {asset['name']: asset for asset in remote_assets}
    pending = []
    # Preflight all existing assets before starting workers. Never overwrite a
    # published filename; a failed batch can resume only the missing APKs.
    for index, (apk, artifact) in enumerate(zip(paths, artifacts), 1):
        existing = assets.get(apk.name)
        if existing is None:
            pending.append((index, apk))
        elif (existing.get('digest') != 'sha256:' + artifact['sha256'] or
              existing['size'] != apk.stat().st_size):
            raise ValueError('Existing release asset differs; increment the version')
        else:
            print(f'Already uploaded [{index}/{len(paths)}]: {apk.name}', flush=True)

    def upload(item):
        index, apk = item
        print(f'Uploading {channel} [{index}/{len(paths)}]: {apk.name}', flush=True)
        run('gh', 'release', 'upload', tag, str(apk), '--repo', repository)
        print(f'Uploaded: {apk.name}', flush=True)

    # Three independent gh processes overlap network transfers without flooding
    # the connection. Join every worker before verification or feed publication.
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(upload, item) for item in pending]
        try:
            for future in as_completed(futures):
                future.result()
        except BaseException:
            for future in futures:
                future.cancel()
            raise


def publish(policy, channels, advance_feed=True):
    repository = policy['repository']
    if not repository or repository.count('/') != 1:
        raise ValueError('Configure your own update repository in updates.json before publishing')
    endpoint = f'repos/{repository}'
    print(f'Checking GitHub repository: {repository}', flush=True)
    remote = api(endpoint)
    if remote['private']:
        raise ValueError('The updater repository must be public')
    # Bootstrap only an empty repository; no public download instructions are created.
    if remote['size'] == 0:
        try:
            api(endpoint + '/git/ref/heads/main')
        except subprocess.CalledProcessError:
            run('gh', 'api', endpoint + '/contents/channels/.gitkeep', '--method', 'PUT', '--input', '-',
                input=json.dumps({'message': 'Initialize update channels', 'branch': 'main', 'content': ''}))
    head = api(endpoint + '/git/ref/heads/main')['object']['sha']
    tree = api(endpoint + '/git/commits/' + head)['tree']['sha']
    complete_channels = {}
    for channel, (tag, manifest, paths) in channels.items():
        print(f'Preparing {channel} publication: {tag}', flush=True)
        complete = {(a['mode'], a['abi']) for a in manifest['artifacts']} == {(m, a) for m in MODES for a in ABIS}
        try:
            existing = api(endpoint + '/contents/channels/' + channel + '.json?ref=main')
        except subprocess.CalledProcessError as error:
            if '404' not in error.stderr:
                raise
        else:
            old = json.loads(base64.b64decode(existing['content']))
            if complete:
                validate_advance(old, manifest)
            elif old['code'] > manifest['code']:
                raise ValueError('Cannot upload a version older than the live channel')
            elif old['code'] == manifest['code']:
                old_assets = {(a['mode'], a['abi']): a for a in old['artifacts']}
                if any(old_assets.get((a['mode'], a['abi'])) != a for a in manifest['artifacts']):
                    raise ValueError('Increment versionCode before publishing different APKs')
        release = find_release(endpoint, tag)
        if release is None:
            run('gh', 'release', 'create', tag, '--repo', repository, '--draft', '--prerelease',
                '--title', tag, '--notes', '', '--latest=false')
            release = find_release(endpoint, tag)
            if release is None:
                raise ValueError('Created draft is not visible yet; retry publication')
        if not release['prerelease']:
            raise ValueError('Expected a testing prerelease')
        upload_github_assets(repository, tag, channel, paths, manifest['artifacts'], release['assets'])
        # GitHub reports the digest computed by its upload service. Check it before exposing the channel.
        print(f'Verifying uploaded {channel} APK checksums and sizes...', flush=True)
        release = api(endpoint + '/releases/' + str(release['id']))
        assets = {asset['name']: asset for asset in release['assets']}
        for apk, artifact in zip(paths, manifest['artifacts']):
            if assets[apk.name].get('digest') != 'sha256:' + artifact['sha256'] or assets[apk.name]['size'] != apk.stat().st_size:
                raise ValueError('Uploaded APK checksum or size mismatch')
        if release['draft']:
            run('gh', 'release', 'edit', tag, '--repo', repository, '--draft=false', '--prerelease', '--latest=false')
        print(f'Verified {channel}: {len(paths)} signed APKs uploaded.', flush=True)
        if complete:
            complete_channels[channel] = (tag, manifest, paths)
        else:
            print(f'{channel} feed unchanged: publish all three modes together to advance this channel.')
    if not complete_channels:
        return
    def commit_feeds():
        print('Updating feeds: ' + ', '.join(complete_channels), flush=True)
        entries = [dict(path=f'channels/{channel}.json', mode='100644', type='blob',
                        content=json.dumps(manifest, indent=2) + '\n') for channel, (_, manifest, _) in complete_channels.items()]
        new_tree = api(endpoint + '/git/trees', {'base_tree': tree, 'tree': entries})['sha']
        commit = api(endpoint + '/git/commits', {'message': f'Publish testing build {policy["versionCode"]}',
                                              'tree': new_tree, 'parents': [head]})['sha']
        # A concurrent publisher causes a non-fast-forward failure instead of overwriting its channels.
        run('gh', 'api', endpoint + '/git/refs/heads/main', '--method', 'PATCH', '--input', '-',
            input=json.dumps({'sha': commit, 'force': False}))
        print('Updated feeds: ' + ', '.join(complete_channels), flush=True)
    if advance_feed:
        commit_feeds()
    return commit_feeds



if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', action='store_true', help='Upload verified APKs and advance both public update channels')
    parser.add_argument('--build-root', type=Path, default=ROOT, help='Checkout containing the built APKs (local changes are allowed)')
    parser.add_argument('--build-next-version', action='store_true', help='Increment version and build selected APKs before publishing')
    parser.add_argument('--allow-dirty', action='store_true', help='Allow release builds with uncommitted changes')
    channel = parser.add_mutually_exclusive_group()
    channel.add_argument('--debug', action='store_true')
    channel.add_argument('--release', action='store_true')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--leanback', action='store_true')
    mode.add_argument('--leanback-lite', action='store_true')
    mode.add_argument('--mobile', action='store_true')
    args = parser.parse_args()
    selected_channels = ('debug',) if args.debug else ('release',) if args.release else ('debug', 'release')
    modes = ('leanback',) if args.leanback else ('leanback_lite',) if args.leanback_lite else ('mobile',) if args.mobile else MODES
    try:
        root = args.build_root.resolve()
        # Serialize build/version/upload operations in this checkout.
        lock_dir = root / 'build'
        lock_dir.mkdir(exist_ok=True)
        upload_lock = (lock_dir / '.apk-upload.lock').open('a')
        try:
            fcntl.flock(upload_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another APK upload is running in this checkout')
        if args.build_next_version:
            if not args.publish:
                raise ValueError('--build-next-version requires --publish')
            build_next_version(root, selected_channels, modes, allow_dirty=args.allow_dirty)
        policy, channels = prepare(args.build_root.resolve(), selected_channels, modes)
        if args.publish:
            publish(policy, channels)
        else:
            print(f'Validated {sum(len(paths) for _, _, paths in channels.values())} APKs: package, channel, version, and pinned signing certificate.')
    except (ValueError, OSError) as error:
        parser.exit(1, f'Upload failed: {error}\n')
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or '').strip()
        parser.exit(1, f'Upload failed: {error.cmd[0]} exited with status {error.returncode}.\n'
                       + (detail + '\n' if detail else 'See the command output above for details.\n'))
