#!/usr/bin/env python3
"""Restore a saved phone's temporary casting-debug settings over explicit-serial ADB."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="Authorized phone's exact ADB serial")
    parser.add_argument("--state-file", type=Path, help="Original settings JSON")
    parser.add_argument("--dry-run", action="store_true", help="Show commands without accessing the phone")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        parser.error("Invalid device serial")
    base = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local/state")
    path = args.state_file or base / "wuming/cast-debug" / f"{args.serial}.json"
    try:
        state = json.loads(path.read_text())
        if state["serial"] != args.serial:
            raise ValueError("Snapshot belongs to a different device")
        power = str(state["power"])
        lock = state["lockDisabled"]
        level = state["castDebug"]
        if power != "null" and not (power.isdecimal() and 0 <= int(power) <= 15):
            raise ValueError("Invalid original power setting")
        if lock not in ("true", "false") or not isinstance(level, str):
            raise ValueError("Invalid original lock/log setting")
        if len(level) > 32 or any(ord(c) < 32 for c in level):
            raise ValueError("Invalid original logging value")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(f"Cannot use settings snapshot {path}: {error}")

    prefix = ["adb", "-s", args.serial]
    power_command = (["settings", "delete", "global", "stay_on_while_plugged_in"]
                     if power == "null" else
                     ["settings", "put", "global", "stay_on_while_plugged_in", power])
    settings = [
        ("casting logging", ["setprop", "log.tag.CastDebug", level],
         ["getprop", "log.tag.CastDebug"], level),
        ("screen lock", ["locksettings", "set-disabled", lock],
         ["locksettings", "get-disabled"], lock),
        ("stay-awake", power_command,
         ["settings", "get", "global", "stay_on_while_plugged_in"], power),
    ]

    def command(parts):
        # ADB's remote shell needs quoting even when the host uses an argv list.
        return prefix + ["shell", shlex.join(parts)]

    if args.dry_run:
        for name, restore, _, _ in settings:
            print(f"Restore {name}: {shlex.join(command(restore))}")
        return 0

    def run(parts):
        result = subprocess.run(parts, text=True, capture_output=True, timeout=15)
        if result.returncode:
            raise RuntimeError((result.stderr or result.stdout).strip() or "ADB command failed")
        return result.stdout.strip()

    try:
        if run(prefix + ["get-state"]) != "device":
            raise RuntimeError("Device is not ready")
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Reconnect {args.serial} by USB and allow USB debugging, then rerun.\n{error}", file=sys.stderr)
        return 1

    failures = []
    for name, restore, read, expected in settings:
        try:
            run(command(restore))
            if run(command(read)) != expected:
                raise RuntimeError("Readback did not match the original value")
            print(f"Restored {name}")
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            failures.append(name)
            print(f"Could not restore {name}: {error}", file=sys.stderr)
    if failures:
        print("Snapshot retained. Reconnect and rerun to retry.", file=sys.stderr)
        return 1
    print("Original debugging settings restored and verified. Snapshot retained for reuse.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
