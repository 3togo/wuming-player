#!/usr/bin/env python3
"""
Quark Cloud Drive API Test Script
Tests the actual API endpoints and responses to understand the video playback issue.

Usage:
    python3 quark_api_test.py <fid> [cookie]

Example:
    python3 quark_api_test.py 64a8f1c9876543210abcdef123456789
    python3 quark_api_test.py 64a8f1c9876543210abcdef123456789 "cookie_name=cookie_value; ..."
"""

import json
import sys
import time
import urllib.request
import urllib.error
import urllib.parse

API_BASE = "https://drive-pc.quark.cn/1/clouddrive/"
QUERY_PARAMS = "?pr=ucpro&fr=pc"

# Match tv-quark-app implementation
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# Alternative endpoints to test
ENDPOINTS = {
    "get_download_url": "file/get_download_url",
    "v2_play": "file/v2/play",
    "download": "file/download",
    "get_download_url_no_pr": "file/get_download_url",  # Without pr/fr params
}

def make_request(url, data=None, headers=None):
    """Make HTTP request and return response"""
    if headers is None:
        headers = {}
    headers.setdefault("User-Agent", UA)
    headers.setdefault("Accept", "application/json")
    # Critical: Quark API requires these headers
    headers.setdefault("Origin", "https://pan.quark.cn")
    headers.setdefault("Referer", "https://pan.quark.cn/")

    try:
        if data:
            if isinstance(data, dict):
                data = json.dumps(data).encode("utf-8")
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request(url, data=data, headers=headers)
        else:
            req = urllib.request.Request(url, headers=headers)

        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read()
            try:
                return json.loads(body), resp.getheaders()
            except:
                return body, resp.getheaders()
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace") if e.fp else ""
        try:
            return json.loads(body), {}
        except:
            return {"error": str(e), "code": e.code, "body": body[:500]}, {}
    except Exception as e:
        return {"error": str(e)}, {}

def test_get_download_url(fid, cookie=""):
    """Test /file/get_download_url endpoint - returns original quality URL"""
    print(f"\n{'='*60}")
    print(f"Testing /file/get_download_url for fid: {fid[:20]}...")
    print(f"{'='*60}")

    url = API_BASE + "file/get_download_url" + QUERY_PARAMS
    print(f"URL: {url}")
    payload = {
        "fid_list": [fid],
        "res_type": 4,
        "share_fid_token": "",
        "forbid_type": 0
    }

    headers = {}
    if cookie:
        headers["Cookie"] = cookie

    response, resp_headers = make_request(url, data=payload, headers=headers)

    if isinstance(response, dict):
        status = response.get('status', 'N/A')
        code = response.get('code', 'N/A')
        error = response.get('error', 'N/A')
        message = response.get('message', 'N/A')
        path = response.get('path', 'N/A')

        print(f"Response status: {status}")
        print(f"Response code: {code}")
        print(f"Response error: {error}")
        print(f"Response message: {message}")
        print(f"Response path: {path}")

        if "data" in response:
            data = response["data"]
            if isinstance(data, list):
                print(f"Number of items in data: {len(data)}")
                for i, item in enumerate(data):
                    if isinstance(item, dict):
                        print(f"\n--- Item {i} ---")
                        print(f"  Keys: {list(item.keys())}")
                        url_item = item.get("download_url", "")
                        print(f"  URL: {url_item[:100]}..." if len(url_item) > 100 else f"  URL: {url_item}")
                        print(f"  URL type: {'valid' if url_item.startswith('http') else 'invalid'}")
                        if "ext" in item:
                            print(f"  Extension: {item.get('ext')}")
                        if "size" in item:
                            print(f"  Size: {item.get('size')}")
                        if "md5" in item:
                            print(f"  MD5: {item.get('md5')}")
            elif isinstance(data, dict):
                print(f"Data is dict with keys: {list(data.keys())}")
        elif "error" in response:
            print(f"Error: {response['error']}")
            print(f"Body: {response.get('body', 'N/A')[:500]}")
    else:
        print(f"Non-JSON response: {str(response)[:200]}")

    return response

def test_play_endpoint(fid, cookie=""):
    """Test /file/v2/play endpoint for transcoded video"""
    print(f"\n{'='*60}")
    print(f"Testing /file/v2/play for fid: {fid[:20]}...")
    print(f"{'='*60}")

    url = API_BASE + "file/v2/play" + QUERY_PARAMS
    payload = {
        "fid": fid,
        "resolutions": "normal,low,high,super,2k,4k",
        "supports": "fmp4"
    }

    headers = {}
    if cookie:
        headers["Cookie"] = cookie

    response, resp_headers = make_request(url, data=payload, headers=headers)

    if isinstance(response, dict):
        print(f"Response code: {response.get('code', 'N/A')}")
        print(f"Response keys: {list(response.keys())}")

        if "data" in response:
            data = response["data"]
            if isinstance(data, dict):
                print(f"Data keys: {list(data.keys())}")

                if "video_list" in data:
                    video_list = data["video_list"]
                    print(f"Number of video renditions: {len(video_list)}")

                    for i, video in enumerate(video_list):
                        if isinstance(video, dict):
                            print(f"\n--- Video rendition {i} ---")
                            print(f"  Keys: {list(video.keys())}")
                            res = video.get("resolution", "")
                            print(f"  Resolution: {res}")

                            if "video_info" in video:
                                vi = video["video_info"]
                                if isinstance(vi, dict):
                                    video_url = vi.get("url", "")
                                    print(f"  Video URL: {video_url[:100]}..." if len(video_url) > 100 else f"  Video URL: {video_url}")
                                    print(f"  URL valid: {video_url.startswith('http')}")

                                    if "m3u8" in video_url.lower():
                                        print(f"  ** WARNING: This is an HLS/m3u8 URL - may not work with DLNA!")
                                    elif ".mp4" in video_url.lower():
                                        print(f"  This is an MP4 URL")
                                    elif ".mkv" in video_url.lower():
                                        print(f"  This is an MKV URL")
                                    elif "fmp4" in video_url.lower():
                                        print(f"  This is an fMP4 URL")
                                elif isinstance(vi, str):
                                    print(f"  Video URL (string): {vi[:100]}...")
            elif isinstance(data, list):
                print(f"Data is list with {len(data)} items")
        elif "error" in response:
            print(f"Error: {response['error']}")
    else:
        print(f"Non-JSON response: {str(response)[:200]}")

    return response

def test_download_endpoint(fid, cookie=""):
    """Test /file/download endpoint"""
    print(f"\n{'='*60}")
    print(f"Testing /file/download for fid: {fid[:20]}...")
    print(f"{'='*60}")

    url = API_BASE + "file/download" + QUERY_PARAMS + "&entry=ft"
    payload = {"fids": [fid]}

    headers = {}
    if cookie:
        headers["Cookie"] = cookie

    response, resp_headers = make_request(url, data=payload, headers=headers)

    if isinstance(response, dict):
        print(f"Response code: {response.get('code', 'N/A')}")

        if "data" in response:
            data = response["data"]
            if isinstance(data, list):
                print(f"Number of items: {len(data)}")
                for item in data:
                    if isinstance(item, dict):
                        dl_url = item.get("download_url", "")
                        print(f"  URL: {dl_url[:100]}..." if len(dl_url) > 100 else f"  URL: {dl_url}")
                        print(f"  Valid: {dl_url.startswith('http')}")
            elif isinstance(data, dict):
                dl_url = data.get("download_url", "")
                print(f"  URL: {dl_url[:100]}..." if len(dl_url) > 100 else f"  URL: {dl_url}")
                print(f"  Valid: {dl_url.startswith('http')}")
        elif "error" in response:
            print(f"Error: {response['error']}")
    else:
        print(f"Non-JSON or unexpected response")

    return response

def check_url_accessibility(url, cookie=""):
    """Check if a URL is accessible and what headers it returns"""
    print(f"\n{'='*60}")
    print(f"Checking URL accessibility: {url[:80]}...")
    print(f"{'='*60}")

    headers = {}
    if cookie:
        headers["Cookie"] = cookie

    req = urllib.request.Request(url, method="HEAD", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            print(f"Status: {resp.status}")
            print(f"Headers:")
            for key, value in resp.getheaders():
                if any(h in key.lower() for h in ["content", "accept", "range", "transfer", "dlna"]):
                    print(f"  {key}: {value}")
            return True
    except urllib.error.HTTPError as e:
        print(f"HTTP Error: {e.code} {e.reason}")
        print(f"Headers:")
        for key, value in e.headers.items():
            if any(h in key.lower() for h in ["content", "accept", "range", "transfer", "dlna"]):
                print(f"  {key}: {value}")
        return False
    except Exception as e:
        print(f"Error: {e}")
        return False

def main():
    if len(sys.argv) < 2:
        print("Usage: python quark_api_test.py <fid> [cookie]")
        print("  fid: The Quark file ID to test")
        print("  cookie: Optional cookie string for authenticated requests")
        print("\nExample:")
        print("  python quark_api_test.py 64a8f1c9876543210abcdef123456789")
        print("  python quark_api_test.py 64a8f1c9876543210abcdef123456789 'cookie_name=cookie_value; ...'")
        sys.exit(1)

    fid = sys.argv[1]
    cookie = sys.argv[2] if len(sys.argv) > 2 else ""

    print(f"Quark Cloud Drive API Test")
    print(f"FID: {fid}")
    print(f"Has Cookie: {'Yes' if cookie else 'No (may fail)'}")

    dl_response = test_get_download_url(fid, cookie)
    play_response = test_play_endpoint(fid, cookie)
    download_response = test_download_endpoint(fid, cookie)

    urls_to_check = []

    if isinstance(dl_response, dict) and "data" in dl_response:
        data = dl_response["data"]
        if isinstance(data, list):
            for item in data:
                if isinstance(item, dict) and "download_url" in item:
                    urls_to_check.append(item["download_url"])

    if isinstance(play_response, dict) and "data" in play_response:
        data = play_response["data"]
        if isinstance(data, dict) and "video_list" in data:
            for video in data["video_list"]:
                if isinstance(video, dict) and "video_info" in video:
                    vi = video["video_info"]
                    if isinstance(vi, dict) and "url" in vi:
                        urls_to_check.append(vi["url"])

    if urls_to_check:
        print(f"\n{'='*60}")
        print(f"Found {len(urls_to_check)} URLs to check")
        print(f"{'='*60}")

        seen = set()
        for url in urls_to_check:
            if url not in seen:
                seen.add(url)
                check_url_accessibility(url, cookie)
                time.sleep(0.5)
    else:
        print("\nNo URLs found in responses (likely need valid cookie)")

if __name__ == "__main__":
    main()