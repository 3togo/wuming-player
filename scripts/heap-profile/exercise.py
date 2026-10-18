#!/usr/bin/env python3
"""Exercise explicit production navigation routes, returning to Home before each heap.
No installs, data clears, logins, preference edits or content deletion are performed.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--mode', choices=['mobile', 'tv'], required=True)
parser.add_argument('--label', required=True)
parser.add_argument('--playback-url', help='Optional controlled media fixture instead of navigation routes')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--cycles', type=int, default=10)
parser.add_argument('--tv-initial-index', type=int, default=0, help='Observed focused Home navigation item; VOD=0')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
package = 'com.fongmi.android.tv'
log = (args.output / (args.label + '-exercise.jsonl')).open('w')

def adb(*parts, timeout=40):
    result = subprocess.run(['adb', '-s', args.serial, *parts], text=True, capture_output=True, timeout=timeout)
    if result.returncode: raise RuntimeError(result.stderr or result.stdout)
    return result.stdout

def record(**values):
    print(json.dumps({'time': time.time(), **values}), file=log, flush=True)

def activity():
    data = adb('shell', 'dumpsys', 'activity', 'activities')
    for line in data.splitlines():
        if 'topResumedActivity=' in line or 'mResumedActivity:' in line:
            match = re.search(r'com\.fongmi\.android\.tv/([^\s}]+)', line)
            if match: return match.group(1).split('.')[-1]
    return None

def home():
    for attempt in range(4):
        if activity() == 'HomeActivity': return
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(.7)
    raise RuntimeError('Back did not return to Home; refusing to navigate an unexpected screen')

def capture(stage, at_home=True):
    if at_home: home()
    time.sleep(12)
    expected = 'HomeActivity' if at_home else 'VideoActivity'
    if activity() != expected:
        raise RuntimeError('Foreground changed while settling for ' + stage)
    current_pid = adb('shell', 'pidof', package).strip()
    if current_pid != pid: raise RuntimeError('Process changed during exercise')
    name = args.label + '-' + stage
    remote = '/data/local/tmp/' + name + '.hprof'
    record(event='dump_start', stage=stage, pid=pid)
    adb('shell', 'am', 'dumpheap', '-g', package, remote, timeout=60)
    size = ''
    for attempt in range(30):
        time.sleep(1)
        latest = adb('shell', 'stat', '-c', '%s', remote).strip()
        if latest == size and int(latest) > 1024: break
        size = latest
    else: raise RuntimeError('Heap dump did not stabilize')
    adb('pull', remote, str(args.output / (name + '.hprof')), timeout=60)
    adb('shell', 'rm', remote)
    if activity() != expected:
        raise RuntimeError('Foreground changed during heap capture: ' + stage)
    (args.output / (name + '-meminfo.txt')).write_text(adb('shell', 'dumpsys', 'meminfo', package))
    record(event='dump_complete', stage=stage, bytes=int(size), pid=pid)
    print(name + ' captured', flush=True)

home()
pid = adb('shell', 'pidof', package).strip()
record(event='start', serial=args.serial, mode=args.mode, pid=pid)
capture('baseline')
tv_position = args.tv_initial_index
for cycle in range(1, args.cycles + 1):
    if args.playback_url:
        home()
        adb('shell', 'am', 'start', '-W', '-n', package + '/.ui.activity.HomeActivity',
            '-a', 'android.intent.action.VIEW', '-d', args.playback_url, '-t', 'video/mp4')
        # Low-end TVs need longer to initialize a decoder after a cold start.
        deadline = time.monotonic() + 25
        while True:
            time.sleep(1)
            actual = activity()
            sessions = adb('shell', 'dumpsys', 'media_session')
            owned = sessions.split('package=' + package + '\n', 1)[-1] if 'package=' + package + '\n' in sessions else ''
            owned = owned.split('package=', 1)[0]
            state = next((line.strip() for line in owned.splitlines() if 'state=PlaybackState' in line), '')
            playing = 'state=PLAYING(3)' in state or 'state=3,' in state
            if (actual == 'VideoActivity' and playing) or time.monotonic() >= deadline: break
        record(event='playback', cycle=cycle, actual=actual, state=state, playing=playing)
        if actual != 'VideoActivity' or not playing:
            raise RuntimeError('Fixture is not playing in production VideoActivity')
        time.sleep(5)
        if cycle == 1: capture('active', at_home=False)
        home()
    else:
        routes = [('SearchActivity', 2), ('KeepActivity', 3), ('SettingActivity', 6), ('VodActivity', 0)] if args.mode == 'tv' else [
            ('SearchActivity', (1960, 142)), ('KeepActivity', (2084, 142)), ('HistoryActivity', (2208, 142))]
        for expected, target in routes:
            home()
            if args.mode == 'tv':
                # TV focus changes are processed asynchronously: yield a frame between keys.
                delta = target - tv_position
                keys = [22 if delta > 0 else 21] * abs(delta) + [23]
                tv_position = target
                command = 'for key in ' + ' '.join(map(str, keys)) + '; do input keyevent "$key"; sleep 0.15; done'
                adb('shell', command)
            else:
                adb('shell', 'input', 'tap', *map(str, target))
            time.sleep(1.2)
            actual = activity()
            record(event='visit', cycle=cycle, expected=expected, actual=actual)
            if actual != expected: raise RuntimeError('Unexpected navigation: ' + str(actual) + ', expected ' + expected)
            home()
    print(args.label + ': cycle ' + str(cycle), flush=True)
    if cycle in {5, args.cycles}: capture('cycle' + str(cycle))
log.close()
