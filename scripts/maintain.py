#!/usr/bin/env python3
"""Daily maintenance GUI for the 無名 TV repo.

Actions:
  1. Build APKs                 (any mode x ABI x debug/release/verification)
  2. Update depot.json          (scrape READMEs, then validate every source)
  3. Post APKs                  (scripts/upload-apks.sh -> GitHub / update feeds)
  4. Connect ADB device         (~/.bin/adbconnect)
  5. Install APK to device      (~/.bin/install-apk, run from the repo root)
  6. Manage & test links        (screening report, overrides, offline catalog)

Run from anywhere: python3 scripts/maintain.py
"""

import json
import os
import pathlib
import queue
import shlex
import shutil
import signal
import subprocess
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]

# Variant tokens mirror ~/.bin/install-apk (m64 / meabi / l64 / leabi) and add the lite
# flavor. Values are the Gradle flavor names; concatenated in mode-then-abi order with the
# capitalized build type they form the assemble task, e.g. Leanback_lite + Arm64_v8a + Debug.
VARIANT_TOKENS = {
    'm64': ('Mobile', 'Arm64_v8a'),
    'mobile64': ('Mobile', 'Arm64_v8a'),
    'meabi': ('Mobile', 'Armeabi_v7a'),
    'mobileeabi': ('Mobile', 'Armeabi_v7a'),
    'l64': ('Leanback', 'Arm64_v8a'),
    'leanback64': ('Leanback', 'Arm64_v8a'),
    'leabi': ('Leanback', 'Armeabi_v7a'),
    'leanbackeabi': ('Leanback', 'Armeabi_v7a'),
    'll64': ('Leanback_lite', 'Arm64_v8a'),
    'lite64': ('Leanback_lite', 'Arm64_v8a'),
    'lleabi': ('Leanback_lite', 'Armeabi_v7a'),
    'liteeabi': ('Leanback_lite', 'Armeabi_v7a'),
}
GROUP_TOKENS = {
    'mobile': ('m64', 'meabi'),
    'leanback': ('l64', 'leabi'),
    'leanback_lite': ('ll64', 'lleabi'),
    'lite': ('ll64', 'lleabi'),
    'all': ('m64', 'meabi', 'l64', 'leabi', 'll64', 'lleabi'),
}
BUILD_TYPES = ('debug', 'release', 'verification')
DEFAULT_VARIANTS = 'm64 leabi'


def assemble_tasks(variants, build_type):
    """Expand a whitespace/comma separated variant selection into Gradle task names."""
    if build_type not in BUILD_TYPES:
        raise ValueError(f'Unknown build type {build_type!r}.')
    chosen, unknown = [], []
    for token in variants.lower().replace(',', ' ').replace('+', ' ').split():
        for name in GROUP_TOKENS.get(token, (token,)):
            pair = VARIANT_TOKENS.get(name)
            if pair is None:
                if name not in unknown:
                    unknown.append(name)
            elif pair not in chosen:
                chosen.append(pair)
    if unknown:
        valid = ', '.join(sorted(GROUP_TOKENS) + sorted(VARIANT_TOKENS))
        raise ValueError(f'Unknown variant(s): {", ".join(unknown)}.\nValid: {valid}')
    if not chosen:
        raise ValueError(f'No variant selected. Try "{DEFAULT_VARIANTS}" or "all".')
    suffix = build_type.capitalize()
    return [f':app:assemble{mode}{abi}{suffix}' for mode, abi in chosen]


class Runner:
    """Runs one subprocess job, streaming merged output into a queue."""

    def __init__(self, out_queue):
        self.queue = out_queue
        self.process = None

    @property
    def running(self):
        return self.process is not None and self.process.poll() is None

    def start(self, argv, title, interactive=False):
        if self.running:
            return False
        env = dict(os.environ, TERM='dumb', PYTHONUNBUFFERED='1')
        self.queue.put(('info', f'=== {title} ===\n$ {" ".join(argv)}\n\n'))

        def pump():
            try:
                self.process = subprocess.Popen(
                    argv, cwd=REPO_ROOT, stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT, text=True, bufsize=1,
                    stdin=subprocess.PIPE if interactive else None,
                    start_new_session=True, env=env)
            except OSError as error:
                self.queue.put(('info', f'Cannot start: {error}\n'))
                self.queue.put(('done', 1))
                return
            for line in self.process.stdout:
                self.queue.put(('out', line))
            self.queue.put(('done', self.process.wait()))

        threading.Thread(target=pump, daemon=True).start()
        return True

    def send_input(self, line):
        if self.running and self.process.stdin is not None:
            self.process.stdin.write(line + '\n')
            self.process.stdin.flush()
            return True
        return False

    def stop(self):
        if self.running:
            os.killpg(os.getpgid(self.process.pid), signal.SIGTERM)


class MaintainApp:
    def __init__(self, root):
        self.root = root
        root.title('無名 daily maintenance')
        root.geometry('920x680')
        self.messages = queue.Queue()
        self.runner = Runner(self.messages)
        self._build_widgets(root)
        self.refresh_status()
        root.after(120, self._drain)

    # ------------------------------------------------------------ widgets
    def _build_widgets(self, root):
        status = ttk.LabelFrame(root, text='Status')
        status.pack(fill='x', padx=10, pady=(10, 4))
        self.status_text = ttk.Label(status, text='', justify='left')
        self.status_text.pack(fill='x', padx=8, pady=4)
        ttk.Button(status, text='Refresh', command=self.refresh_status).pack(
            anchor='e', padx=8, pady=(0, 4))

        actions = ttk.LabelFrame(root, text='Daily maintenance')
        actions.pack(fill='x', padx=10, pady=4)

        row1 = ttk.Frame(actions)
        row1.pack(fill='x', padx=8, pady=4)
        ttk.Button(row1, text='1. Build APKs', width=30,
                   command=self.build_apks).pack(side='left')
        ttk.Label(row1, text='variants:').pack(side='left', padx=(8, 2))
        self.build_variants = ttk.Entry(row1, width=26)
        self.build_variants.insert(0, DEFAULT_VARIANTS)
        self.build_variants.pack(side='left')

        row2 = ttk.Frame(actions)
        row2.pack(fill='x', padx=8, pady=4)
        ttk.Label(row2, text='type:').pack(side='left')
        self.build_type = tk.StringVar(value='debug')
        for build_type in BUILD_TYPES:
            ttk.Radiobutton(row2, text=build_type, value=build_type,
                            variable=self.build_type).pack(side='left', padx=2)
        self.dirty_release = tk.BooleanVar()
        ttk.Checkbutton(row2, text='allow dirty tree (-PallowDirtyRelease)',
                        variable=self.dirty_release).pack(side='left', padx=8)
        ttk.Label(row2, text='m64 meabi l64 leabi ll64 lleabi · mobile leanback lite · all',
                  foreground='#666').pack(side='left', padx=8)

        row3 = ttk.Frame(actions)
        row3.pack(fill='x', padx=8, pady=4)
        ttk.Button(row3, text='2. Update depot.json', width=30,
                   command=self.update_depot).pack(side='left')
        self.refresh_readmes = tk.BooleanVar()
        ttk.Checkbutton(row3, text='force re-fetch READMEs (--refresh)',
                        variable=self.refresh_readmes).pack(side='left', padx=8)
        ttk.Button(row3, text='Preview text links',
                   command=lambda: self.import_depot_links(False)).pack(side='left', padx=8)
        ttk.Button(row3, text='Merge text links',
                   command=lambda: self.import_depot_links(True)).pack(side='left', padx=2)

        row3b = ttk.Frame(actions)
        row3b.pack(fill='x', padx=8, pady=4)
        ttk.Button(row3b, text='Screen + build offline links', width=30,
                   command=self.build_offline_links).pack(side='left')
        ttk.Button(row3b, text='Manage & test links…',
                   command=self.manage_links).pack(side='left', padx=8)
        ttk.Label(row3b, text='Browse verdicts, test, rename, and rebuild',
                  foreground='#666').pack(side='left')

        row4 = ttk.Frame(actions)
        row4.pack(fill='x', padx=8, pady=4)
        ttk.Button(row4, text='3. Post APKs', width=30,
                   command=self.post_apks).pack(side='left')
        self.post_channel = tk.StringVar(value='debug')
        for text, value in (('debug', 'debug'), ('release', 'release'),
                            ('both', 'both')):
            ttk.Radiobutton(row4, text=text, value=value,
                            variable=self.post_channel).pack(side='left', padx=2)
        self.post_dry_run = tk.BooleanVar(value=True)
        ttk.Checkbutton(row4, text='dry-run only',
                        variable=self.post_dry_run).pack(side='left', padx=8)
        ttk.Label(row4, text='extra args:').pack(side='left', padx=(8, 2))
        self.post_extra = ttk.Entry(row4, width=24)
        self.post_extra.pack(side='left')

        row5 = ttk.Frame(actions)
        row5.pack(fill='x', padx=8, pady=4)
        ttk.Button(row5, text='4. Connect ADB device', width=30,
                   command=self.connect_device).pack(side='left')
        ttk.Label(row5, text='~/.bin/adbconnect — "all" scans every local network').pack(
            side='left', padx=8)

        row6 = ttk.Frame(actions)
        row6.pack(fill='x', padx=8, pady=4)
        ttk.Button(row6, text='5. Install APK to device', width=30,
                   command=self.install_apk).pack(side='left')
        ttk.Label(row6, text='~/.bin/install-apk (latest debug APK, per-device ABI)').pack(
            side='left', padx=8)

        logbar = ttk.Frame(root)
        logbar.pack(fill='x', padx=10, pady=(4, 0))
        self.stop_button = ttk.Button(logbar, text='Stop', command=self.runner.stop,
                                      state='disabled')
        self.stop_button.pack(side='left')
        self.job_label = ttk.Label(logbar, text='idle')
        self.job_label.pack(side='left', padx=10)
        self.input_entry = ttk.Entry(logbar, width=32, state='disabled')
        self.input_entry.pack(side='left', padx=4)
        self.input_entry.bind('<Return>', self._send_input)
        self.send_button = ttk.Button(logbar, text='Send', width=6,
                                      command=self._send_input, state='disabled')
        self.send_button.pack(side='left')
        ttk.Button(logbar, text='Clear log', command=self._clear_log).pack(side='right')

        container = ttk.Frame(root)
        container.pack(fill='both', expand=True, padx=10, pady=(0, 10))
        self.log = tk.Text(container, wrap='none', state='disabled',
                           font=('DejaVu Sans Mono', 10))
        scroll = ttk.Scrollbar(container, command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side='left', fill='both', expand=True)
        scroll.pack(side='right', fill='y')

    # ------------------------------------------------------------ status
    def refresh_status(self):
        lines = []
        branch = self._capture(['git', 'rev-parse', '--abbrev-ref', 'HEAD'])
        dirty = self._capture(['git', 'status', '--porcelain'])
        lines.append(f'branch: {branch or "?"}   working tree: '
                     f'{"DIRTY (%d paths)" % len(dirty.splitlines()) if dirty else "clean"}')
        try:
            policy = json.loads((REPO_ROOT / 'updates.json').read_text())
            lines.append(f'updates.json: versionName={policy.get("versionName")} '
                         f'versionCode={policy.get("versionCode")} '
                         f'repo={policy.get("repository")}')
        except (OSError, ValueError) as error:
            lines.append(f'updates.json: unreadable ({error})')
        depot = REPO_ROOT / 'app/src/main/assets/depot.json'
        try:
            data = json.loads(depot.read_text())
            stamp = time.strftime('%Y-%m-%d %H:%M', time.localtime(depot.stat().st_mtime))
            lines.append(f'depot.json: vod={len(data.get("vod", []))} '
                         f'live={len(data.get("live", []))}  updated {stamp}')
        except (OSError, ValueError) as error:
            lines.append(f'depot.json: unreadable ({error})')
        for channel in ('debug', 'release'):
            directory = REPO_ROOT / channel
            apks = sorted(directory.glob('*.apk')) if directory.is_dir() else []
            newest = apks[-1].name if apks else '-'
            lines.append(f'{channel}/: {len(apks)} apk(s), newest {newest}')
        self.status_text.configure(text='\n'.join(lines))

    def _capture(self, argv):
        try:
            done = subprocess.run(argv, cwd=REPO_ROOT, capture_output=True, text=True)
            return done.stdout.strip() if done.returncode == 0 else ''
        except OSError:
            return ''

    # ------------------------------------------------------------ actions
    def build_apks(self):
        if not self._java_ok():
            return
        build_type = self.build_type.get()
        try:
            tasks = assemble_tasks(self.build_variants.get(), build_type)
        except ValueError as error:
            messagebox.showerror('Bad variant selection', str(error))
            return
        argv = ['./gradlew-safe'] + tasks
        if build_type == 'release' and self.dirty_release.get():
            argv.append('-PallowDirtyRelease=true')
        self._start(argv, f'Build {len(tasks)} {build_type} APK(s)')

    def update_depot(self):
        argv = ['python3', 'scripts/depot/update_depot.py']
        if self.refresh_readmes.get():
            argv.append('--refresh')
        self._start(argv, 'Update depot.json')

    def import_depot_links(self, apply):
        path = filedialog.askopenfilename(
            title='Select a text link collection',
            filetypes=[('Text files', '*.txt'), ('All files', '*')])
        if not path:
            return
        argv = ['python3', '-u', 'scripts/depot/import_links.py', path]
        if apply:
            argv.append('--apply')
        self._start(argv, 'Merge text links' if apply else 'Preview text links')

    def build_offline_links(self):
        path = filedialog.askopenfilename(
            title='Select a text link collection',
            filetypes=[('Text files', '*.txt'), ('All files', '*')])
        if path:
            self._start(['python3', '-u', 'scripts/depot/build_link_catalog.py',
                         '--screen', path], 'Screen and build offline links')

    def manage_links(self):
        from depot.link_manager_gui import open_link_manager
        open_link_manager(self.root)

    def post_apks(self):
        try:
            extra = shlex.split(self.post_extra.get())
        except ValueError as error:
            messagebox.showerror('Bad extra args', str(error))
            return
        argv = ['bash', 'scripts/upload-apks.sh']
        if self.post_channel.get() != 'both':
            argv.append(f'--{self.post_channel.get()}')
        argv += extra
        if self.post_dry_run.get():
            argv.append('--dry-run')
        elif not (messagebox.askyesno(
                'Confirm publish',
                'This runs git push and publishes to GitHub / update feeds:\n\n'
                f'$ {" ".join(argv)}\n\nContinue?')
                and simpledialog.askstring(
                    'Confirm publish',
                    'Type PUBLISH to run the command above (push + release upload):')
                == 'PUBLISH'):
            self.messages.put(('info', 'Publish cancelled; nothing was run.\n'))
            return
        self._start(argv, 'Post APKs')

    def connect_device(self):
        tool = self._home_bin('adbconnect')
        if tool is None:
            return
        known = []
        hosts = pathlib.Path.home() / '.adbconnect_known_hosts.json'
        try:
            known = [f'{ip}:{port}' for ip, port in json.loads(hosts.read_text()).items()]
        except (OSError, ValueError):
            pass
        SCAN = 'all — full network scan'
        hint = ('adbconnect target: "all" (the default) scans every local network\n'
                'and the device-selection prompt appears in the log; answer it\n'
                'with the input box next to Stop. Or pick a remembered host, or\n'
                'type a suffix/full host with optional port, e.g.\n'
                '"192.0.2.59:5555", "123.200:40635", "200 40635".')

        def launch(target):
            if target.split()[0].lower() == 'all':
                self._start([tool], 'Connect ADB device (scan all networks)',
                            interactive=True)
                return
            try:
                argv = shlex.split(target)
            except ValueError as error:
                messagebox.showerror('Bad target', str(error))
                return
            self._start([tool, *argv], f'Connect ADB device {target}')

        self._picker('Connect ADB device', hint, [SCAN, *known], launch)

    def install_apk(self):
        tool = self._home_bin('install-apk')
        if tool is None:
            return
        listing = self._capture(['adb', 'devices'])
        rows = []
        position = 0
        for line in listing.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                position += 1
                rows.append(f'{position}  {parts[0]}  [{parts[1]}]')
        if not rows:
            messagebox.showwarning(
                'No devices',
                'adb devices lists nothing. Connect one first (action 4).')
            return

        def pick_variant(device_choice):
            position = device_choice.split()[0]  # rows are "N  serial  [state]"

            def launch(variant):
                argv = [tool, position]
                if variant not in ('', 'auto'):
                    argv.append(variant)
                self._start(argv, f'Install APK to {position} ({variant or "auto"})')

            self._picker('Install APK: variant',
                         'auto = leanback eabi on TVs, m64 on phones.\n'
                         'Overrides: m64, meabi, l64, leabi.',
                         ['auto', 'm64', 'meabi', 'l64', 'leabi'], launch)

        self._picker('Install APK: device',
                     'Position from adb devices (row number), or "all"\n'
                     'for every device in the ready state.',
                     rows + ['all'], pick_variant)

    # ------------------------------------------------------------ plumbing
    def _home_bin(self, name):
        path = pathlib.Path.home() / '.bin' / name
        if path.is_file():
            return str(path)
        found = shutil.which(name)
        if found:
            return found
        messagebox.showerror('Missing tool', f'~/.bin/{name} not found.')
        return None

    def _picker(self, title, prompt, values, on_choice):
        dialog = tk.Toplevel(self.root)
        dialog.title(title)
        dialog.transient(self.root)
        dialog.grab_set()
        ttk.Label(dialog, text=prompt, justify='left').pack(padx=10, pady=(10, 4))
        var = tk.StringVar(value=values[0] if values else '')
        frame = ttk.Frame(dialog)
        frame.pack(fill='x', padx=10, pady=4)
        box = ttk.Combobox(frame, textvariable=var, values=values, width=44)
        box.pack(fill='x')
        box.focus_set()

        def choose(_event=None):
            choice = var.get().strip()
            dialog.destroy()
            if choice:
                on_choice(choice)

        box.bind('<Return>', choose)
        buttons = ttk.Frame(dialog)
        buttons.pack(pady=(4, 10))
        ttk.Button(buttons, text='Run', command=choose).pack(side='left', padx=4)
        ttk.Button(buttons, text='Cancel', command=dialog.destroy).pack(side='left', padx=4)

    def _java_ok(self):
        if shutil.which('java') is None:
            messagebox.showerror(
                'Java missing',
                'java is not on PATH. Run ./build.sh once to install OpenJDK 17, '
                'then reopen this window.')
            return False
        return True

    def _start(self, argv, title, interactive=False):
        if not self.runner.start(argv, title, interactive=interactive):
            messagebox.showwarning('Busy', 'Another job is still running.')
            return
        self.job_label.configure(text=title)
        self.stop_button.configure(state='normal')
        state = 'normal' if interactive else 'disabled'
        self.input_entry.configure(state=state)
        self.send_button.configure(state=state)
        if interactive:
            self.input_entry.focus_set()

    def _send_input(self, _event=None):
        line = self.input_entry.get()
        if self.runner.send_input(line):
            self.input_entry.delete(0, 'end')
            self._append(line + '\n')

    def _drain(self):
        try:
            while True:
                kind, payload = self.messages.get_nowait()
                if kind == 'done':
                    self.job_label.configure(
                        text=f'idle (last exit {payload})' if payload else 'idle')
                    self.stop_button.configure(state='disabled')
                    self.input_entry.configure(state='disabled')
                    self.send_button.configure(state='disabled')
                    self.refresh_status()
                    if payload:
                        self._append(f'\n*** job failed with exit code {payload}\n')
                else:
                    self._append(payload)
        except queue.Empty:
            pass
        self.root.after(120, self._drain)

    def _append(self, text):
        self.log.configure(state='normal')
        self.log.insert('end', text)
        self.log.see('end')
        self.log.configure(state='disabled')

    def _clear_log(self):
        self.log.configure(state='normal')
        self.log.delete('1.0', 'end')
        self.log.configure(state='disabled')


def main():
    root = tk.Tk()
    MaintainApp(root)
    root.mainloop()


if __name__ == '__main__':
    main()
