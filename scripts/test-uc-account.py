#!/usr/bin/env python3
"""Test a UC Drive account with the reference UCApi save-first workflow.

Protocol reference:
https://github.com/lushunming/AndroidCatVodSpider/blob/
ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/UCApi.java

The cookie is never accepted as a command-line argument, printed, or placed in
an exception. With --share-url the selected video is saved into the user's UC
account before a download URL is requested. The saved file is kept by default.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import getpass
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Optional


API = "https://pc-api.uc.cn/1/clouddrive/"
REFERER = "https://drive.uc.cn/"
API_QUERY = (
    "pr=UCBrowser&fr=pc&sys=darwin&ve=1.8.6"
    "&ut=Nk27FcCv6q1eo6rXz8QHR/nIG6qLA3jh7KdL+agFgcOvww=="
)
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 "
    "Mobile Safari/537.36"
)
VIDEO_EXTENSIONS = {
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts"
}


@dataclass
class UcApiError(Exception):
    endpoint: str
    http_status: int
    code: str
    provider_message: str

    def __str__(self) -> str:
        detail = ""
        if self.code:
            detail += f", code {self.code}"
        if self.provider_message:
            detail += f": {self.provider_message}"
        return f"UC {self.endpoint} failed (HTTP {self.http_status}{detail})"


class CookieState:
    def __init__(self, header: str) -> None:
        if "\r" in header or "\n" in header:
            raise ValueError("Cookie must be a single line")
        self.values: OrderedDict[str, str] = OrderedDict()
        for part in header.split(";"):
            name, separator, value = part.strip().partition("=")
            if not separator or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name):
                continue
            if value:
                self.values[name] = value
        if not self.values.get("__pus"):
            raise ValueError("UC cookie does not contain the required __pus session")

    def header(self) -> str:
        return ";".join(f"{name}={value}" for name, value in self.values.items())

    def refresh(self, set_cookie_headers: Iterable[str]) -> None:
        for header in set_cookie_headers:
            match = re.match(r"\s*__puus=([^;\r\n]+)", header)
            if match:
                self.values["__puus"] = match.group(1)


class UcClient:
    def __init__(self, cookie: str, timeout: float) -> None:
        self.cookies = CookieState(cookie)
        self.timeout = timeout

    @staticmethod
    def api_url(endpoint: str, pairs: Iterable[tuple[str, str]] = ()) -> str:
        if not re.fullmatch(r"[a-z0-9/_-]+", endpoint):
            raise ValueError("Invalid UC endpoint")
        query = API_QUERY
        encoded = urllib.parse.urlencode(list(pairs), safe=",:")
        if encoded:
            query += "&" + encoded
        return API + endpoint + "?" + query

    def get(self, endpoint: str, pairs: Iterable[tuple[str, str]] = ()) -> dict[str, Any]:
        return self._request(endpoint, self.api_url(endpoint, pairs), None)

    def post(
        self,
        endpoint: str,
        body: Mapping[str, Any],
        pairs: Iterable[tuple[str, str]] = (),
    ) -> dict[str, Any]:
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return self._request(endpoint, self.api_url(endpoint, pairs), payload)

    def _request(self, endpoint: str, url: str, payload: Optional[bytes]) -> dict[str, Any]:
        headers = {
            "User-Agent": USER_AGENT,
            "Referer": REFERER,
            "Content-Type": "application/json",
            "Cookie": self.cookies.header(),
        }
        request = urllib.request.Request(
            url, data=payload, headers=headers, method="POST" if payload is not None else "GET"
        )
        status = 0
        response_headers: Any = None
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status = response.status
                response_headers = response.headers
                content = response.read(5 * 1024 * 1024 + 1)
        except urllib.error.HTTPError as error:
            status = error.code
            response_headers = error.headers
            content = error.read(64 * 1024)
        except urllib.error.URLError as error:
            reason = str(error.reason).replace(self.cookies.header(), "[redacted]")
            raise RuntimeError(f"Network request to UC {endpoint} failed: {reason}") from error

        if response_headers is not None:
            self.cookies.refresh(response_headers.get_all("Set-Cookie", []))
        if len(content) > 5 * 1024 * 1024:
            raise RuntimeError(f"UC {endpoint} returned an oversized response")
        root = parse_json(content)
        if not 200 <= status < 300:
            raise api_error(endpoint, status, root)
        return root

    def check_download(self, url: str) -> tuple[int, str]:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise RuntimeError("UC returned an invalid download URL")
        headers = {
            "User-Agent": USER_AGENT,
            "Origin": "https://drive.uc.cn",
            "Referer": REFERER,
            "Cookie": self.cookies.header(),
            "Range": "bytes=0-0",
        }
        request = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                response.read(1)
                return response.status, response.headers.get_content_type()
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"UC download probe failed (HTTP {error.code})") from error


def parse_json(content: bytes) -> dict[str, Any]:
    try:
        value = json.loads(content.decode("utf-8", errors="replace"))
        return value if isinstance(value, dict) else {}
    except json.JSONDecodeError:
        return {}


def api_error(endpoint: str, status: int, root: Mapping[str, Any]) -> UcApiError:
    code = root.get("code", root.get("errno", ""))
    message = root.get("message", root.get("msg", ""))
    return UcApiError(endpoint, status, str(code) if code != "" else "", clean_message(message))


def clean_message(value: Any) -> str:
    return re.sub(r"[\x00-\x1f\x7f]+", " ", str(value)).strip()[:200]


def require_data(root: Mapping[str, Any], endpoint: str) -> Any:
    if "data" in root and root["data"] is not None:
        return root["data"]
    raise api_error(endpoint, 200, root)


def share_id(url: str) -> str:
    parsed = urllib.parse.urlsplit(url.strip())
    if parsed.scheme != "https" or parsed.hostname != "drive.uc.cn" or parsed.port is not None:
        raise ValueError("Share URL must use https://drive.uc.cn/s/...")
    match = re.fullmatch(r"/s/([A-Za-z0-9_-]{6,128})/?", parsed.path)
    if not match:
        raise ValueError("Invalid UC share URL")
    return match.group(1)


def account_root(client: UcClient) -> list[dict[str, Any]]:
    root = client.get(
        "file/sort",
        (("pdir_fid", "0"), ("_page", "1"), ("_size", "200"),
         ("_sort", "file_type:asc,updated_at:desc")),
    )
    data = require_data(root, "file/sort")
    values = data.get("list", []) if isinstance(data, dict) else []
    if not isinstance(values, list):
        raise RuntimeError("UC account response did not contain a file list")
    return [item for item in values if isinstance(item, dict)]


def ensure_folder(client: UcClient, values: list[dict[str, Any]], name: str) -> str:
    for item in values:
        if item.get("dir") is True and item.get("file_name") == name and item.get("fid"):
            return str(item["fid"])
    root = client.post(
        "file",
        {"pdir_fid": "0", "file_name": name, "dir_path": "", "dir_init_lock": "false"},
    )
    data = require_data(root, "file")
    if not isinstance(data, dict) or not data.get("fid"):
        raise RuntimeError("UC did not return the created test folder ID")
    return str(data["fid"])


def get_share_token(client: UcClient, sid: str, passcode: str) -> str:
    root = client.post("share/sharepage/token", {"pwd_id": sid, "passcode": passcode})
    data = require_data(root, "share/sharepage/token")
    if not isinstance(data, dict) or not data.get("stoken"):
        raise RuntimeError("UC did not return a share token")
    return str(data["stoken"])


def is_video(item: Mapping[str, Any]) -> bool:
    if item.get("dir") is True:
        return False
    if item.get("obj_category") == "video":
        return True
    name = str(item.get("file_name", ""))
    extension = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    return extension in VIDEO_EXTENSIONS


def list_share_videos(client: UcClient, sid: str, stoken: str) -> list[dict[str, Any]]:
    videos: list[dict[str, Any]] = []
    pending: deque[str] = deque(["0"])
    visited = 0
    while pending:
        folder = pending.popleft()
        visited += 1
        if visited > 256:
            raise RuntimeError("UC share contains more than 256 folders")
        for page in range(1, 21):
            root = client.get(
                "share/sharepage/detail",
                (("pwd_id", sid), ("stoken", stoken), ("pdir_fid", folder),
                 ("force", "0"), ("_page", str(page)), ("_size", "200"),
                 ("_sort", "file_type:asc,file_name:asc")),
            )
            data = require_data(root, "share/sharepage/detail")
            values = data.get("list", []) if isinstance(data, dict) else []
            if not isinstance(values, list):
                break
            for item in values:
                if not isinstance(item, dict):
                    continue
                if item.get("dir") is True and item.get("fid"):
                    pending.append(str(item["fid"]))
                elif is_video(item):
                    videos.append(item)
            if len(values) < 200:
                break
    return videos


def save_video(
    client: UcClient,
    sid: str,
    stoken: str,
    item: Mapping[str, Any],
    folder_id: str,
    polls: int,
) -> str:
    fid = str(item.get("fid", ""))
    file_token = str(item.get("share_fid_token", ""))
    if not fid or not file_token:
        raise RuntimeError("Selected UC video lacks fid/share_fid_token")
    root = client.post(
        "share/sharepage/save",
        {
            "fid_list": [fid],
            "fid_token_list": [file_token],
            "to_pdir_fid": folder_id,
            "pwd_id": sid,
            "stoken": stoken,
            "pdir_fid": "0",
            "scene": "link",
        },
    )
    data = require_data(root, "share/sharepage/save")
    task_id = str(data.get("task_id", "")) if isinstance(data, dict) else ""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", task_id):
        raise RuntimeError("UC did not return a valid save task ID")

    for retry in range(polls):
        task = client.get("task", (("task_id", task_id), ("retry_index", str(retry))))
        task_data = require_data(task, "task")
        if not isinstance(task_data, dict):
            raise RuntimeError("UC task response was malformed")
        save_as = task_data.get("save_as", {})
        saved = save_as.get("save_as_top_fids", []) if isinstance(save_as, dict) else []
        if isinstance(saved, list) and saved:
            return str(saved[0])
        status = int(task_data.get("status", 0) or 0)
        if status >= 2:
            raise RuntimeError(f"UC save task ended without a saved file (status {status})")
        if retry + 1 < polls:
            time.sleep(1)
    raise RuntimeError("UC save task timed out")


def download_url(client: UcClient, fid: str) -> str:
    root = client.post("file/download", {"fids": [fid]}, (("uc_param_str", ""),))
    data = require_data(root, "file/download")
    if not isinstance(data, list) or not data or not isinstance(data[0], dict):
        raise RuntimeError("UC did not return download metadata")
    url = str(data[0].get("download_url", ""))
    if urllib.parse.urlsplit(url).scheme != "https":
        raise RuntimeError("UC did not return a valid HTTPS download URL")
    return url


def delete_saved(client: UcClient, fid: str) -> None:
    client.post(
        "file/delete",
        {"action_type": 2, "filelist": [fid], "exclude_fids": []},
        (("uc_param_str", ""),),
    )


def load_cookie(path: Optional[str]) -> str:
    if path:
        warn_open_permissions(path, "cookie")
        with open(path, "r", encoding="utf-8") as source:
            return source.read().strip()
    value = os.environ.get("UC_COOKIE", "").strip()
    if value:
        return value
    if not sys.stdin.isatty():
        raise ValueError("Use --cookie-file, set UC_COOKIE, or run from an interactive terminal")
    return getpass.getpass("UC cookie (hidden): ").strip()


def load_payload(path: str) -> tuple[str, str, dict[str, Any]]:
    warn_open_permissions(path, "payload")
    with open(path, "r", encoding="utf-8") as source:
        content = source.read().strip()
    if not content or "\r" in content or "\n" in content:
        raise ValueError("UC payload file must contain one non-empty line")
    value: Any = None
    candidates = [content]
    try:
        candidates.append(base64.b64decode(content, validate=True).decode("utf-8"))
    except (ValueError, binascii.Error, UnicodeDecodeError):
        pass
    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                value = parsed
                break
        except json.JSONDecodeError:
            continue
    if isinstance(value, dict):
        fid = first(value, "fid", "file_id", "fileId")
        file_token = first(value, "share_fid_token", "fid_token", "fileToken")
        sid = first(value, "sId", "share_id", "shareId", "pwd_id")
        stoken = first(value, "sToken", "stoken", "share_token", "shareToken")
        name = first(value, "file_name", "name") or "payload video"
    else:
        parts = content.split("++", 3)
        if len(parts) != 4:
            raise ValueError("Payload must be UC JSON/base64 JSON or fid++fileToken++shareId++stoken")
        fid, file_token, sid, stoken = parts
        name = "payload video"
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,512}", fid):
        raise ValueError("Payload contains an invalid UC file ID")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,512}", sid):
        raise ValueError("Payload contains an invalid UC share ID")
    for label, token in (("file token", file_token), ("share token", stoken)):
        if not token or len(token) > 4096 or re.search(r"[\x00-\x1f\x7f]", token):
            raise ValueError(f"Payload contains an invalid UC {label}")
    return sid, stoken, {"fid": fid, "share_fid_token": file_token, "file_name": name}


def first(value: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        item = value.get(key)
        if item is not None and str(item).strip():
            return str(item).strip()
    return ""


def warn_open_permissions(path: str, label: str) -> None:
    try:
        if os.stat(path).st_mode & 0o077:
            print(
                f"WARNING: {label} file is readable by other users; consider chmod 600 {path!r}",
                file=sys.stderr,
            )
    except OSError:
        pass


def human_size(value: Any) -> str:
    try:
        size = int(value)
    except (TypeError, ValueError):
        return "unknown size"
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if size < 1024 or unit == "TiB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.2f} {unit}"
        size /= 1024
    return "unknown size"


def self_test() -> None:
    task_url = UcClient.api_url("task", (("task_id", "task-123"), ("retry_index", "0")))
    expected = (
        API + "task?" + API_QUERY + "&task_id=task-123&retry_index=0"
    )
    if task_url != expected or "%3D%3D" in task_url:
        raise AssertionError(f"Reference task URL mismatch: {task_url}")
    error = api_error("task", 400, {"code": 32011, "message": "user NOT real name"})
    if error.code != "32011":
        raise AssertionError("UC error parser failed")
    print("Self-test passed: reference query and UC error parsing are correct.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Test UC cookie, save a shared video to the account, then probe playback."
    )
    parser.add_argument("--cookie-file", help="File containing one raw UC Cookie header line")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--share-url", help="UC share URL (https://drive.uc.cn/s/...)")
    source.add_argument(
        "--payload-file",
        help="File containing UC JSON/base64 JSON or fid++fileToken++shareId++stoken",
    )
    parser.add_argument("--passcode", default="", help="Share passcode, if required")
    parser.add_argument("--file-index", type=int, default=0, help="Video index to save (default: 0)")
    parser.add_argument("--save-folder", default="TVBoxExternalTest", help="Root folder for saved test files")
    parser.add_argument("--polls", type=int, default=10, help="Maximum task polls (default: 10)")
    parser.add_argument("--timeout", type=float, default=20.0, help="HTTP timeout seconds (default: 20)")
    parser.add_argument(
        "--cleanup", action="store_true",
        help="Delete the exact saved test file after the download probe; folder is retained",
    )
    parser.add_argument("--self-test", action="store_true", help="Test script logic without credentials")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.file_index < 0 or args.polls < 1 or not 1 <= args.timeout <= 120:
        raise ValueError("file-index, polls, or timeout is outside the allowed range")
    if not re.fullmatch(r"[^/\\\x00-\x1f]{1,80}", args.save_folder):
        raise ValueError("Invalid save folder name")

    client = UcClient(load_cookie(args.cookie_file), args.timeout)
    print("[1/5] Verifying UC account cookie and listing account root...")
    root_items = account_root(client)
    print(f"      OK: authenticated; root contains {len(root_items)} item(s).")
    if not args.share_url and not args.payload_file:
        print("Account test passed. Add --share-url or --payload-file to test save-first playback.")
        return 0

    if args.payload_file:
        print("[2/5] Loading exact UC episode payload (capabilities remain redacted)...")
        sid, stoken, selected = load_payload(args.payload_file)
        print("      OK: file/share identifiers and capability tokens are present.")
    else:
        sid = share_id(args.share_url)
        print("[2/5] Resolving share token and listing videos...")
        stoken = get_share_token(client, sid, args.passcode)
        videos = list_share_videos(client, sid, stoken)
        if not videos:
            raise RuntimeError("No video was found in the UC share")
        for index, video in enumerate(videos):
            print(f"      [{index}] {video.get('file_name', 'Unnamed video')} ({human_size(video.get('size'))})")
        if args.file_index >= len(videos):
            raise ValueError(f"--file-index must be between 0 and {len(videos) - 1}")
        selected = videos[args.file_index]

    print(f"[3/5] Ensuring account folder {args.save_folder!r}...")
    folder_id = ensure_folder(client, root_items, args.save_folder)
    print(f"[4/5] Saving {selected.get('file_name', 'selected video')!r} into the UC account...")
    saved_fid = save_video(client, sid, stoken, selected, folder_id, args.polls)
    print("      OK: UC returned the saved account file ID.")

    print("[5/5] Requesting saved-file download URL and probing one byte...")
    url = download_url(client, saved_fid)
    status, content_type = client.check_download(url)
    host = urllib.parse.urlsplit(url).hostname or "unknown host"
    print(f"      OK: HTTP {status}, {content_type}, host {host} (signed URL redacted).")
    if args.cleanup:
        print("      Cleanup requested: deleting the exact saved test file...")
        delete_saved(client, saved_fid)
        print("      Deleted saved test file; the test folder was retained.")
    else:
        print(f"      Saved file retained in UC account folder {args.save_folder!r}.")
    print("UC external save-first playback test passed.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except UcApiError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        if error.code == "32011":
            print(
                "DIAGNOSIS: UC accepted the save request but refused the save task because "
                "this account has not completed real-name verification.",
                file=sys.stderr,
            )
        raise SystemExit(4)
    except (ValueError, RuntimeError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
