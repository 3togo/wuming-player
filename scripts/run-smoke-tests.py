#!/usr/bin/env python3
"""Optional, explicitly targeted production/deep smoke evidence; never an acceptance gate."""
import argparse
import collections
import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import shlex
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "com.wuming.android.tv.publicedition.verification"
COMPONENT = PACKAGE + "/com.wuming.android.tv.ui.activity.HomeActivity"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
METHOD = re.compile(r"com\.wuming\.android\.tv\.[A-Za-z0-9_.$]+#[A-Za-z0-9_]+$")


def load_suite(tier, mode, path=ROOT / "tests/smoke/suites.json"):
    catalog = json.loads(path.read_text())
    if catalog.get("version") != 1:
        raise ValueError("Unsupported smoke manifest version")
    groups = catalog["production"] + (catalog["deep"] if tier == "deep" else [])
    selected = [dict(g) for g in groups if g["mode"] in ("all", mode)]
    seen = set()
    names = set()
    for group in selected:
        if group["id"] in names or not re.fullmatch(r"[a-z-]+", group["id"]):
            raise ValueError("Duplicate/invalid group id")
        names.add(group["id"])
        if not isinstance(group["ui"], bool) or not 1 <= group["timeout_seconds"] <= 600:
            raise ValueError("Invalid group controls")
        if not group["tests"]:
            raise ValueError("Empty test group")
        for test in group["tests"]:
            if not METHOD.fullmatch(test) or test in seen:
                raise ValueError("Invalid or duplicate method: " + test)
            seen.add(test)
    if not selected:
        raise ValueError("Empty smoke selection")
    return selected


def instrumentation_result(output, expected, exit_code=0):
    fields, started, finished, codes = {}, [], [], []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: ") and "=" in line:
            key, value = line.split(": ", 1)[1].split("=", 1)
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            status = int(line.split(": ", 1)[1])
            identifier = fields.get("class", "") + "#" + fields.get("test", "")
            if status == 1:
                started.append(identifier)
            elif "test" in fields:
                finished.append({"test": identifier, "status": status})
            fields = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            codes.append(line.split(": ", 1)[1])
    counts = collections.Counter(expected)
    passed = sum(test["status"] == 0 for test in finished)
    success = (bool(expected) and exit_code == 0 and codes == ["-1"]
               and collections.Counter(started) == counts
               and collections.Counter(x["test"] for x in finished) == counts
               and passed == len(expected)
               and not any(marker in output for marker in
                           ("FAILURES!!!", "Process crashed", "INSTRUMENTATION_FAILED:")))
    return {"success": success, "expected": expected, "started": started,
            "finished": finished, "passed": passed, "final_codes": codes,
            "adb_exit_code": exit_code}


def resolve_apk(variant, runner=False):
    directory = ROOT / "app/build/outputs/apk"
    if runner:
        directory /= "androidTest"
    directory = directory / variant / "verification"
    metadata = json.loads((directory / "output-metadata.json").read_text())
    expected = PACKAGE + (".test" if runner else "")
    if metadata["applicationId"] != expected or len(metadata["elements"]) != 1:
        raise ValueError("APK metadata does not identify the isolated verification package")
    path = (directory / metadata["elements"][0]["outputFile"]).resolve()
    if path.parent != directory.resolve() or not path.is_file():
        raise ValueError("Invalid APK output path")
    return path


def verify_apk_package(apk, expected):
    tool = shutil.which("aapt")
    if not tool:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        local = ROOT / "local.properties"
        if not sdk and local.exists():
            match = re.search(r"(?m)^sdk\.dir=(.+)$", local.read_text())
            sdk = match[1].strip() if match else None
        base = pathlib.Path(sdk) if sdk else pathlib.Path.home() / "Android/Sdk"
        candidates = sorted((base / "build-tools").glob("*/aapt"))
        tool = str(candidates[-1]) if candidates else None
    if not tool:
        raise RuntimeError("aapt is required to verify the actual APK package before installation")
    result = subprocess.run([tool, "dump", "badging", str(apk)], capture_output=True,
                            text=True, timeout=30, check=True)
    match = re.search(r"(?m)^package: name='([^']+)'", result.stdout)
    if not match or match[1] != expected:
        raise ValueError("APK manifest package does not match " + expected)


class DeviceRun:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.stop, self.foreground = threading.Event(), threading.Event()
        self.focus_lock = threading.Lock()
        self.worker = None
        self.saved = None
        self.summary = {"serial": serial, "package": PACKAGE, "groups": [], "launches": {}}

    def adb(self, *args, timeout=30):
        return subprocess.run(["adb", "-s", self.serial, *args], capture_output=True,
                              text=True, timeout=timeout, check=False)

    def checked(self, *args, timeout=30):
        result = self.adb(*args, timeout=timeout)
        if result.returncode:
            raise RuntimeError("ADB operation failed: " + " ".join(args[:3]) + ": " + result.stderr.strip())
        return result.stdout

    def keep_awake(self):
        while not self.stop.wait(5):
            try:
                self.checked("shell", "input", "keyevent", "KEYCODE_WAKEUP", timeout=10)
                with self.focus_lock:
                    if self.foreground.is_set():
                        self.checked("shell", "am", "start", "-n", COMPONENT, timeout=10)
            except (RuntimeError, subprocess.TimeoutExpired):
                # The main test deadline remains authoritative; cleanup failures
                # are recorded separately and never turn a failed run green.
                pass

    def prepare(self, abi):
        if self.checked("get-state").strip() != "device":
            raise RuntimeError("The explicitly selected device is not ready")
        actual = self.checked("shell", "getprop", "ro.product.cpu.abilist").strip()
        if abi.replace("_", "-") not in actual.split(","):
            raise RuntimeError("Requested ABI is not supported by the selected device: " + actual)
        self.summary["device_abis"] = actual
        for prop in ("ro.product.model", "ro.build.version.sdk"):
            self.summary[prop] = self.checked("shell", "getprop", prop).strip()
        self.saved = self.checked("shell", "settings", "get", "global", "stay_on_while_plugged_in").strip()
        if not (self.saved == "null" or self.saved.isdigit()):
            self.saved = None
            raise RuntimeError("Cannot safely save device power setting")
        self.summary["saved_stay_on"] = self.saved
        self.checked("shell", "svc", "power", "stayon", "true")
        self.checked("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.checked("shell", "wm", "dismiss-keyguard")
        self.worker = threading.Thread(target=self.keep_awake, daemon=True)
        self.worker.start()

    def install(self, apks):
        self.summary["apks"] = []
        for index, apk in enumerate(apks):
            self.summary["apks"].append({"path": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
            result = self.adb("install", "-r", str(apk), timeout=120)
            (self.output / f"install-{index}.txt").write_text(result.stdout + result.stderr)
            if result.returncode or "Success" not in result.stdout:
                raise RuntimeError("Data-preserving installation failed; see install log")

    def launch(self, name):
        result = self.checked("shell", "am", "start", "-W", "-n", COMPONENT)
        (self.output / (name + "-launch.txt")).write_text(result)
        time.sleep(2)
        pid = self.adb("shell", "pidof", PACKAGE).stdout.strip()
        window = self.checked("shell", "dumpsys", "window")
        focus = "\n".join(x for x in window.splitlines() if "mCurrentFocus" in x)
        success = "Status: ok" in result and bool(pid) and PACKAGE in focus
        self.summary["launches"][name] = {"success": success, "pid": pid, "focus": focus}
        if not success:
            raise RuntimeError(name + " failed (including possible locked device or external focus change)")

    def instrument(self, group):
        print(f"{group['id']}: {len(group['tests'])} tests", flush=True)
        with self.focus_lock:
            if group["ui"]:
                self.foreground.clear()
            else:
                self.foreground.set()
        path = self.output / (group["id"] + ".txt")
        timed_out = False
        diagnostic_id = self.output.name + "-" + group["id"]
        extra = ["-e", "smoke_run_id", diagnostic_id]
        for key, value in group.get("arguments", {}).items():
            extra.extend(["-e", key, shlex.quote(value)])
        with path.open("w") as log:
            process = subprocess.Popen(["adb", "-s", self.serial, "shell", "am", "instrument", "-w", "-r",
                                        "-e", "class", ",".join(group["tests"]), *extra, RUNNER],
                                       stdout=log, stderr=subprocess.STDOUT)
            try:
                process.wait(timeout=group["timeout_seconds"])
            except subprocess.TimeoutExpired:
                timed_out = True
            finally:
                with self.focus_lock:
                    self.foreground.clear()
                if process.poll() is None:
                    try:
                        self.adb("shell", "am", "force-stop", PACKAGE, timeout=10)
                    finally:
                        process.kill()
                        process.wait(timeout=10)
        diagnostic_ok = True
        if group["id"] in ("live-provider", "playback-diagnostics-fixture"):
            diagnostic_ok = False
            diagnostic = self.adb("shell", "run-as", PACKAGE, "cat", "cache/smoke/" + diagnostic_id + ".json")
            if diagnostic.returncode == 0:
                try:
                    payload = json.loads(diagnostic.stdout)
                    if not isinstance(payload, dict) or not isinstance(payload.get("samples"), list):
                        raise ValueError("Invalid playback report")
                    (self.output / (group["id"] + "-diagnostics.json")).write_text(json.dumps(payload, indent=2) + "\n")
                    diagnostic_ok = True
                except (ValueError, OSError):
                    self.summary["diagnostic_error"] = "Playback diagnostic could not be parsed or saved"
            else:
                self.summary["diagnostic_error"] = "Playback diagnostic unavailable (possible early failure or timeout)"
        result = instrumentation_result(path.read_text(), group["tests"], process.returncode)
        result.update({"id": group["id"], "timeout": timed_out})
        result["success"] = result["success"] and not timed_out and diagnostic_ok
        result["diagnostics_saved"] = diagnostic_ok
        self.summary["groups"].append(result)
        if not result["success"]:
            raise RuntimeError(group["id"] + " failed or did not complete the exact selection")

    def cleanup(self):
        errors = []
        self.foreground.clear()
        self.stop.set()
        if self.worker:
            self.worker.join(timeout=25)
            if self.worker.is_alive():
                errors.append("Wake/foreground worker did not stop")
        if self.saved is not None:
            commands = [("shell", "am", "force-stop", PACKAGE),
                        ("shell", "input", "keyevent", "KEYCODE_HOME")]
            commands.append(("shell", "settings", "delete", "global", "stay_on_while_plugged_in")
                            if self.saved == "null" else
                            ("shell", "settings", "put", "global", "stay_on_while_plugged_in", self.saved))
            for command in commands:
                try:
                    self.checked(*command)
                except Exception as error:
                    errors.append(str(error))
            try:
                restored = self.checked("shell", "settings", "get", "global", "stay_on_while_plugged_in").strip()
                self.summary["restored_stay_on"] = restored
                if restored != self.saved:
                    errors.append("Device power setting was not restored")
            except Exception as error:
                errors.append(str(error))
        self.summary["cleanup_errors"] = errors
        if errors:
            self.summary["success"] = False
        (self.output / "summary.json").write_text(json.dumps(self.summary, indent=2) + "\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tier", choices=("production", "deep"), required=True)
    parser.add_argument("--mode", choices=("mobile", "leanback"), required=True)
    parser.add_argument("--abi", choices=("arm64_v8a", "armeabi_v7a"), default="arm64_v8a")
    parser.add_argument("--serial", help="Explicitly authorized device; never auto-detected")
    parser.add_argument("--dry-run", action="store_true", help="Print exact selection without build, ADB, or installation")
    parser.add_argument("--offline", action="store_true", help="Use only cached Gradle dependencies")
    parser.add_argument("--no-build", action="store_true", help="Install existing verified-package APK outputs")
    parser.add_argument("--persistence", action="store_true", help="Disposable backup/migration/recovery and artwork tests")
    parser.add_argument("--live-seconds", type=int, default=15, help="Live observation duration, 15–300 seconds")
    parser.add_argument("--tv-navigation", action="store_true", help="Add injected D-pad/OK/Back tests (Leanback only)")
    parser.add_argument("--live-channel", help="Opt in to decoded playback of an exact bundled Hong Kong playlist channel")
    args = parser.parse_args(argv)
    if not 15 <= args.live_seconds <= 300 or (args.live_seconds != 15 and not args.live_channel):
        parser.error("--live-seconds requires --live-channel and must be 15–300")
    if args.tv_navigation and args.mode != "leanback":
        parser.error("--tv-navigation requires --mode leanback")
    groups = load_suite(args.tier, args.mode)
    if args.persistence:
        groups += json.loads((ROOT / "tests/smoke/persistence.json").read_text())["groups"]
    if args.tv_navigation:
        existing = {test for group in groups for test in group["tests"]}
        methods = [
            "com.wuming.android.tv.api.config.WallConfigInstrumentedTest#settingsCanReadWallpaperBeforeBackgroundInitialization",
            "com.wuming.android.tv.ui.LeanbackRemoteDialogsInstrumentedTest#remoteCanLeaveEditorsAndReachConfirmation",
            "com.wuming.android.tv.ui.LeanbackRemoteDialogsInstrumentedTest#dynamicDialogActionsReceiveFocusAndRemainReachable",
            "com.wuming.android.tv.ui.LeanbackCriticalFlowInstrumentedTest#settingsRouteRespondsToInjectedDpadAndOk",
        ]
        groups.append({"id": "remote-navigation", "mode": "leanback", "ui": True,
                       "timeout_seconds": 180, "tests": [t for t in methods if t not in existing]})
    if args.live_channel:
        playlist = ROOT / "app/src/main/assets/live/public_samples.m3u8"
        names = {line.rsplit(",", 1)[-1] for line in playlist.read_text().splitlines() if line.startswith("#EXTINF:")}
        if args.live_channel not in names:
            parser.error("--live-channel must exactly match a bundled playlist channel name")
        groups.append({"id": "live-provider", "mode": "all", "ui": True,
                       "timeout_seconds": args.live_seconds + 100,
                       "arguments": {"live_channel": args.live_channel, "live_seconds": str(args.live_seconds)},
                       "tests": ["com.wuming.android.tv.player.LiveChannelSmokeInstrumentedTest#bundledLiveChannelRendersAndAdvances"]})
    variant = args.mode + args.abi[0].upper() + args.abi[1:]
    plan = {"tier": args.tier, "mode": args.mode, "variant": variant, "groups": groups,
            "test_count": sum(len(g["tests"]) for g in groups)}
    if args.dry_run:
        print(json.dumps(plan, indent=2))
        return 0
    if not args.serial or args.serial.startswith("-") or any(c.isspace() for c in args.serial):
        parser.error("--serial must name an explicitly authorized device")
    print("Installing 無名(testing), the isolated app, and 無名(test runner), its separate runner. "
          "Application data is preserved.", flush=True)
    if not args.no_build:
        capitalized = variant[0].upper() + variant[1:]
        command = [str(ROOT / "gradlew-safe"),
                   ":app:assemble" + capitalized + "Verification",
                   ":app:assemble" + capitalized + "VerificationAndroidTest"]
        if args.offline:
            command.append("--offline")
        subprocess.run(command, cwd=ROOT, check=True)
    apks = [resolve_apk(variant), resolve_apk(variant, runner=True)]
    verify_apk_package(apks[0], PACKAGE)
    verify_apk_package(apks[1], PACKAGE + ".test")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = ROOT / "app/build/reports/smoke" / (stamp + "-" + args.tier + "-" + args.mode)
    output.mkdir(parents=True, exist_ok=False)
    (output / "selection.json").write_text(json.dumps(plan, indent=2) + "\n")
    device = DeviceRun(args.serial, output)
    device.summary.update({"started_utc": stamp, "tier": args.tier, "variant": variant, "success": False,
                           "remaining_groups": [g["id"] for g in groups]})
    try:
        device.prepare(args.abi)
        device.install(apks)
        device.launch("initial")
        for group in groups:
            device.instrument(group)
            device.summary["remaining_groups"].remove(group["id"])
        device.checked("shell", "am", "force-stop", PACKAGE)
        device.launch("cold-start")
        device.checked("shell", "input", "keyevent", "KEYCODE_HOME")
        device.launch("background-return")
        device.checked("shell", "am", "force-stop", PACKAGE)
        device.launch("restart")
        device.summary["success"] = True
    except (Exception, KeyboardInterrupt) as error:
        device.summary["error"] = str(error) or type(error).__name__
    finally:
        device.cleanup()
    print("PASS" if device.summary["success"] else "FAILED/INCOMPLETE", output)
    return 0 if device.summary["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
