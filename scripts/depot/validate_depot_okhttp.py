#!/usr/bin/env python3
"""
Script to validate depot.json sources using OkHttp-like behavior
Mimics the actual app's URL loading logic
"""

import json
import os
import requests
import time
import base64
from urllib.parse import urlparse

# Depot file
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEPOT_FILE = os.path.join(_REPO_ROOT, "app/src/main/assets/depot.json")

# Timeout (matching app's 15 second timeout)
VALIDATION_TIMEOUT = 15

# GitHub proxies used by the app
GITHUB_PROXIES = [
    "https://gh-proxy.com/",
    "https://gh.idayer.com/",
    "https://mirror.ghproxy.com/",
    "https://proxy.zyun.vip/",
]

def rewrite_github_url(url):
    """Rewrite GitHub URLs with proxy (like the app does)"""
    if "raw.githubusercontent.com" in url or "github.com" in url and "/blob/" in url:
        # Use first proxy
        proxy = GITHUB_PROXIES[0]
        return proxy + url
    return url

def is_valid_json_or_encrypted(content):
    """Check if content is valid JSON or encrypted JSON (like app's Decoder.verify())"""
    if not content or not content.strip():
        return False, "Empty content"
    
    content = content.strip()
    
    # Check if HTML
    if content.startswith('<!DOCTYPE') or content.startswith('<html'):
        return False, "Returns HTML, not JSON"
    
    # Try to parse as JSON
    try:
        json.loads(content)
        return True, "Valid JSON"
    except json.JSONDecodeError:
        pass
    
    # Check if base64 encoded
    try:
        decoded = base64.b64decode(content)
        json.loads(decoded)
        return True, "Valid base64-encoded JSON"
    except:
        pass
    
    # Check if encrypted (long content that's not JSON)
    if len(content) > 100:
        return True, "Valid (encrypted/obfuscated content)"
    
    # Check if redirect URL
    if content.startswith('http://') or content.startswith('https://'):
        return True, "Valid (redirect URL)"
    
    return False, "Not JSON or encrypted JSON"

def fetch_url_like_app(url):
    """Fetch URL like the app does with OkHttp"""
    try:
        # Step 1: Rewrite GitHub URLs with proxy
        final_url = rewrite_github_url(url)
        
        # Step 2: Fetch with redirect following (like OkHttp.followRedirects(true))
        response = requests.get(
            final_url,
            timeout=VALIDATION_TIMEOUT,
            allow_redirects=True,
            headers={
                'User-Agent': 'Dalvik/2.1.0 (Linux; U; Android 10; SM-G981B Build/QP1A.190711.020)',
                'Accept': 'application/json, text/plain, */*',
            }
        )
        
        # Step 3: Check HTTP status
        if response.status_code != 200:
            # Try relay proxy like the app does
            relay_url = f"https://ua.fongmi.eu.org/box.php?url={url}"
            try:
                relay_response = requests.get(relay_url, timeout=VALIDATION_TIMEOUT)
                if relay_response.status_code == 200:
                    content = relay_response.text.strip()
                    valid, msg = is_valid_json_or_encrypted(content)
                    if valid:
                        return True, f"{msg} (via relay)"
            except:
                pass
            
            return False, f"HTTP {response.status_code}"
        
        # Step 4: Validate content
        content = response.text.strip()
        valid, msg = is_valid_json_or_encrypted(content)
        return valid, msg
        
    except requests.exceptions.Timeout:
        # Try relay on timeout
        relay_url = f"https://ua.fongmi.eu.org/box.php?url={url}"
        try:
            relay_response = requests.get(relay_url, timeout=VALIDATION_TIMEOUT)
            if relay_response.status_code == 200:
                content = relay_response.text.strip()
                valid, msg = is_valid_json_or_encrypted(content)
                if valid:
                    return True, f"{msg} (via relay)"
        except:
            pass
        return False, "Timeout"
    except requests.exceptions.ConnectionError:
        return False, "Connection error"
    except Exception as e:
        return False, f"Error: {e}"

def validate_depot():
    """Validate all sources in depot.json"""
    print("=" * 60)
    print("Depot.json Validator (OkHttp-like)")
    print("=" * 60)
    
    # Load depot.json
    try:
        with open(DEPOT_FILE, 'r', encoding='utf-8') as f:
            depot = json.load(f)
    except Exception as e:
        print(f"Error loading depot.json: {e}")
        return
    
    # Validate VOD sources
    print("\n" + "=" * 60)
    print("Validating VOD Sources")
    print("=" * 60)
    
    valid_vod = []
    invalid_vod = []
    
    for i, source in enumerate(depot.get('vod', []), 1):
        print(f"\n[{i}/{len(depot.get('vod', []))}] {source['name']}")
        print(f"  URL: {source['url']}")
        valid, msg = fetch_url_like_app(source['url'])
        if valid:
            print(f"  ✓ {msg}")
            valid_vod.append(source)
        else:
            print(f"  ✗ {msg}")
            invalid_vod.append(source)
        time.sleep(0.5)
    
    # Validate Live sources
    print("\n" + "=" * 60)
    print("Validating Live Sources")
    print("=" * 60)
    
    valid_live = []
    invalid_live = []
    
    for i, source in enumerate(depot.get('live', []), 1):
        print(f"\n[{i}/{len(depot.get('live', []))}] {source['name']}")
        print(f"  URL: {source['url']}")
        valid, msg = fetch_url_like_app(source['url'])
        if valid:
            print(f"  ✓ {msg}")
            valid_live.append(source)
        else:
            print(f"  ✗ {msg}")
            invalid_live.append(source)
        time.sleep(0.5)
    
    # Update depot.json
    depot['vod'] = valid_vod
    depot['live'] = valid_live
    
    # Save
    with open(DEPOT_FILE, 'w', encoding='utf-8') as f:
        json.dump(depot, f, indent=2, ensure_ascii=False)
    
    # Summary
    print("\n" + "=" * 60)
    print("Summary")
    print("=" * 60)
    print(f"VOD sources: {len(valid_vod)} valid, {len(invalid_vod)} invalid")
    print(f"Live sources: {len(valid_live)} valid, {len(invalid_live)} invalid")
    
    if invalid_vod:
        print("\nInvalid VOD sources removed:")
        for source in invalid_vod:
            print(f"  - {source['name']}: {source['url']}")
    
    if invalid_live:
        print("\nInvalid Live sources removed:")
        for source in invalid_live:
            print(f"  - {source['name']}: {source['url']}")
    
    print(f"\nSaved to {DEPOT_FILE}")
    print("=" * 60)

if __name__ == "__main__":
    validate_depot()
