#!/usr/bin/env python3
"""Play a resolved provider result using the distribution's real mpv command builder.

Keep the provider host alive when the result points at its loopback proxy. Inputs
and logs may contain signed URLs; keep them outside version control.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--playback-json', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=20)
    parser.add_argument('--timeout', type=int, default=80)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 120 or not args.seconds < args.timeout <= 300:
        parser.error('Use 1–120 playback seconds and a longer timeout of at most 300 seconds')
    playback = json.loads(args.playback_json.read_text())
    if playback.get('parse', 0) != 0 or not isinstance(playback.get('url'), str):
        parser.error('The input must be a resolved playback result with parse=0 and a URL')
    headers = playback.get('header') or {}
    if isinstance(headers, str):
        headers = json.loads(headers)
    if not isinstance(headers, dict) or any(not isinstance(v, str) for v in headers.values()):
        parser.error('Playback headers must be a string map')
    args.output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.pop('LD_PRELOAD', None)
    # Do not recreate mpv header encoding here. In particular, its comma-separated
    # generic header option corrupts browser User-Agents containing "KHTML, like Gecko".
    bridge = '''
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.wuming.tv.desktop.player.ExternalPlayerAdapter;
import com.wuming.tv.desktop.player.PlayerProcessBuilderKt;
class PlaybackCommand {
    public static void main(String[] args) throws Exception {
        var input = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Map<String,String> headers = new LinkedHashMap<>();
        input.getAsJsonObject("headers").entrySet().forEach(e -> headers.put(e.getKey(), e.getValue().getAsString()));
        var adapter = new ExternalPlayerAdapter(Path.of("mpv"), command -> null);
        var method = Arrays.stream(ExternalPlayerAdapter.class.getMethods())
            .filter(m -> m.getName().startsWith("command$") && m.getParameterCount() == 6)
            .findFirst().orElseThrow();
        var command = (List<String>) method.invoke(adapter,
            input.get("url").getAsString(), headers, null, null, 1.0, false);
        var builder = PlayerProcessBuilderKt.playerProcessBuilder(command);
        System.out.println(new Gson().toJson(Map.of("command", command, "environment", builder.environment())));
    }
}
'''
    with tempfile.TemporaryDirectory(prefix='wuming-playback-command-') as directory:
        root = Path(directory)
        source = root / 'PlaybackCommand.java'
        source.write_text(bridge)
        payload = root / 'input.json'
        payload.write_text(json.dumps({'url': playback['url'], 'headers': headers}))
        built = subprocess.run(
            ['java', '--class-path', str(args.distribution.resolve() / 'lib' / '*'),
             str(source), str(payload)], check=True, capture_output=True, text=True,
            timeout=30, env=env,
        )
        launch = json.loads(built.stdout)
        command = launch["command"]
        env = launch["environment"]
    command[1:1] = ['--no-config', '--ytdl=no', f'--length={args.seconds}',
                    '--network-timeout=20', '--log-file=' + str(args.output.resolve() / 'mpv.log')]
    with (args.output / 'console.log').open('w') as log:
        try:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    timeout=args.timeout, env=env)
            exit_code = result.returncode
        except subprocess.TimeoutExpired:
            exit_code = 124
    log = (args.output / 'mpv.log').read_text()
    # Exit status alone can report success without decoding any media.
    video = 'VO: [' in log
    audio = 'AO: [' in log
    failed = 'Unable to open key file' in log or 'HTTP error 400' in log
    summary = {'exitCode': exit_code, 'videoOutput': video,
               'audioOutput': audio, 'keyOrHttp400Error': failed,
               'requestedSeconds': args.seconds}
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))
    return 0 if exit_code == 0 and video and audio and not failed else 1


if __name__ == '__main__':
    raise SystemExit(main())
