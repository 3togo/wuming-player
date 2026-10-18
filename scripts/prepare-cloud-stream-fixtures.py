#!/usr/bin/env python3
"""Create tiny streaming fixtures and open official cloud upload pages.

Provider credentials stay inside a dedicated Chromium profile under the user's
data directory. The script never exports cookies or writes credentials to the
repository.
"""

from __future__ import annotations

import argparse
import ctypes
import ctypes.util
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import zlib
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "test-fixtures" / "cloud-drive"
PROVIDERS = {
    "quark": {
        "name": "Quark",
        "url": "https://pan.quark.cn/",
        "hosts": ("pan.quark.cn",),
        "color": "0x2d7ff9",
        "tone": 523,
    },
    "uc": {
        "name": "UC Drive",
        "url": "https://drive.uc.cn/",
        "hosts": ("drive.uc.cn",),
        "color": "0xff8a00",
        "tone": 587,
    },
    "alipan": {
        "name": "Aliyun Drive",
        "url": "https://www.alipan.com/drive/file/all",
        "hosts": ("alipan.com", "aliyundrive.com"),
        "color": "0x6c5ce7",
        "tone": 659,
    },
    "baidu": {
        "name": "Baidu Netdisk",
        "url": "https://pan.baidu.com/disk/main#/index?category=all",
        "hosts": ("pan.baidu.com",),
        "color": "0x06a7ff",
        "tone": 698,
    },
}


def private_directory(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path


def data_home() -> Path:
    configured = os.environ.get("XDG_DATA_HOME")
    return Path(configured).expanduser() if configured else Path.home() / ".local" / "share"


def browser_binary(explicit: str | None) -> str:
    if explicit:
        resolved = shutil.which(explicit) if "/" not in explicit else explicit
        if resolved and Path(resolved).exists():
            return str(resolved)
        raise SystemExit(f"Browser not found: {explicit}")
    for name in ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable"):
        resolved = shutil.which(name)
        if resolved:
            return resolved
    raise SystemExit("Chromium or Google Chrome is required for QR login and upload")


def require_tool(name: str) -> str:
    value = shutil.which(name)
    if not value:
        raise SystemExit(f"Required command is missing: {name}")
    return value


def fixture_path(provider: str) -> Path:
    return FIXTURE_DIR / f"wuming-{provider}-stream-test.mp4"


def generate_fixture(provider: str, overwrite: bool) -> Path:
    target = fixture_path(provider)
    if target.exists() and not overwrite:
        validate_fixture(target)
        return target
    ffmpeg = require_tool("ffmpeg")
    settings = PROVIDERS[provider]
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i",
        f"color=c={settings['color']}:s=320x180:r=15:d=2",
        "-f", "lavfi", "-i", f"sine=frequency={settings['tone']}:duration=2",
        "-shortest", "-c:v", "libx264", "-profile:v", "baseline",
        "-level", "3.0", "-pix_fmt", "yuv420p", "-preset", "veryfast",
        "-crf", "30", "-c:a", "aac", "-b:a", "48k", "-ac", "1",
        "-movflags", "+faststart", str(target),
    ]
    try:
        subprocess.run(command, check=True)
    except subprocess.CalledProcessError:
        target.unlink(missing_ok=True)
        raise SystemExit(f"ffmpeg failed while creating {target.name}")
    target.chmod(0o600)
    validate_fixture(target)
    return target


def validate_fixture(path: Path) -> None:
    ffprobe = require_tool("ffprobe")
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "format=duration,size",
         "-show_entries", "stream=codec_type", "-of", "json", str(path)],
        check=True, capture_output=True, text=True,
    )
    metadata = json.loads(result.stdout)
    stream_types = {stream.get("codec_type") for stream in metadata.get("streams", [])}
    size = int(metadata.get("format", {}).get("size", "0"))
    duration = float(metadata.get("format", {}).get("duration", "0"))
    if "video" not in stream_types or duration < 1.0 or not 0 < size <= 1_000_000:
        raise SystemExit(f"Invalid fixture generated at {path}")


def state_path(base: Path) -> Path:
    return base / "shares.json"


def load_state(base: Path) -> dict[str, str]:
    path = state_path(base)
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return {str(k): str(v) for k, v in value.items() if k in PROVIDERS}
    except (OSError, ValueError, TypeError):
        raise SystemExit(f"Invalid state file: {path}")


def valid_share_url(provider: str, value: str) -> bool:
    try:
        parsed = urlparse(value)
        host = (parsed.hostname or "").lower()
        return parsed.scheme == "https" and parsed.username is None and parsed.port is None and any(
            host == allowed or host.endswith("." + allowed)
            for allowed in PROVIDERS[provider]["hosts"]
        )
    except ValueError:
        return False


def normalized_share_url(provider: str, copied_text: str) -> str:
    match = re.search(r"https://[^\s<>\"']+", copied_text or "", re.IGNORECASE)
    if not match:
        raise ValueError("No HTTPS URL was found in the copied text")
    value = match.group(0).rstrip(",.;:!?，。；：！？、)]}）】》")
    if not valid_share_url(provider, value):
        raise ValueError(f"The URL is not an accepted {PROVIDERS[provider]['name']} share")

    # Official share dialogs sometimes copy the extraction code beside the URL
    # instead of putting it in the URL. Preserve it as `pwd`, which every current
    # provider share parser understands, so later contract tests remain unattended.
    parsed = urlparse(value)
    query = parse_qsl(parsed.query, keep_blank_values=True)
    if not any(key.lower() == "pwd" for key, _ in query):
        code = re.search(
            r"(?:提取码|提取碼|密码|密碼|pwd|code)\s*[：:]?\s*([A-Za-z0-9]{1,16})",
            copied_text, re.IGNORECASE,
        )
        if code:
            query.append(("pwd", code.group(1)))
    return urlunparse(parsed._replace(query=urlencode(query)))


def detected_provider(copied_text: str) -> str:
    match = re.search(r"https://[^\s<>\"']+", copied_text or "", re.IGNORECASE)
    if not match:
        raise ValueError("No HTTPS URL was found in the copied text")
    value = match.group(0).rstrip(",.;:!?，。；：！？、)]}）】》")
    for provider in PROVIDERS:
        if valid_share_url(provider, value):
            return provider
    raise ValueError("The copied text is not a supported Quark, UC, Ali, or Baidu share")


def record_share(base: Path, provider: str, copied_text: str) -> str:
    try:
        value = normalized_share_url(provider, copied_text)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    state = load_state(base)
    state[provider] = value
    path = state_path(base)
    path.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    path.chmod(0o600)
    print(f"Recorded {provider} share: {value}")
    print(f"Private state file: {path}")
    return value


def capture_shares(base: Path, providers: list[str]) -> None:
    if not sys.stdin.isatty():
        print("\nThis terminal is not interactive; capture each link later with:")
        print(f"  {Path(__file__).name} --capture-share PROVIDER")
        return
    print("\nFor each provider, upload its matching MP4, select the uploaded file,")
    print("choose Share / 分享, create the link, and click Copy link / 复制链接.")
    print("Paste the complete copied message below; the script extracts the URL and code.")
    for provider in providers:
        name = PROVIDERS[provider]["name"]
        while True:
            try:
                copied = input(f"\n{name} copied share text (Enter to skip): ").strip()
            except EOFError:
                return
            if not copied:
                break
            try:
                value = normalized_share_url(provider, copied)
            except ValueError as error:
                print(f"Not recorded: {error}. Copy the provider's share message and try again.")
                continue
            record_share(base, provider, value)
            break


class QrCode(ctypes.Structure):
    _fields_ = [
        ("version", ctypes.c_int),
        ("width", ctypes.c_int),
        ("data", ctypes.POINTER(ctypes.c_ubyte)),
    ]


def qr_png(value: str, target: Path) -> None:
    library_name = ctypes.util.find_library("qrencode")
    if not library_name:
        raise SystemExit("libqrencode is required to generate the handoff QR code")
    library = ctypes.CDLL(library_name)
    library.QRcode_encodeString8bit.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
    library.QRcode_encodeString8bit.restype = ctypes.POINTER(QrCode)
    library.QRcode_free.argtypes = [ctypes.POINTER(QrCode)]
    code = library.QRcode_encodeString8bit(value.encode("utf-8"), 0, 1)
    if not code:
        raise SystemExit("Unable to encode the handoff address as a QR code")
    try:
        width = code.contents.width
        data = code.contents.data
        quiet, scale = 4, 10
        size = (width + quiet * 2) * scale
        rows = bytearray()
        for output_y in range(size):
            module_y = output_y // scale - quiet
            rows.append(0)  # PNG scanline filter: none.
            for output_x in range(size):
                module_x = output_x // scale - quiet
                black = (0 <= module_x < width and 0 <= module_y < width
                         and data[module_y * width + module_x] & 1)
                rows.append(0 if black else 255)

        def chunk(kind: bytes, payload: bytes) -> bytes:
            return (struct.pack(">I", len(payload)) + kind + payload
                    + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))

        png = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
               + chunk(b"IEND", b""))
        target.write_bytes(png)
        target.chmod(0o600)
    finally:
        library.QRcode_free(code)


def lan_address() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.0.2.1", 80))
        address = probe.getsockname()[0]
    except OSError:
        address = socket.gethostbyname(socket.gethostname())
    finally:
        probe.close()
    if address.startswith("127."):
        raise SystemExit("No LAN address found; connect this computer and phone to the same network")
    return address


def qr_share_handoff(base: Path, timeout_seconds: int) -> str | None:
    token = secrets.token_urlsafe(24)
    route = "/" + token
    result: dict[str, str] = {}

    def page(message: str = "") -> bytes:
        notice = f'<p class="notice">{html.escape(message)}</p>' if message else ""
        return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<meta name="referrer" content="no-referrer"><title>無名 fixture share handoff</title>
<style>body{{font:18px system-ui;max-width:42rem;margin:2rem auto;padding:0 1rem;color:#202124}}
textarea{{box-sizing:border-box;width:100%;min-height:10rem;font:16px system-ui;padding:.8rem}}
button{{font:700 18px system-ui;padding:.8rem 1.2rem;margin-top:1rem}}.notice{{color:#b3261e}}</style></head>
<body><h1>Send a cloud share to 無名 tests</h1>{notice}
<p>In the cloud app, choose <b>Share / 分享</b>, then <b>Copy link / 复制链接</b>.
Paste the complete copied message here. The provider and extraction code are detected automatically.</p>
<form method="post" action="{route}"><textarea name="share" required autofocus></textarea>
<button type="submit">Send to computer</button></form>
<p>This temporary page accepts only Quark, UC, Ali, and Baidu share domains.</p></body></html>""".encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        def respond(self, status: int, body: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path != route:
                self.respond(404, b"Not found")
                return
            self.respond(200, page())

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path != route:
                self.respond(404, b"Not found")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if not 0 < length <= 16_384:
                self.respond(400, page("The submitted message is empty or too large."))
                return
            form = dict(parse_qsl(self.rfile.read(length).decode("utf-8", "replace"), keep_blank_values=True))
            copied = form.get("share", "")
            try:
                provider = detected_provider(copied)
                value = normalized_share_url(provider, copied)
                record_share(base, provider, value)
            except ValueError as error:
                self.respond(400, page(str(error)))
                return
            result["provider"] = provider
            result["value"] = value
            body = (f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Share received</title></head><body style="font:20px system-ui;padding:2rem">
<h1>Share received</h1><p>{html.escape(PROVIDERS[provider]['name'])} was saved. You can close this page.</p>
</body></html>""").encode("utf-8")
            self.respond(200, body)

        def log_message(self, format: str, *args: object) -> None:
            pass

    server = ThreadingHTTPServer(("0.0.0.0", 0), Handler)
    server.timeout = 1
    address = f"http://{lan_address()}:{server.server_port}{route}"
    image_path = base / "share-handoff-qr.png"
    qr_png(address, image_path)
    print(f"\nScan this QR code with your phone: {image_path}")
    print(f"Handoff address: {address}")
    print(f"Waiting up to {timeout_seconds // 60} minutes for one supported share...")
    opener = shutil.which("xdg-open")
    if opener:
        subprocess.Popen([opener, str(image_path)], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    deadline = time.monotonic() + timeout_seconds
    try:
        while "value" not in result and time.monotonic() < deadline:
            server.handle_request()
    except KeyboardInterrupt:
        print("\nQR handoff cancelled.")
    finally:
        server.server_close()
        image_path.unlink(missing_ok=True)
    if "value" not in result:
        print("QR handoff expired without receiving a share.")
        return None
    print(f"Received {result['provider']} share from phone.")
    return result["value"]


def launch_browser(executable: str, profile: Path, providers: list[str]) -> None:
    command = [
        executable,
        f"--user-data-dir={profile}",
        "--profile-directory=Default",
        "--new-window",
        *[PROVIDERS[item]["url"] for item in providers],
    ]
    subprocess.Popen(command, stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)


def open_fixture_folder() -> None:
    opener = shutil.which("xdg-open")
    if opener:
        subprocess.Popen([opener, str(FIXTURE_DIR)], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate tiny MP4 fixtures and open official cloud upload pages.")
    parser.add_argument("--provider", action="append", choices=tuple(PROVIDERS),
                        help="prepare one provider; repeat as needed (default: all)")
    parser.add_argument("--browser", help="Chromium/Chrome executable")
    parser.add_argument("--profile-dir", type=Path,
                        default=data_home() / "wuming-cloud-fixtures" / "chromium-profile",
                        help="persistent browser profile used for provider sessions")
    parser.add_argument("--overwrite", action="store_true", help="regenerate existing MP4 files")
    parser.add_argument("--generate-only", action="store_true", help="do not open browser or file manager")
    parser.add_argument("--record-share", nargs=2, metavar=("PROVIDER", "TEXT"),
                        help="extract and store a share URL from copied share text")
    parser.add_argument("--capture-share", choices=tuple(PROVIDERS),
                        help="prompt for one provider's copied share text without opening a browser")
    parser.add_argument("--qr-share", action="store_true",
                        help="show a QR code and receive one copied share message from a phone")
    parser.add_argument("--qr-timeout", type=int, default=600, metavar="SECONDS",
                        help="QR handoff lifetime (default: 600 seconds)")
    parser.add_argument("--no-capture", action="store_true",
                        help="open upload pages without waiting for copied share links")
    parser.add_argument("--show-state", action="store_true", help="print fixture and recorded-share state")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    providers = list(dict.fromkeys(args.provider or PROVIDERS.keys()))
    profile = private_directory(args.profile_dir.expanduser().resolve())
    base = profile

    if args.record_share:
        provider, value = args.record_share
        if provider not in PROVIDERS:
            raise SystemExit("Unknown provider: " + provider)
        record_share(base, provider, value)
        return 0
    if args.capture_share:
        capture_shares(base, [args.capture_share])
        return 0
    if args.qr_share:
        if args.qr_timeout < 10:
            raise SystemExit("--qr-timeout must be at least 10 seconds")
        return 0 if qr_share_handoff(base, args.qr_timeout) else 1

    paths = {provider: generate_fixture(provider, args.overwrite) for provider in providers}
    state = load_state(base)
    for provider, path in paths.items():
        share = state.get(provider, "not recorded")
        print(f"{provider:7} {path} ({path.stat().st_size} bytes); share: {share}")

    print(f"\nPersistent login profile: {profile}")
    print("The directory is mode 0700. Chromium manages its session data inside it.")
    if args.show_state or args.generate_only:
        return 0

    executable = browser_binary(args.browser)
    launch_browser(executable, profile, providers)
    open_fixture_folder()
    print("\nIn each official tab, scan the QR code if requested and upload the")
    print("matching wuming-<provider>-stream-test.mp4 file from the opened folder.")
    print("Later runs reuse the dedicated browser profile, so QR login is normally one-time.")
    if not args.no_capture:
        capture_shares(base, providers)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
