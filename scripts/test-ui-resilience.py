#!/usr/bin/env python3
"""Crawl the installed Android app and audit startup resilience and UI consistency.

The safe default skips checkable/editable controls and labels associated with
destructive actions. It does not clear application data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable


DEFAULT_PACKAGE = "com.fongmi.android.tv"
DEFAULT_REPORT_ROOT = Path("app/build/reports/ui-resilience")
RISKY_LABELS = re.compile(
    r"\b(delete|remove|clear|reset|logout|log out|sign out|uninstall|erase|"
    r"purchase|buy|pay|format|factory|change site|switch site|choose site)\b|"
    r"删除|刪除|清除|重置|退出登录|退出登入|登出|卸载|解除|购买|購買|付款|"
    r"格式化|切换站点|切換站點|选择站点|選擇站點",
    re.IGNORECASE,
)
BOUNDS = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")


class TestFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class Action:
    x: int
    y: int
    label: str
    resource_id: str
    class_name: str


@dataclass
class Page:
    number: int
    signature: str
    activity: str
    title: str
    depth: int
    path: list[str]
    screenshot: str
    hierarchy: str
    actionable_controls: int
    ux_warnings: list[str]


@dataclass
class Edge:
    source: str
    target: str
    action: str


@dataclass(frozen=True)
class LaunchMetric:
    label: str
    this_time_ms: int | None
    total_time_ms: int | None
    wait_time_ms: int | None
    total_pss_kb: int | None
    total_rss_kb: int | None


def compare_performance_baseline(
    current: list[LaunchMetric], baseline: dict, device: dict[str, str],
    package: str, mode: str, time_limit_percent: float,
    memory_limit_percent: float,
) -> dict:
    identity = ("manufacturer", "model", "sdk")
    baseline_device = baseline.get("device", {})
    if baseline.get("package") != package or baseline.get("mode") != mode or any(
        baseline_device.get(key) != device.get(key) for key in identity
    ):
        return {"status": "skipped", "reason": "baseline device or app mode does not match", "regressions": []}
    baseline_metrics = [
        LaunchMetric(**item) for item in baseline.get("launch_metrics", [])
        if isinstance(item, dict) and item.get("label", "").startswith("cold-")
    ]
    current_metrics = [item for item in current if item.label.startswith("cold-")]
    comparisons = []
    regressions = []
    for field, label, limit in (
        ("total_time_ms", "cold total time", time_limit_percent),
        ("total_pss_kb", "cold total PSS", memory_limit_percent),
    ):
        before = [getattr(item, field) for item in baseline_metrics if getattr(item, field) is not None]
        after = [getattr(item, field) for item in current_metrics if getattr(item, field) is not None]
        if not before or not after:
            continue
        baseline_value = float(statistics.median(before))
        current_value = float(statistics.median(after))
        change = 0.0 if baseline_value == 0 else (current_value - baseline_value) * 100.0 / baseline_value
        comparisons.append({
            "metric": field, "label": label, "baseline_median": baseline_value,
            "current_median": current_value, "change_percent": round(change, 2),
            "limit_percent": limit,
        })
        if change > limit:
            regressions.append(f"{label} regressed {change:.1f}% (limit {limit:.1f}%)")
    return {"status": "compared", "comparisons": comparisons, "regressions": regressions}


class Adb:
    def __init__(self, serial: str | None):
        self.prefix = ["adb"]
        if serial:
            self.prefix += ["-s", serial]

    def run(
        self,
        *args: str,
        check: bool = True,
        timeout: float = 30,
        text: bool = True,
    ) -> subprocess.CompletedProcess:
        command = self.prefix + list(args)
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=text,
            timeout=timeout,
        )
        if check and result.returncode:
            stderr = result.stderr if text else result.stderr.decode(errors="replace")
            stdout = result.stdout if text else result.stdout.decode(errors="replace")
            raise TestFailure(
                f"Command failed ({result.returncode}): {shlex.join(command)}\n"
                f"{stderr.strip() or stdout.strip()}"
            )
        return result

    def shell(self, *args: str, **kwargs) -> subprocess.CompletedProcess:
        return self.run("shell", *args, **kwargs)


class UiResilienceTest:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.adb = Adb(args.serial)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.report_dir = Path(args.output or DEFAULT_REPORT_ROOT / stamp).resolve()
        self.artifact_dir = self.report_dir / "pages"
        self.pages: list[Page] = []
        self.edges: list[Edge] = []
        self.launch_metrics: list[LaunchMetric] = []
        self.performance_comparison: dict = {"status": "not-requested", "regressions": []}
        self.failures: list[str] = []
        self.ux_warnings: list[str] = []
        self.visited: set[str] = set()
        self.expanded: set[str] = set()
        self.page_by_signature: dict[str, Page] = {}
        self.control_sizes: dict[str, list[tuple[int, int, str]]] = {}
        self.launch_component = ""
        self.characteristics = ""
        self.mode = ""
        self.width = 0
        self.height = 0
        self.density = 160
        self.device_info: dict[str, str] = {}
        self.app_info: dict[str, str] = {}
        self.original_rotation: str | None = None
        self.original_accelerometer: str | None = None

    def run(self) -> int:
        self.artifact_dir.mkdir(parents=True, exist_ok=True)
        try:
            self.preflight()
            self.clear_logcat()
            self.cold_start_trials()
            self.compare_performance()
            self.background_foreground_trial()
            self.memory_pressure_trial()
            self.rotation_trial()
            self.crawl(depth=0, path=[])
            if self.mode == "tv":
                self.tv_focus_trial()
        except (TestFailure, subprocess.TimeoutExpired, ET.ParseError) as error:
            self.failures.append(str(error))
        finally:
            self.restore_rotation()
            self.collect_logcat()
            self.audit_control_consistency()
            self.write_report()

        print(f"UI resilience report: {self.report_dir / 'summary.md'}")
        print(
            f"Pages: {len(self.pages)}, crashes/failures: {len(self.failures)}, "
            f"UX warnings: {len(self.ux_warnings)}"
        )
        if self.failures:
            return 1
        if self.args.fail_on_ux and self.ux_warnings:
            return 2
        if self.args.fail_on_performance and self.performance_comparison.get("regressions"):
            return 3
        return 0

    def compare_performance(self) -> None:
        if not self.args.baseline:
            return
        try:
            baseline = json.loads(Path(self.args.baseline).read_text(encoding="utf-8"))
            self.performance_comparison = compare_performance_baseline(
                self.launch_metrics, baseline, self.device_info, self.args.package,
                self.mode, self.args.max_time_regression_percent,
                self.args.max_memory_regression_percent,
            )
        except (OSError, ValueError, TypeError) as error:
            self.performance_comparison = {
                "status": "invalid", "reason": str(error), "regressions": []
            }

    def preflight(self) -> None:
        state = self.adb.run("get-state").stdout.strip()
        if state != "device":
            raise TestFailure(f"Android device is not ready: {state or 'unknown'}")

        if self.args.apk:
            apk = str(Path(self.args.apk).resolve())
            result = self.adb.run("install", "-r", apk, check=False, timeout=180)
            if result.returncode:
                raise TestFailure(f"Could not install {apk}: {result.stderr.strip()}")

        packages = self.adb.shell("pm", "path", self.args.package, check=False).stdout
        if not packages.strip().startswith("package:"):
            raise TestFailure(
                f"{self.args.package} is not installed. Install a matching debug APK first."
            )

        resolution = self.adb.shell(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            self.args.package,
        ).stdout
        components = [
            line.strip()
            for line in resolution.splitlines()
            if "/" in line and not line.startswith("priority=")
        ]
        if not components:
            raise TestFailure(f"Could not resolve launcher activity for {self.args.package}")
        self.launch_component = components[-1]

        self.characteristics = self.adb.shell(
            "getprop", "ro.build.characteristics"
        ).stdout.strip()
        self.device_info = {
            "manufacturer": self.device_property("ro.product.manufacturer"),
            "model": self.device_property("ro.product.model"),
            "sdk": self.device_property("ro.build.version.sdk"),
            "abis": self.device_property("ro.product.cpu.abilist"),
        }
        package_dump = self.adb.shell(
            "dumpsys", "package", self.args.package, check=False
        ).stdout
        self.app_info = self.parse_package_info(package_dump)
        self.mode = self.args.mode
        if self.mode == "auto":
            self.mode = "tv" if "tv" in self.characteristics.lower() else "mobile"

        size = self.adb.shell("wm", "size").stdout
        match = re.search(r"(?:Override|Physical) size:\s*(\d+)x(\d+)", size)
        if not match:
            raise TestFailure(f"Could not determine display size from: {size.strip()}")
        self.width, self.height = map(int, match.groups())

        density = self.adb.shell("wm", "density").stdout
        density_match = re.search(r"(?:Override|Physical) density:\s*(\d+)", density)
        if density_match:
            self.density = int(density_match.group(1))

        self.original_accelerometer = self.adb.shell(
            "settings", "get", "system", "accelerometer_rotation", check=False
        ).stdout.strip()
        self.original_rotation = self.adb.shell(
            "settings", "get", "system", "user_rotation", check=False
        ).stdout.strip()

    def clear_logcat(self) -> None:
        self.adb.run("logcat", "-c", check=False)

    def device_property(self, name: str) -> str:
        return self.adb.shell("getprop", name, check=False).stdout.strip()

    def start_app(self, cold: bool = False, metric_label: str | None = None) -> None:
        if cold:
            self.adb.shell("am", "force-stop", self.args.package)
        result = self.adb.shell(
            "am", "start", "-W", "-n", self.launch_component, timeout=45, check=False
        )
        output = result.stdout + result.stderr
        if result.returncode or "Error:" in output or "Status: timeout" in output:
            raise TestFailure(f"App launch failed:\n{output.strip()}")
        self.settle()
        self.assert_alive("launch")
        if metric_label:
            timings = self.parse_launch_timings(output)
            pss_kb, rss_kb = self.read_process_memory()
            self.launch_metrics.append(
                LaunchMetric(metric_label, *timings, pss_kb, rss_kb)
            )

    def read_process_memory(self) -> tuple[int | None, int | None]:
        output = self.adb.shell(
            "dumpsys", "meminfo", "--local", self.args.package, check=False
        ).stdout
        return self.parse_process_memory(output)

    def settle(self) -> None:
        deadline = time.monotonic() + self.args.settle
        while time.monotonic() < deadline:
            time.sleep(min(0.2, deadline - time.monotonic()))

    def assert_alive(self, phase: str) -> None:
        pid = self.adb.shell("pidof", self.args.package, check=False).stdout.strip()
        if not pid:
            self.failures.append(f"{phase}: application process is not alive")
            raise TestFailure(self.failures[-1])
        crash = self.latest_crash()
        if crash:
            self.failures.append(f"{phase}: {crash}")
            raise TestFailure(self.failures[-1])

    def latest_crash(self) -> str | None:
        log = self.adb.run("logcat", "-d", "-v", "brief", check=False).stdout
        return self.find_crash(log)

    def cold_start_trials(self) -> None:
        for trial in range(1, self.args.cold_starts + 1):
            self.start_app(cold=True, metric_label=f"cold-{trial}")
            self.capture_page(0, [f"cold-start-{trial}"], force=True)

    def background_foreground_trial(self) -> None:
        self.adb.shell("input", "keyevent", "KEYCODE_HOME")
        self.settle()
        self.start_app(cold=False, metric_label="warm-background-foreground")
        self.capture_page(0, ["background-foreground"], force=True)

    def memory_pressure_trial(self) -> None:
        self.adb.shell(
            "am",
            "send-trim-memory",
            self.args.package,
            "RUNNING_CRITICAL",
            check=False,
        )
        self.settle()
        self.assert_alive("memory pressure")
        self.capture_page(0, ["memory-pressure"], force=True)

    def rotation_trial(self) -> None:
        if self.mode == "tv" or self.args.skip_rotation:
            return
        for rotation, name in (("1", "landscape"), ("0", "portrait")):
            self.adb.shell(
                "settings", "put", "system", "accelerometer_rotation", "0", check=False
            )
            self.adb.shell(
                "settings", "put", "system", "user_rotation", rotation, check=False
            )
            self.settle()
            self.assert_alive(f"rotation-{name}")
            self.capture_page(0, [f"rotation-{name}"], force=True)

    def restore_rotation(self) -> None:
        if self.original_accelerometer in {"0", "1"}:
            self.adb.shell(
                "settings",
                "put",
                "system",
                "accelerometer_rotation",
                self.original_accelerometer,
                check=False,
            )
        if self.original_rotation in {"0", "1", "2", "3"}:
            self.adb.shell(
                "settings",
                "put",
                "system",
                "user_rotation",
                self.original_rotation,
                check=False,
            )

    def crawl(self, depth: int, path: list[str]) -> str | None:
        if len(self.visited) >= self.args.max_pages:
            return None
        page, root = self.capture_page(depth, path)
        if page is None or root is None:
            return None
        if page.signature in self.expanded or depth >= self.args.max_depth:
            return page.signature
        self.expanded.add(page.signature)

        actions = list(self.safe_actions(root))
        for action in actions[: self.args.max_actions_per_page]:
            if len(self.visited) >= self.args.max_pages:
                break
            if not self.is_app_foreground():
                self.adb.shell("input", "keyevent", "KEYCODE_BACK", check=False)
                self.start_app(cold=False)

            source = self.current_signature() or page.signature
            self.adb.shell("input", "tap", str(action.x), str(action.y), check=False)
            self.settle()
            try:
                self.assert_alive(f"tap {action.label}")
            except TestFailure:
                break

            if not self.is_app_foreground():
                self.ux_warnings.append(
                    f"Action '{action.label}' left the application; external page was not crawled"
                )
                self.adb.shell("input", "keyevent", "KEYCODE_BACK", check=False)
                self.settle()
                continue

            target = self.crawl(depth + 1, path + [action.label])
            if target:
                self.edges.append(Edge(source=source, target=target, action=action.label))

            self.adb.shell("input", "keyevent", "KEYCODE_BACK", check=False)
            self.settle()
            if not self.is_app_foreground():
                self.start_app(cold=False)
                return page.signature
        return page.signature

    def capture_page(
        self, depth: int, path: list[str], force: bool = False
    ) -> tuple[Page | None, ET.Element | None]:
        hierarchy = self.dump_hierarchy()
        root = ET.fromstring(hierarchy)
        activity = self.current_activity()
        signature = self.signature(activity, root)
        if signature in self.visited and not force:
            return self.page_by_signature.get(signature), root

        number = len(self.pages) + 1
        stem = f"{number:03d}-{self.slug(activity.rsplit('.', 1)[-1] or 'page')}"
        hierarchy_file = self.artifact_dir / f"{stem}.xml"
        screenshot_file = self.artifact_dir / f"{stem}.png"
        hierarchy_file.write_text(hierarchy, encoding="utf-8")
        screenshot = self.adb.run(
            "exec-out", "screencap", "-p", text=False, check=False, timeout=30
        ).stdout
        screenshot_file.write_bytes(screenshot)

        actions = list(self.safe_actions(root))
        warnings = self.audit_page(root, activity)
        title = self.page_title(root) or activity.rsplit(".", 1)[-1]
        page = Page(
            number=number,
            signature=signature,
            activity=activity,
            title=title,
            depth=depth,
            path=path,
            screenshot=str(screenshot_file.relative_to(self.report_dir)),
            hierarchy=str(hierarchy_file.relative_to(self.report_dir)),
            actionable_controls=len(actions),
            ux_warnings=warnings,
        )
        self.pages.append(page)
        self.visited.add(signature)
        self.page_by_signature.setdefault(signature, page)
        self.ux_warnings.extend(f"{title}: {warning}" for warning in warnings)
        return page, root

    def dump_hierarchy(self) -> str:
        remote = "/data/local/tmp/tvbox-ui-resilience.xml"
        result = self.adb.shell(
            "uiautomator", "dump", "--compressed", remote, check=False, timeout=30
        )
        if result.returncode:
            raise TestFailure(f"Could not dump UI hierarchy: {result.stderr.strip()}")
        hierarchy = self.adb.run(
            "exec-out", "cat", remote, check=False, timeout=30
        ).stdout
        if not hierarchy.lstrip().startswith("<?xml"):
            raise TestFailure("UI hierarchy dump was empty or invalid")
        return hierarchy

    def current_activity(self) -> str:
        output = self.adb.shell("dumpsys", "activity", "activities").stdout
        match = re.search(
            rf"(?:topResumedActivity|mResumedActivity)=.*?"
            rf"{re.escape(self.args.package)}/([^\s}}]+)",
            output,
        )
        return match.group(1) if match else "unknown"

    def current_signature(self) -> str | None:
        try:
            root = ET.fromstring(self.dump_hierarchy())
            return self.signature(self.current_activity(), root)
        except (TestFailure, ET.ParseError):
            return None

    def is_app_foreground(self) -> bool:
        return self.current_activity() != "unknown"

    def safe_actions(self, root: ET.Element) -> Iterable[Action]:
        seen: set[tuple[int, int]] = set()
        for node in root.iter("node"):
            if node.get("clickable") != "true" or node.get("enabled") != "true":
                continue
            class_name = node.get("class", "")
            checkable = node.get("checkable") == "true"
            editable = (
                "EditText" in class_name
                or node.get("password") == "true"
                or node.get("long-clickable") == "true"
            )
            if not self.args.allow_risky_actions and (checkable or editable):
                continue
            label = self.node_label(node)
            if not self.args.allow_risky_actions and RISKY_LABELS.search(label):
                continue
            bounds = self.parse_bounds(node)
            if not bounds:
                continue
            left, top, right, bottom = bounds
            if right <= left or bottom <= top:
                continue
            x, y = (left + right) // 2, (top + bottom) // 2
            if not (0 <= x < self.width and 0 <= y < self.height):
                continue
            if (x, y) in seen:
                continue
            seen.add((x, y))
            yield Action(
                x=x,
                y=y,
                label=label or f"{class_name}@{x},{y}",
                resource_id=node.get("resource-id", ""),
                class_name=class_name,
            )

    def audit_page(self, root: ET.Element, activity: str) -> list[str]:
        warnings: list[str] = []
        minimum_px = round(48 * self.density / 160)
        actionable = 0
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter("node"):
            bounds = self.parse_bounds(node)
            if not bounds:
                continue
            left, top, right, bottom = bounds
            width, height = right - left, bottom - top
            clickable = node.get("clickable") == "true"
            focusable = node.get("focusable") == "true"
            if clickable or focusable:
                actionable += 1
                label = self.node_label(node)
                if not label and (clickable or self.mode == "tv"):
                    warnings.append(
                        f"unlabelled actionable {node.get('class', 'control')} "
                        f"at {node.get('bounds', '')}"
                    )
                if (
                    clickable
                    and (width < minimum_px or height < minimum_px)
                    and not self.is_clipped_by_scroll_parent(node, bounds, parents)
                ):
                    warnings.append(
                        f"touch target '{label or node.get('class', 'control')}' is "
                        f"{width}x{height}px; recommended minimum is {minimum_px}px"
                    )
                resource_id = node.get("resource-id", "")
                if resource_id:
                    self.control_sizes.setdefault(resource_id, []).append(
                        (width, height, activity)
                    )
            if left < 0 or top < 0 or right > self.width or bottom > self.height:
                warnings.append(
                    f"control '{self.node_label(node)}' extends outside the display: "
                    f"{node.get('bounds', '')}"
                )
        if actionable == 0:
            warnings.append("page exposes no clickable or focusable controls")
        if self.mode == "tv":
            focusables = [
                node for node in root.iter("node") if node.get("focusable") == "true"
            ]
            if focusables and not any(
                node.get("focused") == "true" for node in focusables
            ):
                warnings.append("TV page has focusable controls but no current focus")
        return list(dict.fromkeys(warnings))

    def is_clipped_by_scroll_parent(
        self,
        node: ET.Element,
        bounds: tuple[int, int, int, int],
        parents: dict[ET.Element, ET.Element],
    ) -> bool:
        left, top, right, bottom = bounds
        parent = parents.get(node)
        while parent is not None:
            if parent.get("scrollable") == "true":
                parent_bounds = self.parse_bounds(parent)
                if parent_bounds:
                    p_left, p_top, p_right, p_bottom = parent_bounds
                    if (
                        left <= p_left
                        or top <= p_top
                        or right >= p_right
                        or bottom >= p_bottom
                    ):
                        return True
            parent = parents.get(parent)
        return False

    def audit_control_consistency(self) -> None:
        for resource_id, samples in sorted(self.control_sizes.items()):
            if len(samples) < 2:
                continue
            widths = [sample[0] for sample in samples if sample[0] > 0]
            heights = [sample[1] for sample in samples if sample[1] > 0]
            if not widths or not heights:
                continue
            width_ratio = max(widths) / min(widths)
            height_ratio = max(heights) / min(heights)
            if width_ratio > 1.75 or height_ratio > 1.75:
                locations = ", ".join(sorted({sample[2] for sample in samples}))
                self.ux_warnings.append(
                    f"Inconsistent size for {resource_id}: "
                    f"{min(widths)}-{max(widths)} x {min(heights)}-{max(heights)}px "
                    f"across {locations}"
                )

    def tv_focus_trial(self) -> None:
        for key in (
            "KEYCODE_DPAD_RIGHT",
            "KEYCODE_DPAD_DOWN",
            "KEYCODE_DPAD_LEFT",
            "KEYCODE_DPAD_UP",
        ):
            self.adb.shell("input", "keyevent", key, check=False)
            self.settle()
            self.assert_alive(f"TV focus navigation {key}")
        self.capture_page(0, ["tv-focus-sweep"], force=True)

    def collect_logcat(self) -> None:
        log = self.adb.run(
            "logcat", "-d", "-v", "threadtime", check=False, timeout=45
        ).stdout
        (self.report_dir / "logcat.txt").write_text(log, encoding="utf-8")
        if not any("FATAL EXCEPTION:" in failure for failure in self.failures):
            crash = self.find_crash(log)
            if crash:
                self.failures.append(f"Crash/ANR found in final logcat:\n{crash}")

    def write_report(self) -> None:
        data = {
            "package": self.args.package,
            "launch_component": self.launch_component,
            "mode": self.mode,
            "characteristics": self.characteristics,
            "device": self.device_info,
            "application": self.app_info,
            "display": {
                "width": self.width,
                "height": self.height,
                "density": self.density,
            },
            "configuration": {
                "cold_starts": self.args.cold_starts,
                "max_pages": self.args.max_pages,
                "max_depth": self.args.max_depth,
                "max_actions_per_page": self.args.max_actions_per_page,
                "allow_risky_actions": self.args.allow_risky_actions,
            },
            "pages": [asdict(page) for page in self.pages],
            "edges": [asdict(edge) for edge in self.edges],
            "launch_metrics": [asdict(metric) for metric in self.launch_metrics],
            "performance_comparison": self.performance_comparison,
            "failures": self.failures,
            "ux_warnings": list(dict.fromkeys(self.ux_warnings)),
        }
        (self.report_dir / "summary.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )

        status = "FAIL" if self.failures else "PASS"
        lines = [
            "# UI resilience and consistency report",
            "",
            f"- Status: **{status}**",
            f"- Package: `{self.args.package}`",
            f"- Mode: `{self.mode}`",
            f"- Reachable page states captured: **{len(self.pages)}**",
            f"- Resilience failures: **{len(self.failures)}**",
            f"- UX warnings: **{len(set(self.ux_warnings))}**",
            "",
            "The crawler covers pages reachable from the launcher using safe controls. "
            "Authentication-, data-, or provider-dependent pages may require fixtures or "
            "`--allow-risky-actions` and should be reviewed separately.",
            "",
            "## Resilience failures",
            "",
        ]
        if self.failures:
            lines.extend(f"- {self.markdown_text(item)}" for item in self.failures)
        else:
            lines.append("- None detected.")
        lines += ["", "## UX warnings", ""]
        warnings = list(dict.fromkeys(self.ux_warnings))
        if warnings:
            lines.extend(f"- {self.markdown_text(item)}" for item in warnings)
        else:
            lines.append("- None detected.")
        lines += ["", "## Performance baseline", ""]
        if self.launch_metrics:
            lines += [
                "| Trial | This time | Total time | Wait time | Total PSS | Total RSS |",
                "|---|---:|---:|---:|---:|---:|",
            ]
            for metric in self.launch_metrics:
                lines.append(
                    f"| {metric.label} | {self.metric(metric.this_time_ms, 'ms')} | "
                    f"{self.metric(metric.total_time_ms, 'ms')} | "
                    f"{self.metric(metric.wait_time_ms, 'ms')} | "
                    f"{self.metric(metric.total_pss_kb, 'KiB')} | "
                    f"{self.metric(metric.total_rss_kb, 'KiB')} |"
                )
        else:
            lines.append("- No launch measurements were captured.")
        comparison = self.performance_comparison
        if comparison.get("status") == "compared":
            lines += ["", "### Comparison with matching-device baseline", ""]
            for item in comparison.get("comparisons", []):
                lines.append(
                    f"- {item['label']}: {item['change_percent']:+.2f}% "
                    f"(allowed regression {item['limit_percent']:.2f}%)."
                )
            if not comparison.get("regressions"):
                lines.append("- No performance regression exceeded its configured limit.")
        elif comparison.get("status") != "not-requested":
            lines += ["", f"- Baseline comparison skipped: {comparison.get('reason', 'unavailable')}."]
        lines += ["", "## Captured pages", ""]
        if self.pages:
            lines += [
                "| # | Page | Activity | Depth | Controls | Artifacts |",
                "|---:|---|---|---:|---:|---|",
            ]
            for page in self.pages:
                lines.append(
                    f"| {page.number} | {self.markdown_text(page.title)} | "
                    f"`{page.activity}` | {page.depth} | {page.actionable_controls} | "
                    f"[PNG]({page.screenshot}) · [XML]({page.hierarchy}) |"
                )
        else:
            lines.append("- No pages were captured.")
        (self.report_dir / "summary.md").write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )

    @staticmethod
    def parse_bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
        match = BOUNDS.fullmatch(node.get("bounds", ""))
        return tuple(map(int, match.groups())) if match else None

    @staticmethod
    def node_label(node: ET.Element) -> str:
        resource = node.get("resource-id", "").rsplit("/", 1)[-1]
        return (
            node.get("text", "").strip()
            or node.get("content-desc", "").strip()
            or resource
        )

    @staticmethod
    def page_title(root: ET.Element) -> str:
        texts = [
            node.get("text", "").strip()
            for node in root.iter("node")
            if node.get("text", "").strip()
        ]
        return texts[0][:80] if texts else ""

    @staticmethod
    def signature(activity: str, root: ET.Element) -> str:
        nodes = []
        for node in root.iter("node"):
            nodes.append(
                "|".join(
                    (
                        node.get("class", ""),
                        node.get("resource-id", ""),
                        node.get("text", ""),
                        node.get("content-desc", ""),
                        node.get("clickable", ""),
                        node.get("checked", ""),
                        node.get("bounds", ""),
                    )
                )
            )
        digest = hashlib.sha256((activity + "\n" + "\n".join(nodes)).encode()).hexdigest()
        return digest[:16]

    @staticmethod
    def slug(value: str) -> str:
        value = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-")
        return value[:60] or "page"

    @staticmethod
    def markdown_text(value: str) -> str:
        return value.replace("\n", " ").replace("|", "\\|")

    @staticmethod
    def metric(value: int | None, unit: str) -> str:
        return "—" if value is None else f"{value} {unit}"

    @staticmethod
    def parse_launch_timings(output: str) -> tuple[int | None, int | None, int | None]:
        def value(name: str) -> int | None:
            match = re.search(rf"(?m)^{name}:\s*(\d+)\s*$", output)
            return int(match.group(1)) if match else None

        return value("ThisTime"), value("TotalTime"), value("WaitTime")

    @staticmethod
    def parse_process_memory(output: str) -> tuple[int | None, int | None]:
        summary = re.search(
            r"TOTAL PSS:\s*([\d,]+).*?TOTAL RSS:\s*([\d,]+)", output, re.DOTALL
        )
        if summary:
            return tuple(int(value.replace(",", "")) for value in summary.groups())
        total = re.search(r"(?m)^\s*TOTAL\s+([\d,]+)(?:\s+[\d,]+){5}\s+([\d,]+)", output)
        if total:
            return tuple(int(value.replace(",", "")) for value in total.groups())
        return None, None

    @staticmethod
    def parse_package_info(output: str) -> dict[str, str]:
        result = {}
        for key in ("versionName", "versionCode"):
            match = re.search(rf"\b{key}=([^\s]+)", output)
            if match:
                result[key] = match.group(1)
        return result

    def find_crash(self, log: str) -> str | None:
        package = re.escape(self.args.package)
        patterns = (
            re.compile(
                rf"FATAL EXCEPTION:[\s\S]{{0,1200}}?Process:\s*{package}(?:,|\s)",
                re.IGNORECASE,
            ),
            re.compile(rf"ANR in {package}\b", re.IGNORECASE),
            re.compile(
                rf"Input dispatching timed out[^\n]*{package}", re.IGNORECASE
            ),
            re.compile(rf"am_crash[^\n]*{package}", re.IGNORECASE),
        )
        matches = [match for pattern in patterns if (match := pattern.search(log))]
        if not matches:
            return None
        match = min(matches, key=lambda item: item.start())
        return log[match.start() : match.start() + 3000]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Crawl reachable app pages, stress lifecycle transitions, capture artifacts, "
            "and report crashes, ANRs, accessibility gaps, and inconsistent controls."
        )
    )
    parser.add_argument("--serial", help="ADB device serial; defaults to ANDROID_SERIAL")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--apk", help="Optionally install this APK with adb install -r")
    parser.add_argument("--mode", choices=("auto", "mobile", "tv"), default="auto")
    parser.add_argument("--output", help="Report directory")
    parser.add_argument("--cold-starts", type=int, default=3)
    parser.add_argument("--max-pages", type=int, default=40)
    parser.add_argument("--max-depth", type=int, default=5)
    parser.add_argument("--max-actions-per-page", type=int, default=12)
    parser.add_argument("--settle", type=float, default=1.25)
    parser.add_argument(
        "--allow-risky-actions",
        action="store_true",
        help="Also exercise toggles, editable fields, long-clicks, and risky labels",
    )
    parser.add_argument("--skip-rotation", action="store_true")
    parser.add_argument("--baseline", help="Previous summary.json from the same device and app mode")
    parser.add_argument("--max-time-regression-percent", type=float, default=20.0)
    parser.add_argument("--max-memory-regression-percent", type=float, default=15.0)
    parser.add_argument(
        "--fail-on-performance", action="store_true",
        help="Return exit code 3 when a matching-device baseline exceeds a limit",
    )
    parser.add_argument(
        "--fail-on-ux",
        action="store_true",
        help="Return exit code 2 when UX warnings are found",
    )
    args = parser.parse_args()
    if not args.serial:
        args.serial = os.environ.get("ANDROID_SERIAL")
    for name in ("cold_starts", "max_pages", "max_depth", "max_actions_per_page"):
        if getattr(args, name) < 0:
            parser.error(f"--{name.replace('_', '-')} cannot be negative")
    if args.settle < 0:
        parser.error("--settle cannot be negative")
    if args.max_time_regression_percent < 0 or args.max_memory_regression_percent < 0:
        parser.error("performance regression limits cannot be negative")
    return args


if __name__ == "__main__":
    sys.exit(UiResilienceTest(parse_args()).run())
