#!/usr/bin/env python3
"""
Test script for UC/Quark cloud drive cookie bridge.

This script independently tests whether the cookie bridge approach is viable:
1. Can we authenticate with UC/Quark APIs using cookies from a WebView login?
2. Which cookies are actually required for the API to return data?
3. Does domain matching work correctly (e.g., drive.uc.cn vs broccoli.uc.cn)?

Based on real logcat data from the TVBoxOSC app:
- UC API: https://pc-api.uc.cn/1/clouddrive/share/sharepage/detail
- Quark API: https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail
- Baidu API: https://pan.baidu.com/share/verify, /share/list

Usage:
    # Test with no cookies (should fail - no login)
    python3 test_uc_cookie_bridge.py

    # Test with cookies from a real session (paste from adb logcat)
    python3 test_uc_cookie_bridge.py --cookies "__pus=xxx; __sdid=yyy"

    # Test with cookies from a specific domain
    python3 test_uc_cookie_bridge.py --cookies "__pus=xxx" --domain drive.uc.cn

    # Test a specific share link
    python3 test_uc_cookie_bridge.py --share-url "https://drive.uc.cn/s/abc123"

    # Interactive: open a WebView-like login and capture cookies
    python3 test_uc_cookie_bridge.py --interactive
"""

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
import http.cookiejar
import re
from datetime import datetime


# ============================================================
# Constants from real logcat data
# ============================================================

# UC API endpoint (from logcat: pc-api.uc.cn/1/clouddrive/share/sharepage/detail)
UC_API_BASE = "https://pc-api.uc.cn/1/clouddrive/share/sharepage/detail"
UC_SHARE_URL = "https://drive.uc.cn/s/{surl}"
UC_LOGIN_URL = "https://drive.uc.cn/"
UC_BROCCOLI_URL = "https://broccoli.uc.cn/"

# Quark API endpoint (from logcat: drive-pc.quark.cn/1/clouddrive/share/sharepage/detail)
QUARK_API_BASE = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail"
QUARK_SHARE_URL = "https://pan.quark.cn/s/{surl}"
QUARK_LOGIN_URL = "https://pan.quark.cn/"

# Baidu Pan API endpoints (from logcat)
BAIDU_SHARE_URL = "https://pan.baidu.com/s/{surl}"
BAIDU_VERIFY_API = "https://pan.baidu.com/share/verify"
BAIDU_LIST_API = "https://pan.baidu.com/share/list"
BAIDU_LOGIN_URL = "https://pan.baidu.com/"

# Known login cookies per provider
LOGIN_COOKIES = {
    "uc.cn": ["__pus"],
    "quark.cn": ["__puus"],
    "baidu.com": ["BDUSS", "BDCLND"],
    "alipan.com": ["token"],
    "aliyundrive.com": ["token"],
    "115.com": ["CID"],
}

# Tracking cookies that are NOT login indicators (from logcat)
TRACKING_COOKIES = {
    "uc.cn": ["__sdid"],
    "quark.cn": ["__sdid"],
    "baidu.com": ["BAIDUID", "BAIDUID_BFESS", "PANPSC"],
}


# ============================================================
# Cookie parsing and matching logic
# ============================================================

class CookieBridge:
    """Simulates the PersistentCookieJar logic in Python for testing."""

    def __init__(self):
        self.cookies = {}  # key: "host|name" -> {name, value, domain, path, expires_at}

    def add_cookie(self, host, name, value, domain=None, path="/", expires_at=None):
        """Add a cookie (simulates saveFromResponse)."""
        if domain is None:
            domain = host
        if expires_at is None:
            expires_at = int(time.time() * 1000) + 365 * 24 * 60 * 60 * 1000
        key = f"{host.lower()}|{name}"
        self.cookies[key] = {
            "name": name,
            "value": value,
            "domain": domain.lower(),
            "path": path,
            "expires_at": expires_at,
        }
        print(f"  Added cookie: key={key}, domain={domain}")

    def load_for_request(self, url):
        """Load cookies for a URL (simulates loadForRequest with subdomain matching)."""
        parsed = urllib.parse.urlparse(url)
        host = parsed.hostname.lower()

        result = []
        for key, cookie in self.cookies.items():
            pipe = key.find("|")
            if pipe < 0:
                continue
            cookie_host = key[:pipe]

            # Subdomain matching (same logic as PersistentCookieJar)
            if not (host == cookie_host or host.endswith("." + cookie_host)):
                continue

            # Check expiry
            now = int(time.time() * 1000)
            if cookie["expires_at"] < now:
                continue

            result.append(cookie)

        return result

    def has_cookies(self, url):
        """Check if any cookies exist for URL (old, broken logic)."""
        return len(self.load_for_request(url)) > 0

    def has_login_cookies(self, url):
        """Check if login session cookies exist for URL (new, correct logic)."""
        parsed = urllib.parse.urlparse(url)
        host = parsed.hostname.lower()

        # Find which provider this host belongs to
        login_cookie_names = []
        for provider_domain, names in LOGIN_COOKIES.items():
            if host == provider_domain or host.endswith("." + provider_domain):
                login_cookie_names = names
                break

        if not login_cookie_names:
            print(f"  WARNING: No known login cookies for host={host}")
            return False

        cookies = self.load_for_request(url)
        for cookie in cookies:
            if cookie["name"] in login_cookie_names:
                print(f"  Found login cookie: {cookie['name']} for {host}")
                return True

        cookie_names = [c["name"] for c in cookies]
        print(f"  No login cookies found for {host}. Have: {cookie_names}")
        return False

    def sync_from_webview(self, url, raw_cookie_string):
        """Simulate syncFromWebView: parse WebView CookieManager output.
        
        IMPORTANT: Cookies from WebView are stored under the TOP-LEVEL domain
        (e.g., uc.cn, not drive.uc.cn) so that sibling subdomains like
        pc-api.uc.cn can also access them via subdomain matching.
        """
        parsed = urllib.parse.urlparse(url)
        host = parsed.hostname.lower()

        # Extract top-level domain for storage
        # e.g., drive.uc.cn -> uc.cn, pc-api.uc.cn -> uc.cn
        # This ensures sibling subdomains can access the cookies
        storage_domain = self._get_storage_domain(host)

        count = 0
        for pair in raw_cookie_string.split(";"):
            pair = pair.strip()
            eq = pair.find("=")
            if eq <= 0:
                continue
            name = pair[:eq].strip()
            value = pair[eq + 1:].strip()
            self.add_cookie(storage_domain, name, value, domain=storage_domain)
            count += 1

        return count

    def _get_storage_domain(self, host):
        """Get the top-level domain for cookie storage.
        
        For cloud drive providers, we store cookies under the top-level domain
        so that sibling subdomains can access them:
        - drive.uc.cn -> uc.cn (so pc-api.uc.cn can also get the cookies)
        - broccoli.uc.cn -> uc.cn
        - pc-api.uc.cn -> uc.cn
        - drive-pc.quark.cn -> quark.cn
        - pan.quark.cn -> quark.cn
        - pan.baidu.com -> baidu.com
        """
        CLOUD_DOMAINS = ["uc.cn", "quark.cn", "baidu.com", "alipan.com", 
                         "aliyundrive.com", "115.com"]
        for domain in CLOUD_DOMAINS:
            if host == domain or host.endswith("." + domain):
                return domain
        return host

    def dump(self):
        """Dump all cookies for debugging."""
        print(f"\n=== Cookie dump ({len(self.cookies)} cookies) ===")
        for key, cookie in sorted(self.cookies.items()):
            val_preview = cookie["value"][:30] + "..." if len(cookie["value"]) > 30 else cookie["value"]
            print(f"  {key}: domain={cookie['domain']}, value={val_preview}")
        print("=== End dump ===\n")


# ============================================================
# API test functions
# ============================================================

def test_uc_api(share_id, stoken, cookies_str=None, pdir_fid="0"):
    """Test the UC share detail API with optional cookies."""
    params = {
        "pr": "UCBrowser",
        "fr": "pc",
        "uc_param_str": "",
        "pwd_id": share_id,
        "stoken": stoken,
        "pdir_fid": pdir_fid,
        "force": "0",
        "_page": "1",
        "_size": "100",
        "_fetch_banner": "1",
        "_fetch_share": "1",
        "_fetch_total": "1",
        "_sort": "file_type::asc,file_name:asc",
    }
    url = UC_API_BASE + "?" + urllib.parse.urlencode(params)

    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://drive.uc.cn/",
        "Accept": "application/json, text/plain, */*",
    }
    if cookies_str:
        headers["Cookie"] = cookies_str

    print(f"\n--- Testing UC API ---")
    print(f"  URL: {url[:120]}...")
    print(f"  Cookies: {cookies_str[:80] + '...' if cookies_str and len(cookies_str) > 80 else cookies_str}")

    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("status", {})
            code = status.get("code", "unknown")
            msg = status.get("msg", "")
            print(f"  Response: code={code}, msg={msg}")

            if code == 0:
                # Success - list file info
                metadata = data.get("data", {}).get("metadata", {})
                if metadata:
                    for f in metadata[:3]:
                        print(f"    File: {f.get('file_name', '?')} ({f.get('size', '?')} bytes)")
                    if len(metadata) > 3:
                        print(f"    ... and {len(metadata) - 3} more files")
                return True, data
            else:
                print(f"  API returned error: {json.dumps(status, ensure_ascii=False)}")
                return False, data
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:200]
        print(f"  HTTP Error: {e.code} {e.reason}")
        print(f"  Body: {body}")
        return False, {"error": f"HTTP {e.code}"}
    except Exception as e:
        print(f"  Error: {e}")
        return False, {"error": str(e)}


def test_quark_api(share_id, stoken, cookies_str=None, pdir_fid=""):
    """Test the Quark share detail API with optional cookies."""
    params = {
        "pr": "ucpro",
        "fr": "pc",
        "uc_param_str": "",
        "pwd_id": share_id,
        "stoken": stoken,
        "pdir_fid": pdir_fid,
        "force": "0",
        "_page": "1",
        "_size": "100",
        "_fetch_banner": "0",
        "_fetch_share": "0",
        "_fetch_total": "1",
        "_sort": "file_type:asc,file_name:asc",
    }
    url = QUARK_API_BASE + "?" + urllib.parse.urlencode(params)

    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://pan.quark.cn/",
        "Accept": "application/json, text/plain, */*",
    }
    if cookies_str:
        headers["Cookie"] = cookies_str

    print(f"\n--- Testing Quark API ---")
    print(f"  URL: {url[:120]}...")
    print(f"  Cookies: {cookies_str[:80] + '...' if cookies_str and len(cookies_str) > 80 else cookies_str}")

    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("status", {})
            code = status.get("code", "unknown")
            msg = status.get("msg", "")
            print(f"  Response: code={code}, msg={msg}")

            if code == 0:
                metadata = data.get("data", {}).get("metadata", {})
                if metadata:
                    for f in metadata[:3]:
                        print(f"    File: {f.get('file_name', '?')} ({f.get('size', '?')} bytes)")
                return True, data
            else:
                print(f"  API returned error: {json.dumps(status, ensure_ascii=False)}")
                return False, data
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:200]
        print(f"  HTTP Error: {e.code} {e.reason}")
        print(f"  Body: {body}")
        return False, {"error": f"HTTP {e.code}"}
    except Exception as e:
        print(f"  Error: {e}")
        return False, {"error": str(e)}


def test_baidu_api(surl, cookies_str=None, pwd=""):
    """Test the Baidu Pan share API with optional cookies."""
    # First, verify the share
    params = {
        "t": str(int(time.time() * 1000)),
        "surl": surl,
        "channel": "chunlei",
        "web": "1",
        "bdstoken": "",
        "logid": "",
        "clienttype": "0",
    }
    url = BAIDU_VERIFY_API + "?" + urllib.parse.urlencode(params)

    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://pan.baidu.com/",
        "Accept": "application/json, text/plain, */*",
    }
    if cookies_str:
        headers["Cookie"] = cookies_str

    print(f"\n--- Testing Baidu Pan API ---")
    print(f"  URL: {url[:120]}...")
    print(f"  Cookies: {cookies_str[:80] + '...' if cookies_str and len(cookies_str) > 80 else cookies_str}")

    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"  Response: {json.dumps(data, ensure_ascii=False)[:200]}")
            return data.get("errno", -1) == 0, data
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:200]
        print(f"  HTTP Error: {e.code} {e.reason}")
        return False, {"error": f"HTTP {e.code}"}
    except Exception as e:
        print(f"  Error: {e}")
        return False, {"error": str(e)}


# ============================================================
# Cookie bridge simulation tests
# ============================================================

def test_cookie_domain_matching():
    """Test that subdomain matching works correctly."""
    print("\n" + "=" * 60)
    print("TEST: Cookie domain matching")
    print("=" * 60)

    bridge = CookieBridge()

    # Simulate cookies from spider API calls (from real logcat data)
    print("\n1. Adding tracking cookies from spider API calls:")
    bridge.add_cookie("uc.cn", "__sdid", "AAQaJr8awTQqCkgkR1B6", domain="uc.cn")
    bridge.add_cookie("quark.cn", "__sdid", "AATvZADu/dnN2mxxZQls", domain="quark.cn")
    bridge.add_cookie("baidu.com", "BAIDUID", "C6C66CE4D18CD8CB5E4D", domain="baidu.com")
    bridge.add_cookie("baidu.com", "BAIDUID_BFESS", "C6C66CE4D18CD8CB5E4D", domain="baidu.com")
    bridge.add_cookie("pan.baidu.com", "PANPSC", "12023239526244352012", domain="pan.baidu.com")
    bridge.add_cookie("pan.baidu.com", "BDCLND", "ZCBLkVQK8qMRFbLrfAn8", domain="pan.baidu.com")
    bridge.add_cookie("pan.baidu.com", "csrfToken", "mIsFbz_0zxpn2cc8pnOC", domain="pan.baidu.com")

    # Test: hasCookies (old, broken) vs hasLoginCookies (new, correct)
    print("\n2. Testing hasCookies (old logic - any cookie):")
    for url in [UC_LOGIN_URL, QUARK_LOGIN_URL, BAIDU_LOGIN_URL]:
        result = bridge.has_cookies(url)
        print(f"  hasCookies({url}) = {result}  <-- WRONG: tracking cookies make this True")

    print("\n3. Testing hasLoginCookies (new logic - login cookies only):")
    for url in [UC_LOGIN_URL, QUARK_LOGIN_URL, BAIDU_LOGIN_URL]:
        result = bridge.has_login_cookies(url)
        print(f"  hasLoginCookies({url}) = {result}  <-- CORRECT: no login cookies")

    # Now add login cookies (simulating WebView login)
    print("\n4. Adding login cookies (simulating WebView login):")
    bridge.add_cookie("uc.cn", "__pus", "FAKE_UC_LOGIN_TOKEN_12345", domain="uc.cn")
    bridge.add_cookie("quark.cn", "__puus", "FAKE_QUARK_LOGIN_TOKEN_12345", domain="quark.cn")
    bridge.add_cookie("baidu.com", "BDUSS", "FAKE_BAIDU_LOGIN_TOKEN_12345", domain="baidu.com")

    print("\n5. Testing hasLoginCookies after adding login cookies:")
    for url in [UC_LOGIN_URL, QUARK_LOGIN_URL, BAIDU_LOGIN_URL]:
        result = bridge.has_login_cookies(url)
        print(f"  hasLoginCookies({url}) = {result}  <-- CORRECT: login cookies found")

    # Test subdomain matching
    print("\n6. Testing subdomain matching (pc-api.uc.cn should get uc.cn cookies):")
    uc_api_url = "https://pc-api.uc.cn/1/clouddrive/share/sharepage/detail?pr=UCBrowser"
    cookies = bridge.load_for_request(uc_api_url)
    print(f"  Cookies for pc-api.uc.cn: {[c['name'] for c in cookies]}")
    assert any(c["name"] == "__pus" for c in cookies), "FAIL: __pus not found for pc-api.uc.cn"
    print(f"  __pus found for pc-api.uc.cn: PASS")

    print("\n7. Testing subdomain matching (drive-pc.quark.cn should get quark.cn cookies):")
    quark_api_url = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro"
    cookies = bridge.load_for_request(quark_api_url)
    print(f"  Cookies for drive-pc.quark.cn: {[c['name'] for c in cookies]}")
    assert any(c["name"] == "__puus" for c in cookies), "FAIL: __puus not found for drive-pc.quark.cn"
    print(f"  __puus found for drive-pc.quark.cn: PASS")

    print("\n8. Testing subdomain matching (pan.baidu.com should get baidu.com cookies):")
    baidu_api_url = "https://pan.baidu.com/share/list?web=1"
    cookies = bridge.load_for_request(baidu_api_url)
    print(f"  Cookies for pan.baidu.com: {[c['name'] for c in cookies]}")
    assert any(c["name"] == "BDUSS" for c in cookies), "FAIL: BDUSS not found for pan.baidu.com"
    print(f"  BDUSS found for pan.baidu.com: PASS")

    bridge.dump()
    return True


def test_episode_url_detection():
    """Test that cloud drive detection works for actual episode URL formats."""
    print("\n" + "=" * 60)
    print("TEST: Episode URL detection")
    print("=" * 60)

    # Real episode URL from logcat
    uc_episode_url = json.dumps({
        "pg": "",
        "parent": "[Y 野狗-骨头]",
        "sId": "8599b47d65854",
        "sToken": "PRxb987z+r7PAJSOmUtkGU3Eh7zeBQvQAfwvAFOZixc=",
        "shareUser": "6d2a",
        "share_fid_token": "6a0819c7bef25e5b5d0aa69a665aca87",
        "fid": "8750ac8cb12c4348899ef3f8b98fea56",
        "vodName": "null",
        "pdir_fid": "7c8923593d864fea9f26b8f8dbea9ac7",
        "sub": "",
        "size": 5416783439,
        "flag": "优汐盘",
        "file_name": "Love.For.You.2026.S01E01.2160p.WEB-DL.H.264.AAC.mp4"
    })

    # Test detection logic (Python equivalent of detectCloudDrive)
    def detect_cloud_drive(episode_url):
        try:
            data = json.loads(episode_url)
        except (json.JSONDecodeError, TypeError):
            return None

        # UC/Quark API pattern: sId + sToken
        s_id = data.get("sId", "")
        s_token = data.get("sToken", "")
        if s_id and s_token:
            flag_name = data.get("flag", "").lower()
            if "quark" in flag_name or "夸克" in flag_name:
                return {"shareUrl": "https://pan.quark.cn/", "loginUrl": "https://pan.quark.cn/"}
            return {"shareUrl": "https://drive.uc.cn/", "loginUrl": "https://drive.uc.cn/"}

        # UC legacy: surl
        surl = data.get("surl", "")
        if surl:
            return {"shareUrl": f"https://drive.uc.cn/s/{surl}", "loginUrl": "https://drive.uc.cn/"}

        # share_url / url
        share_url = data.get("share_url", "") or data.get("url", "")
        if share_url and share_url.startswith("http"):
            for domain in ["quark.cn", "alipan.com", "aliyundrive.com", "pan.baidu.com", "115.com", "drive.uc.cn"]:
                if domain in share_url:
                    parsed = urllib.parse.urlparse(share_url)
                    return {"shareUrl": share_url, "loginUrl": f"{parsed.scheme}://{parsed.hostname}/"}

        return None

    # Test cases
    test_cases = [
        (uc_episode_url, "UC (sId/sToken pattern)", "https://drive.uc.cn/"),
        (json.dumps({"surl": "abc123"}), "UC legacy (surl)", "https://drive.uc.cn/"),
        (json.dumps({"sId": "xxx", "sToken": "yyy", "flag": "夸克网盘"}), "Quark (flag=夸克)", "https://pan.quark.cn/"),
        (json.dumps({"share_url": "https://pan.baidu.com/s/abc"}), "Baidu (share_url)", "https://pan.baidu.com/"),
        (json.dumps({"url": "https://pan.quark.cn/s/abc"}), "Quark (url)", "https://pan.quark.cn/"),
        ("https://example.com/video.mp4", "Plain URL (not cloud drive)", None),
        (json.dumps({"foo": "bar"}), "JSON without cloud drive fields", None),
    ]

    for episode_url, desc, expected_login_url in test_cases:
        result = detect_cloud_drive(episode_url)
        login_url = result["loginUrl"] if result else None
        status = "PASS" if login_url == expected_login_url else "FAIL"
        print(f"  [{status}] {desc}: loginUrl={login_url}")

    return True


def test_webview_cookie_sync():
    """Test that WebView cookie sync works correctly."""
    print("\n" + "=" * 60)
    print("TEST: WebView cookie sync simulation")
    print("=" * 60)

    bridge = CookieBridge()

    # Simulate what WebView CookieManager.getCookie() would return after UC login
    # This is what syncFromWebView receives
    uc_webview_cookies = "__pus=FAKE_PUS_VALUE; __sdid=AAQaJr8awTQqCkgkR1B6; locale=zh-CN"

    print("\n1. Syncing cookies from WebView (drive.uc.cn):")
    count = bridge.sync_from_webview("https://drive.uc.cn/", uc_webview_cookies)
    print(f"  Synced {count} cookies")

    # Also sync from broccoli.uc.cn (UC redirect domain)
    broccoli_webview_cookies = "__pus=FAKE_PUS_VALUE_BROCCOLI; __sdid=AAQaJr8awTQqCkgkR1B6"
    print("\n2. Syncing cookies from WebView (broccoli.uc.cn):")
    count = bridge.sync_from_webview("https://broccoli.uc.cn/", broccoli_webview_cookies)
    print(f"  Synced {count} cookies")

    # Check if login is detected
    print("\n3. Checking login state:")
    has_login = bridge.has_login_cookies("https://drive.uc.cn/")
    print(f"  hasLoginCookies(drive.uc.cn) = {has_login}")

    # Check if cookies are available for API calls
    print("\n4. Checking cookies for API endpoint (pc-api.uc.cn):")
    api_cookies = bridge.load_for_request("https://pc-api.uc.cn/1/clouddrive/share/sharepage/detail")
    print(f"  Cookies: {[c['name'] for c in api_cookies]}")
    has_pus = any(c["name"] == "__pus" for c in api_cookies)
    print(f"  __pus available for API: {has_pus}")

    bridge.dump()
    return has_login and has_pus


def test_api_without_login():
    """Test that UC/Quark APIs fail without login cookies."""
    print("\n" + "=" * 60)
    print("TEST: API calls without login (should fail or return limited data)")
    print("=" * 60)

    # Use a real share ID from logcat data
    # UC: pwd_id=8599b47d65854, stoken=PRxb987z+r7PAJSOmUtkGU3Eh7zeBQvQAfwvAFOZixc=
    # Quark: pwd_id=5084a4108d28, stoken=eHEo/OvqxVgPLWPgQ67LupO1OqLqF5hXis3ry0qrvXc=

    print("\n1. Testing UC API without cookies:")
    uc_ok, uc_data = test_uc_api("8599b47d65854", "PRxb987z+r7PAJSOmUtkGU3Eh7zeBQvQAfwvAFOZixc=")

    print("\n2. Testing Quark API without cookies:")
    quark_ok, quark_data = test_quark_api("5084a4108d28", "eHEo/OvqxVgPLWPgQ67LupO1OqLqF5hXis3ry0qrvXc=")

    print("\n3. Testing Baidu Pan API without cookies:")
    baidu_ok, baidu_data = test_baidu_api("zheHRyhk2x1eHvXkzDL-SA")

    # These should all fail or return limited data without login
    if not uc_ok and not quark_ok:
        print("\n  Result: APIs correctly require login. Cookie bridge is needed.")
    else:
        print("\n  Result: Some APIs work without login. Cookie bridge may not be needed for those.")

    return True


def test_api_with_cookies(cookies_str, provider="uc"):
    """Test API calls with real cookies from user."""
    print("\n" + "=" * 60)
    print(f"TEST: API calls with real cookies ({provider})")
    print("=" * 60)

    if provider == "uc":
        return test_uc_api("8599b47d65854", "PRxb987z+r7PAJSOmUtkGU3Eh7zeBQvQAfwvAFOZixc=", cookies_str)
    elif provider == "quark":
        return test_quark_api("5084a4108d28", "eHEo/OvqxVgPLWPgQ67LupO1OqLqF5hXis3ry0qrvXc=", cookies_str)
    elif provider == "baidu":
        return test_baidu_api("zheHRyhk2x1eHvXkzDL-SA", cookies_str)
    else:
        print(f"  Unknown provider: {provider}")
        return False, {}


def test_full_flow(cookies_str=None, provider="uc"):
    """Test the complete cookie bridge flow end-to-end."""
    print("\n" + "=" * 60)
    print("TEST: Full cookie bridge flow")
    print("=" * 60)

    # Step 1: Episode URL detection
    print("\nStep 1: Episode URL detection")
    test_episode_url_detection()

    # Step 2: Cookie domain matching
    print("\nStep 2: Cookie domain matching")
    test_cookie_domain_matching()

    # Step 3: WebView cookie sync
    print("\nStep 3: WebView cookie sync")
    test_webview_cookie_sync()

    # Step 4: API test (with or without real cookies)
    print("\nStep 4: API test")
    if cookies_str:
        test_api_with_cookies(cookies_str, provider)
    else:
        test_api_without_login()

    print("\n" + "=" * 60)
    print("SUMMARY")
    print("=" * 60)
    print("""
The cookie bridge approach requires:
1. Detecting cloud drive episode URLs (sId/sToken pattern) - TESTED
2. Checking for login cookies (__pus for UC, __puus for Quark) - TESTED
3. Syncing cookies from WebView to OkHttp - TESTED (simulation)
4. Subdomain matching (pc-api.uc.cn gets uc.cn cookies) - TESTED

To fully validate, you need to:
1. Log into UC in a browser on your device
2. Get the cookies from WebView CookieManager (via adb logcat)
3. Run: python3 test_uc_cookie_bridge.py --cookies "__pus=YOUR_REAL_COOKIE"
4. If the API returns file data, the bridge approach works
""")


# ============================================================
# Interactive mode: capture cookies from device via adb
# ============================================================

def interactive_mode():
    """Use adb to capture cookies from the device's WebView."""
    print("\n" + "=" * 60)
    print("INTERACTIVE: Capture cookies from device")
    print("=" * 60)

    import subprocess

    # Check if adb is available
    try:
        result = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
        print(f"ADB devices:\n{result.stdout}")
    except (FileNotFoundError, subprocess.TimeoutExpired):
        print("ERROR: adb not found or timed out. Please install Android SDK platform-tools.")
        return

    # Try to get UC cookies from the device
    print("\nAttempting to get cookies from device...")
    print("This requires the app to be running and a WebView login to have been performed.")

    # Method 1: Read from logcat (if PersistentCookieJar logging is active)
    print("\nMethod 1: Reading from logcat (PersistentCookieJar logs)...")
    print("  Run this on your device first, then come back:")
    print("  1. Open the app")
    print("  2. Navigate to a UC source")
    print("  3. Log in via WebView")
    print("  4. Run: adb logcat -s PersistentCookieJar BaseDrive")
    print("  5. Copy the cookie values and pass them to this script with --cookies")

    # Method 2: Use adb to run content query on SharedPreferences
    print("\nMethod 2: Reading SharedPreferences directly...")
    try:
        # Try to read the okhttp_cookies SharedPreferences file
        cmd = [
            "adb", "shell",
            "cat /data/data/com.fongmi.android.tv/shared_prefs/okhttp_cookies.xml"
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.returncode == 0 and result.stdout:
            print(f"  Found cookies in SharedPreferences:")
            # Parse the XML
            import xml.etree.ElementMap as ET
            root = ET.fromstring(result.stdout)
            for elem in root:
                name = elem.get("name", "")
                value = elem.get("value", "")
                if "__pus" in name or "__puus" in name or "BDUSS" in name:
                    print(f"  LOGIN COOKIE: {name} = {value[:30]}...")
                else:
                    print(f"  {name} = {value[:30]}...")
        else:
            print(f"  Could not read SharedPreferences (need root or debug app): {result.stderr[:100]}")
    except Exception as e:
        print(f"  Error: {e}")


# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="Test UC/Quark cookie bridge for TVBoxOSC")
    parser.add_argument("--cookies", type=str, help="Cookie string to test (e.g., '__pus=xxx; __sdid=yyy')")
    parser.add_argument("--domain", type=str, default="uc.cn", help="Domain for cookies (default: uc.cn)")
    parser.add_argument("--provider", type=str, default="uc", choices=["uc", "quark", "baidu"],
                        help="Cloud drive provider to test (default: uc)")
    parser.add_argument("--share-url", type=str, help="Share URL to test (e.g., 'https://drive.uc.cn/s/abc123')")
    parser.add_argument("--interactive", action="store_true", help="Interactive mode: capture cookies from device via adb")
    parser.add_argument("--unit-tests", action="store_true", help="Run unit tests only (no network)")
    parser.add_argument("--full", action="store_true", help="Run full test suite")

    args = parser.parse_args()

    if args.interactive:
        interactive_mode()
        return

    if args.unit_tests or args.full or (not args.cookies and not args.share_url):
        # Run unit tests
        print("=" * 60)
        print("UC/Quark Cookie Bridge Test Suite")
        print(f"Time: {datetime.now().isoformat()}")
        print("=" * 60)

        test_episode_url_detection()
        test_cookie_domain_matching()
        test_webview_cookie_sync()

    if args.full or (not args.cookies and not args.share_url and not args.unit_tests):
        # Test APIs without login
        test_api_without_login()

    if args.cookies:
        # Test with real cookies
        test_api_with_cookies(args.cookies, args.provider)

    if args.share_url:
        # Parse share URL and test
        print(f"\nTesting share URL: {args.share_url}")
        # Extract share ID from URL
        # UC: https://drive.uc.cn/s/{surl}
        # Quark: https://pan.quark.cn/s/{surl}
        # Baidu: https://pan.baidu.com/s/{surl}
        match = re.search(r'/s/([A-Za-z0-9_-]+)', args.share_url)
        if match:
            surl = match.group(1)
            print(f"  Share ID: {surl}")
            if "uc.cn" in args.share_url:
                print("  Provider: UC (need stoken from spider)")
            elif "quark.cn" in args.share_url:
                print("  Provider: Quark (need stoken from spider)")
            elif "baidu.com" in args.share_url:
                test_baidu_api(surl, args.cookies)
        else:
            print(f"  Could not parse share URL: {args.share_url}")

    if args.full:
        test_full_flow(args.cookies, args.provider)


if __name__ == "__main__":
    main()
