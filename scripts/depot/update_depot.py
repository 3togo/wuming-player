#!/usr/bin/env python3
"""
Harvest playable source endpoints into app/src/main/assets/depot.json.

Two intake shapes feed the depot: README scraping (markdown prose) and structured
catalogs (a curator's own JSON, see FEEDS). New candidates from either shape must pass
the content-shape gate before they are admitted; entries already in the depot are expired
by the same consecutive-failure policy the app uses (DepotPrune.PRUNE_AFTER_FAILS), never
by one failed fetch.
"""

import argparse
import json
import os
import pathlib
import re
import requests
from urllib.parse import urlparse, urljoin
import time

import validate_depot

# GitHub README URLs
README_URLS = [
    "https://raw.githubusercontent.com/youhunwl/TVAPP/main/README.md",
    "https://raw.githubusercontent.com/ngo5/IPTV/main/README.md",
    "https://raw.githubusercontent.com/Zhou-Li-Bin/Tvbox-QingNing/main/README.md"
]

# Structured catalogs. `categories` maps the curator's own category field onto depot
# sections; any category absent from the map is discarded, because a website or an APK is
# not a player configuration no matter how well the curator labelled it.
FEEDS = [
    {
        "id": "zhuiju",
        "label": "追剧指南",
        "catalog": "https://raw.githubusercontent.com/laoma2053/awesome-zhuiju-free/main/resources/resources.json",
        # Upstream's own daily link check. Used only to order candidates, never to admit
        # one: it runs on GitHub Actions runners and disagrees with a local probe.
        "availability": "https://raw.githubusercontent.com/laoma2053/awesome-zhuiju-free/main/reports/availability.json",
        "categories": {"tvbox_config": "vod"},
    },
]

# How many entries one feed may own in the shipped asset. depot.json is inside the APK and
# every entry costs first-boot fetch time, so intake needs a ceiling.
FEED_BUDGET = 12

# Mirrors DepotPrune.PRUNE_AFTER_FAILS: a 403/DNS blip must not cost a working source.
PRUNE_AFTER_FAILS = 3

# Keys the app's config parser acts on. A JSON body without any of them is not a playable
# configuration even if it fetches with HTTP 200.
CONFIG_KEYS = ('sites', 'lives', 'vills', 'spider', 'parses', 'jar', 'ads', 'rules',
               'dns', 'hosts', 'ijkp', 'ijkv', 'wallPaper')

# Hosts that can never be probed reliably (IDN endpoints, pages that block datacenter IPs).
# Mirrors LEGACY_NO_CHECK in DepotManager; recorded on the entry as noCheck instead of
# being counted as a successful validation.
NO_CHECK_HOSTS = ('饭太硬', 'fty.888484', '我不是.摸鱼儿')

# Output file
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEPOT_FILE = os.path.join(_REPO_ROOT, "app/src/main/assets/depot.json")

# Cache directory for README content
CACHE_DIR = os.path.join(_REPO_ROOT, ".cache/readme")

# Heading keywords. A heading that matches none of them resets the section to "guess from URL shape";
# it must NOT inherit the previous section, and it must NOT silently drop the entries below it.
VOD_HEADINGS = ('点播', 'VOD', '影视')
LIVE_HEADINGS = ('直播', 'Live', 'TV', '电视')
SKIP_HEADINGS = ('EPG', '推荐', '软件', '壁纸', '加速', '缩短', '声明', '免责', '赞助',
                 '写在前面', '资源获取', '更新记录', '项目需知')

# Badges, screenshots and other assets shown in the READMEs. Never a config endpoint.
ASSET_EXTENSIONS = ('.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.ico', '.bmp',
                    '.css', '.js', '.html', '.woff', '.woff2', '.ttf', '.eot', '.mp4', '.apk')

# Validation cache: skip re-testing URLs validated as valid within this period
VALIDATION_CACHE_DIR = os.path.join(_REPO_ROOT, ".cache/validation")
VALIDATION_CACHE_TTL = 24 * 60 * 60  # 24 hours

# Consecutive-failure counters, keyed by URL. Deliberately outside depot.json, which ships
# inside the APK and must not carry a device-facing countdown.
STATE_FILE = os.path.join(_REPO_ROOT, ".cache/depot_state.json")

# Bundled sources an entry can point at ("assets://live/….m3u8"); resolved locally, never fetched.
ASSET_ROOT = pathlib.Path(_REPO_ROOT) / "app/src/main/assets"

# Timeout for URL validation (seconds)
VALIDATION_TIMEOUT = 10

def fetch_cached(url, refresh=False, kind='README', suffix='.md'):
    """Fetch a remote document, using the cached copy by default to avoid IP blocking.

    By default, always uses the cached version if it exists (regardless of age).
    Use refresh=True to force re-fetching from the network.
    """
    # Create cache directory if needed
    os.makedirs(CACHE_DIR, exist_ok=True)
    
    # Generate a cache filename from the URL
    cache_key = re.sub(r'[^a-zA-Z0-9]', '_', url)
    cache_file = os.path.join(CACHE_DIR, f"{cache_key}{suffix}")
    
    # Use cached version if it exists and refresh is not requested
    if os.path.exists(cache_file) and not refresh:
        cache_age = time.time() - os.path.getmtime(cache_file)
        print(f"Using cached {kind} (age: {cache_age / 3600:.1f}h): {url}")
        try:
            with open(cache_file, 'r', encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            print(f"Error reading cache: {e}")
    
    # Fetch from network (only when no cache exists or refresh=True)
    if refresh:
        print(f"Refreshing {kind} from {url}...")
    else:
        print(f"Fetching {kind} from {url}...")
    
    try:
        response = requests.get(url, timeout=30)
        response.raise_for_status()
        content = response.text
        
        # Save to cache
        try:
            with open(cache_file, 'w', encoding='utf-8') as f:
                f.write(content)
            print(f"  Cached to {cache_file}")
        except Exception as e:
            print(f"  Warning: could not cache: {e}")
        
        return content
    except Exception as e:
        print(f"Error fetching {kind}: {e}")
        # If network fails but cache exists, use it as fallback
        if os.path.exists(cache_file):
            try:
                with open(cache_file, 'r', encoding='utf-8') as f:
                    return f.read()
            except Exception:
                pass
        return None


def fetch_readme(url, refresh=False):
    """Fetch README content from GitHub (cached; see fetch_cached)."""
    return fetch_cached(url, refresh, kind='README')

def is_source_url(url):
    """Check if a URL is a valid source (VOD or live)"""
    lower = url.lower()
    # Reject mirror/accelerator links (ghproxy.net/https://..., ghp.ci/https://..., gitdl.cn/https://...).
    # They embed the canonical URL, so they would (a) duplicate an entry we already store and
    # (b) route every user request through an unrelated third party we do not control.
    scheme = url.find('://')
    if scheme >= 0 and ('http://' in url[scheme + 3:] or 'https://' in url[scheme + 3:]):
        return False
    if lower.endswith(ASSET_EXTENSIONS):
        return False
    # VOD sources: .json, .txt, or a file-ish name containing 'tvbox'
    # Live sources: .m3u, .m3u8
    if lower.endswith(('.json', '.txt', '.m3u', '.m3u8')):
        return True
    # A bare "tvbox" substring anywhere is too weak: every badge and self-link of a repo whose name
    # contains "Tvbox" (img.shields.io/.../Tvbox-QingNing, github.com/.../Tvbox-QingNing/pulls) matches.
    segment = urlparse(url).path.rstrip('/').rsplit('/', 1)[-1].lower()
    return 'tvbox' in segment and '.' in segment

def is_live_url(url):
    """Check if a URL is a live source based on extension"""
    lower = url.lower()
    return lower.endswith('.m3u') or lower.endswith('.m3u8')

def extract_name_from_url(url):
    """Extract a name from the URL's last path segment without extension"""
    path = urlparse(url).path
    basename = os.path.basename(path)
    # Remove extension
    name = os.path.splitext(basename)[0]
    return name if name else urlparse(url).netloc

def extract_name_from_hash_comment(line, url):
    """Extract name from # comment after the URL on the line"""
    # Find the position of the URL in the line
    url_pos = line.find(url)
    if url_pos < 0:
        return None
    after_url = line[url_pos + len(url):]
    # Look for # comment
    match = re.match(r'\s*#\s*(.+)', after_url)
    if match:
        return match.group(1).strip()
    return None

def extract_name_from_table_row(line):
    """Extract the APP名称 (first column name) from a markdown table row.
    
    Table format: |[name](link)|url|...| or |name|url|...|
    The first column contains the source name.
    """
    # Split by | and get the first data column (index 1 after split)
    parts = line.split('|')
    if len(parts) < 3:
        return None
    first_col = parts[1].strip()
    if not first_col:
        return None
    
    # If it's a markdown link [name](url), extract the name
    match = re.match(r'\[([^\]]+)\]', first_col)
    if match:
        return match.group(1).strip()
    
    # Otherwise, use the plain text (strip ~~ markers)
    name = first_col.replace('~~', '').strip()
    return name if name else None

def extract_sources(readme_content):
    """Extract TV and VOD sources from README"""
    vod_sources = []
    live_sources = []
    
    lines = readme_content.split('\n')
    current_section = None
    last_table_name = None  # Track the APP名称 from table rows
    
    for line in lines:
        # Skip cross-outed lines (marked with ~~)
        if line.strip().startswith('|~~') or '~~|~~' in line:
            continue
        
        # Detect section headers (only markdown headers, not inline text)
        stripped = line.strip()
        if stripped.startswith('#'):
            if any(k in stripped for k in VOD_HEADINGS):
                current_section = 'vod'
            elif any(k in stripped for k in LIVE_HEADINGS):
                current_section = 'live'
            elif any(k in stripped for k in SKIP_HEADINGS):
                current_section = 'skip'
            else:
                current_section = None  # unknown heading: classify by URL shape below
        
        # Check if this is a table row with a name in the first column
        is_table_row = line.strip().startswith('|') and '|' in line.strip()[1:]
        if is_table_row:
            table_name = extract_name_from_table_row(line)
            if table_name:
                last_table_name = table_name
        
        # Collect all source URLs found on this line (from both markdown links and plain URLs)
        link_pattern = r'\[([^\]]+)\]\(([^)]+)\)'
        url_pattern = r'(https?://[^\s\)\]\>\#\|]+)'
        
        # Track URLs found via markdown links to avoid duplicates
        link_urls = set()
        links = re.findall(link_pattern, line)
        for name, url in links:
            # Skip cross-outed links
            if '~~' in line and line.find('~~') < line.find(url):
                continue
            
            if is_source_url(url):
                link_urls.add(url)
                # Prefer: # comment after URL > table APP名称 > markdown link name
                best_name = (extract_name_from_hash_comment(line, url) or
                             last_table_name or name)
                source = {'url': url, 'name': best_name}
                # Determine section: use current_section, but also check URL type
                if current_section == 'skip':
                    continue
                if current_section == 'live' or is_live_url(url):
                    live_sources.append(source)
                else:
                    vod_sources.append(source)
        
        # Also look for plain URLs (e.g. in table cells without markdown links)
        urls = re.findall(url_pattern, line)
        for url in urls:
            # Skip cross-outed URLs
            if '~~' in line and line.find('~~') < line.find(url):
                continue
            
            # Skip URLs already found via markdown links
            if url in link_urls:
                continue
            
            if is_source_url(url):
                # Prefer: # comment after URL > table APP名称 > filename without extension > domain
                best_name = (extract_name_from_hash_comment(line, url) or
                             last_table_name or
                             extract_name_from_url(url))
                source = {'url': url, 'name': best_name}
                if current_section == 'skip':
                    continue
                if current_section == 'live' or is_live_url(url):
                    live_sources.append(source)
                else:
                    vod_sources.append(source)
    
    print(f"Extracted {len(vod_sources)} VOD sources and {len(live_sources)} Live sources")
    return vod_sources, live_sources

def is_validation_cached(url):
    """Check if a URL was validated as valid within the last 24 hours"""
    os.makedirs(VALIDATION_CACHE_DIR, exist_ok=True)
    cache_key = re.sub(r'[^a-zA-Z0-9]', '_', url)
    cache_file = os.path.join(VALIDATION_CACHE_DIR, f"{cache_key}.valid")
    if os.path.exists(cache_file):
        cache_age = time.time() - os.path.getmtime(cache_file)
        if cache_age < VALIDATION_CACHE_TTL:
            return True
        else:
            # Expired, remove stale cache
            try:
                os.remove(cache_file)
            except OSError:
                pass
    return False

def save_validation_cache(url):
    """Mark a URL as validated valid"""
    os.makedirs(VALIDATION_CACHE_DIR, exist_ok=True)
    cache_key = re.sub(r'[^a-zA-Z0-9]', '_', url)
    cache_file = os.path.join(VALIDATION_CACHE_DIR, f"{cache_key}.valid")
    try:
        with open(cache_file, 'w') as f:
            f.write(url)
    except OSError:
        pass

def validate_url(url):
    """Test if URL is valid and returns usable content"""
    # Check validation cache first
    if is_validation_cached(url):
        print(f"  ✓ Cached valid (skipped re-test): {url}")
        return True

    if url.startswith('assets://'):
        # A source bundled in the APK. It is checked against the working tree, never fetched,
        # so a packaged entry cannot age out because a probe has no transport for it.
        result = validate_depot.fetch_asset(url, ASSET_ROOT)
        ok, reason = validate_depot.useful_content(result.content) if result.valid else (False, result.reason)
        print(f"  {'✓ Bundled asset verified' if ok else '✗ Asset'}: {url} ({reason})")
        if ok:
            save_validation_cache(url)
        return ok

    try:
        print(f"  Validating: {url}")
        response = requests.get(url, timeout=VALIDATION_TIMEOUT, allow_redirects=True)
        
        # Check HTTP status
        if response.status_code != 200:
            print(f"    ✗ Invalid (HTTP {response.status_code})")
            return False
        
        content = response.text.strip()
        
        # Check if it's HTML (invalid)
        if content.startswith('<!DOCTYPE') or content.startswith('<html'):
            print(f"    ✗ Returns HTML, not valid content")
            return False
        
        # Additional check: content should not be empty
        if not content:
            print(f"    ✗ Empty content")
            return False
        
        # For .m3u/.m3u8 URLs, check for M3U format
        if is_live_url(url):
            if '#EXTM3U' in content or '#EXTINF' in content or content.startswith('http'):
                print(f"    ✓ Valid M3U/live source")
                save_validation_cache(url)
                return True
            # Some live sources return plain text lists
            if len(content) > 50:
                print(f"    ✓ Valid (text content)")
                save_validation_cache(url)
                return True
            print(f"    ✗ Not valid M3U content")
            return False
        
        # For VOD sources (.json, .txt), check for JSON or encrypted content
        try:
            json.loads(content)
            print(f"    ✓ Valid JSON")
            save_validation_cache(url)
            return True
        except json.JSONDecodeError:
            # Check if it might be encrypted JSON (base64 or other encoding)
            if content.startswith('http') or len(content) > 100:
                print(f"    ✓ Valid (encrypted/redirect content)")
                save_validation_cache(url)
                return True
            else:
                print(f"    ✗ Not JSON or encrypted JSON")
                return False
        
    except requests.exceptions.Timeout:
        print(f"    ✗ Timeout")
        return False
    except requests.exceptions.ConnectionError:
        print(f"    ✗ Connection error")
        return False
    except Exception as e:
        print(f"    ✗ Error: {e}")
        return False

def validate_sources(sources):
    """Validate all sources and return only valid ones"""
    valid_sources = []
    total = len(sources)
    
    print(f"\nValidating {total} sources...")
    
    for i, source in enumerate(sources, 1):
        print(f"\n[{i}/{total}] {source['name']}")
        if is_no_check(source['url']):
            # Recorded as data, not counted as a passing probe: the app applies the same
            # noCheck rule in DepotManager.resolveNoCheck.
            source['noCheck'] = True
            valid_sources.append(source)
            print("    ~ noCheck host, kept without probing")
            continue
        if validate_url(source['url']):
            valid_sources.append(source)
        time.sleep(0.5)  # Be nice to servers
    
    return valid_sources

# ---------------------------------------------------------------------------
# Content-shape gate: what the app can actually act on, not just what answers 200
# ---------------------------------------------------------------------------

def is_no_check(url):
    """True for hosts no automated probe can answer for (IDN endpoints, datacenter blocks)."""
    try:
        host = urlparse(url).hostname or ''
        host.encode('ascii')
    except UnicodeEncodeError:
        return True
    return any(marker in url for marker in NO_CHECK_HOSTS)


def is_endpoint_url(url):
    """A directly fetchable http(s) endpoint, not a proxy-embedded or site link."""
    if not isinstance(url, str):
        return False
    url = url.strip()
    scheme = url.find('://')
    if scheme < 0 or url[:scheme].lower() not in ('http', 'https'):
        return False
    rest = url[scheme + 3:]
    return 'http://' not in rest and 'https://' not in rest


def next_fail_count(previous, ok):
    """Consecutive-failure counter. Mirrors DepotPrune.nextFailCount."""
    return 0 if ok else previous + 1


def keep_entry(fail_count):
    """Mirrors DepotPrune.keep: an entry is only dropped after PRUNE_AFTER_FAILS in a row."""
    return fail_count < PRUNE_AFTER_FAILS


def strip_json_comments(text):
    """Blank out //, # and /* */ comments that sit outside string literals.

    Plenty of curated configs are JSON-with-comments because TVBox reads them with a lenient
    Gson parser. The scan is string-aware so an "http://" inside a value survives.
    """
    out = []
    index = 0
    length = len(text)
    in_string = False
    while index < length:
        char = text[index]
        if in_string:
            out.append(char)
            if char == '\\' and index + 1 < length:
                out.append(text[index + 1])
                index += 2
                continue
            if char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if char == '/' and index + 1 < length:
            following = text[index + 1]
            if following == '/':
                while index < length and text[index] != '\n':
                    index += 1
                continue
            if following == '*':
                end = text.find('*/', index + 2)
                index = length if end < 0 else end + 2
                out.append(' ')
                continue
        if char == '#':
            while index < length and text[index] != '\n':
                index += 1
            continue
        out.append(char)
        index += 1
    return ''.join(out)


def _decode_at(decoder, text, position):
    try:
        value, _end = decoder.raw_decode(text, position)
    except ValueError:
        return None
    return value if isinstance(value, (dict, list)) else None


def _decode_scalar(text):
    try:
        value = json.loads(text, strict=False)
    except ValueError:
        return False
    return not isinstance(value, (dict, list))


def first_json_value(text):
    """Return the first JSON object or array in a body, or None.

    Two deliberate limits, both learned from the live catalogs:
    - The comment-stripped document is tried first, because `///` banners and inline comments
      are what TVBox actually reads (Gson is lenient; json is not).
    - A body that opens with a brace but never decodes is reported as nothing. Scanning deeper
      would return a nested fragment as if it were the whole config, and a broken source would
      pass the gate.
    """
    body = text.lstrip('\ufeff\x00\t \r\n')
    documents = []
    if '//' in body or '/*' in body or '#' in body:
        stripped = strip_json_comments(body).lstrip()
        if stripped:
            documents.append(stripped)
    documents.append(body)
    # strict=False: shipped configs embed raw newlines and tabs inside string values.
    decoder = json.JSONDecoder(strict=False)
    outermost = [document for document in documents if document[:1] in ('{', '[')]
    if outermost:
        for document in outermost:
            value = _decode_at(decoder, document, 0)
            if value is not None:
                return value
        return None
    for document in documents:
        index = 0
        for _attempt in range(12):
            starts = [position for position in (document.find('{', index),
                                                document.find('[', index)) if position >= 0]
            if not starts:
                break
            index = min(starts)
            value = _decode_at(decoder, document, index)
            if value is not None:
                return value
            index += 1
    return None


def classify_bytes(content):
    """Classify a fetched body as (kind, detail); kind None means unusable."""
    if not content or not content.strip():
        return None, 'empty response'
    head = content.lstrip(b'\xef\xbb\xbf\t\r\n')
    if head.startswith((b'\xff\xd8', b'\x89PNG', b'GIF8', b'RIFF', b'%PDF')) or b'\x00' in content[:64]:
        return None, 'binary payload, not a parseable config'
    try:
        text = content.decode('utf-8')
    except UnicodeDecodeError:
        return None, 'body is not UTF-8 text'
    return classify_text(text)


def classify_text(text):
    body = text.strip()
    lowered = body.lower()
    if lowered.startswith('<!doctype html') or lowered.startswith('<html'):
        return None, 'HTML page instead of a config'
    if body.startswith('#EXTM3U') or body.startswith('#EXTINF'):
        return 'playlist', 'M3U playlist'
    if body.startswith('http'):
        return 'playlist', 'URL list'
    parsed = first_json_value(body)
    if parsed is not None:
        if isinstance(parsed, list):
            return ('config', f'bare array with {len(parsed)} item(s)') if parsed else (None, 'empty array')
        if parsed.get('msg') is not None:
            return None, f"error payload: msg={str(parsed['msg'])[:40]}"
        inner = parsed.get('urls')
        if isinstance(inner, list) and inner:
            return 'depot', f'warehouse index with {len(inner)} config(s)'
        keys = sorted(key for key in CONFIG_KEYS if key in parsed)
        if keys:
            return 'config', 'keys: ' + ','.join(keys)
        return None, 'JSON object without TVBox config keys'
    if body[:1] in ('{', '['):
        return None, 'JSON body does not parse'
    if _decode_scalar(body):
        return None, 'JSON scalar, not a config'
    if len(body.encode('utf-8')) >= 64:
        return 'text', f'{len(body)} byte(s) of text'
    return None, f'too small to be a config ({len(body)} byte(s))'


def first_inner_config(content, base_url):
    """Resolve the warehouse index the app would actually open.

    BaseConfig.parseDepot keeps only configs.get(0), so a 多仓 whose first entry is dead is
    dead for us; validating that one URL is what the user would experience.
    """
    try:
        parsed = first_json_value(content.decode('utf-8', 'replace'))
    except (ValueError, UnicodeDecodeError):
        return None
    items = parsed.get('urls') if isinstance(parsed, dict) else None
    for item in items or []:
        if isinstance(item, dict) and isinstance(item.get('url'), str) and item['url'].strip():
            return urljoin(base_url, item['url'].strip())
    return None


# ---------------------------------------------------------------------------
# Inner-stream sampling: a playlist that answers 200 only proves the file exists
# ---------------------------------------------------------------------------

STREAM_SAMPLE_HOSTS = 10
STREAM_TIMEOUT = 6
# Below this many testable hosts the sample says nothing, so it cannot reject.
STREAM_MIN_TESTABLE = 3
# A rejection needs both a majority and a pattern. Two 404s in a ten-host sample is an operator
# difference, and the cost of refusing a working curated source is higher than the cost of
# admitting a mediocre one — the app's own consecutive-failure prune still catches it later.
STREAM_MIN_GONE = 3
# Judged only on what was testable: a region-locked list and a dead-domain list both read as
# broken from a probe box, but only one of them is broken for the user.
STREAM_DEAD_CEILING = 0.5

# Failures that hold for every client, whoever asks.
GONE_MARKERS = ('name or service not known', 'temporary failure in name resolution',
                'nodename nor servname', 'address associated', 'empty response',
                'html response')
# A missing path is gone; 401/403/429 are an access policy that differs by location, and 5xx is
# usually the origin being briefly down.
GONE_STATUS = (404, 405, 410, 414, 451)


def stream_evidence(reason):
    """Classify a failed stream probe as 'gone' (true for everyone) or 'untestable' (true for us)."""
    text = (reason or '').lower()
    if any(marker in text for marker in GONE_MARKERS):
        return 'gone'
    match = re.match(r'^http (\d{3})', text)
    if match:
        return 'gone' if int(match.group(1)) in GONE_STATUS else 'untestable'
    return 'untestable'


def host_diverse_sample(candidates, limit):
    """One URL per host, spread evenly across hosts.

    Deterministic so reruns agree, and host-first so a 44-channel block behind one dead domain
    counts as one vote instead of forty-four.
    """
    per_host = {}
    for url in candidates:
        host = (urlparse(url).hostname or url).lower()
        per_host.setdefault(host, url)
    hosts = sorted(per_host)
    if len(hosts) <= limit:
        return [per_host[host] for host in hosts]
    stride = len(hosts) / limit
    return [per_host[hosts[int(index * stride)]] for index in range(limit)]


def sample_streams(content, base_url):
    """Probe a host-diverse sample of the channels inside a playlist. Returns (ok, detail)."""
    candidates = validate_depot.nested_candidates(content, base_url)
    sample = host_diverse_sample(candidates, STREAM_SAMPLE_HOSTS)
    if not sample:
        return True, 'no stream URLs inside'
    tally = {'alive': 0, 'gone': 0, 'untestable': 0}
    gone = []
    for url in sample:
        result = validate_depot.fetch_http(
            url, STREAM_TIMEOUT, 0,
            max_bytes=validate_depot.MAX_NESTED_BYTES, range_request=True)
        if result.valid:
            tally['alive'] += 1
            continue
        verdict = stream_evidence(result.reason)
        tally[verdict] += 1
        if verdict == 'gone':
            gone.append(f'{urlparse(url).hostname}: {result.reason[:40]}')
    tested = tally['alive'] + tally['gone']
    detail = (f"streams {tally['alive']}/{tested} hosts serve content, "
              f"{tally['untestable']} untestable of {len(sample)} sampled")
    if tested < STREAM_MIN_TESTABLE:
        return True, detail + ', too few testable hosts to judge'
    if tally['gone'] >= STREAM_MIN_GONE and tally['gone'] / tested >= STREAM_DEAD_CEILING:
        return False, detail + f"; {'; '.join(gone[:2])}"
    return True, detail


def gate_url(url, depth=1):
    """Fetch one candidate and require a shape the app can act on. Returns (ok, reason)."""
    if url.startswith('assets://'):
        result = validate_depot.fetch_asset(url, ASSET_ROOT)
    else:
        result = validate_depot.fetch_http(
            url, validate_depot.DEFAULT_TIMEOUT, validate_depot.DEFAULT_RETRIES)
    if not result.valid:
        return False, result.reason
    kind, detail = classify_bytes(result.content)
    if kind is None:
        return False, detail
    if kind == 'depot':
        inner = first_inner_config(result.content, result.final_url or url)
        if not inner:
            return False, f'{detail}; no usable URL inside the index'
        if depth <= 0:
            return True, f'{detail}; nested index not followed'
        ok, reason = gate_url(inner, depth - 1)
        return ok, f'{detail}; first warehouse: {reason}'
    if kind in ('playlist', 'text'):
        # A channel list is worth what its channels are worth, so sample inside it.
        ok, streams = sample_streams(result.content, result.final_url or url)
        return ok, f'{kind}: {detail}; {streams}'
    return True, f'{kind}: {detail}'

# ---------------------------------------------------------------------------
# Structured feeds
# ---------------------------------------------------------------------------

def availability_ranks(feed, refresh=False):
    """Map upstream resource id -> rank for ordering. Never used to admit an entry."""
    document = fetch_cached(feed['availability'], refresh=refresh, kind=f'{feed["id"]} availability',
                            suffix='.json') if feed.get('availability') else None
    ranks = {'reachable': 0, 'restricted': 1, 'unreachable': 2}
    if not document:
        return {}
    try:
        report = json.loads(document)
    except json.JSONDecodeError:
        return {}
    ordered = {}
    for item in report.get('results', []) if isinstance(report, dict) else []:
        if isinstance(item, dict) and isinstance(item.get('resource_id'), str):
            ordered[item['resource_id']] = ranks.get(item.get('status'), 3)
    return ordered


def feed_candidates(feed, refresh=False):
    """Turn a curator's catalog into ordered depot candidates, before any validation."""
    document = fetch_cached(feed['catalog'], refresh=refresh, kind=f'{feed["id"]} catalog',
                            suffix='.json')
    if not document:
        print(f'  Catalog unavailable for feed {feed["id"]}')
        return []
    try:
        parsed = json.loads(document)
    except json.JSONDecodeError as error:
        print(f'  Catalog for feed {feed["id"]} does not parse: {error}')
        return []
    resources = parsed.get('resources') if isinstance(parsed, dict) else None
    if not isinstance(resources, list):
        print(f'  Catalog for feed {feed["id"]} has no resources array')
        return []
    ranks = availability_ranks(feed, refresh=refresh)
    candidates = []
    seen = set()
    for item in resources:
        if not isinstance(item, dict):
            continue
        section = feed['categories'].get(item.get('category'))
        url = (item.get('url') or '').strip()
        if not section or not is_endpoint_url(url) or url in seen:
            continue
        seen.add(url)
        name = str(item.get('name') or urlparse(url).netloc).strip()
        candidates.append({
            'url': url,
            'name': f'{feed["label"]} · {name}',
            'section': section,
            'feed': feed['id'],
            'rank': ranks.get(item.get('id'), 3),
            'upstream_status': ranks.get(item.get('id'), 'unknown'),
        })
    candidates.sort(key=lambda entry: (entry['rank'], entry['name']))
    return candidates


def revalidate_existing(entries, state):
    """Keep existing entries under the consecutive-failure policy; drop only stale ones."""
    kept = []
    total = len(entries)
    print(f'\nRe-validating {total} existing entries (prune after {PRUNE_AFTER_FAILS} consecutive fails)')
    for index, entry in enumerate(entries, 1):
        url = str(entry.get('url') or '').strip()
        if not url:
            print(f'  [{index}/{total}] dropped: entry has no URL')
            continue
        if entry.get('noCheck') or is_no_check(url):
            entry['noCheck'] = True
            state.pop(url, None)
            kept.append(entry)
            continue
        print(f"\n[{index}/{total}] {entry.get('name') or url}")
        ok = validate_url(url)
        state[url] = next_fail_count(int(state.get(url, 0)), ok)
        fails = state[url]
        if keep_entry(fails):
            if not ok:
                print(f'    ! kept after {fails} consecutive failure(s)')
            kept.append(entry)
        else:
            state.pop(url, None)
            print(f'    x dropped after {fails} consecutive failures')
    return kept


def apply_feed(depot, feed, state, refresh=False, dry_run=False):
    """Harvest one feed: expire its own entries, then admit candidates that pass the gate."""
    feed_id = feed['id']
    budget = feed.get('budget', FEED_BUDGET)
    print('\n' + '=' * 60)
    print(f'Feed: {feed_id} ({feed["label"]})')
    print('=' * 60)

    summary = {'feed': feed_id, 'kept': 0, 'expired': 0, 'added': [], 'rejected': []}

    # Pass 1: this feed's own entries age out under the same policy as everything else.
    # Entries stay in place; rebuilding the list in order keeps the asset diff readable.
    for section in validate_depot.SECTIONS:
        rebuilt = []
        for entry in depot.get(section, []):
            if entry.get('feed') != feed_id:
                rebuilt.append(entry)
                continue
            url = str(entry.get('url') or '').strip()
            if not url or entry.get('noCheck') or is_no_check(url):
                entry['noCheck'] = True
                state.pop(url, None)
                rebuilt.append(entry)
                continue
            ok, reason = gate_url(url)
            if ok and reason.startswith('warehouse index'):
                entry['warehouse'] = True
            state[url] = next_fail_count(int(state.get(url, 0)), ok)
            if keep_entry(state[url]):
                rebuilt.append(entry)
            else:
                summary['expired'] += 1
                state.pop(url, None)
                print(f'  x {entry.get("name") or url}: expired ({reason})')
        depot[section] = rebuilt
        summary['kept'] += sum(1 for entry in depot[section] if entry.get('feed') == feed_id)

    # Pass 2: candidates. An URL the depot already carries is not ours to duplicate or claim.
    already = {str(entry.get('url') or '') for section in validate_depot.SECTIONS for entry in depot[section]}
    room = max(0, budget - summary['kept'])
    for candidate in feed_candidates(feed, refresh=refresh):
        url = candidate['url']
        section = candidate['section']
        if url in already:
            continue
        if room <= 0:
            summary['rejected'].append((candidate['name'], 'feed budget reached'))
            continue
        if is_no_check(url):
            ok, reason, no_check = True, 'noCheck host, not probed', True
        else:
            ok, reason = gate_url(url)
            no_check = False
        if not ok:
            summary['rejected'].append((candidate['name'], reason))
            continue
        entry = {'url': url, 'name': candidate['name'], 'feed': feed_id}
        if reason.startswith('warehouse index'):
            entry['warehouse'] = True
        if no_check:
            entry['noCheck'] = True
        else:
            state[url] = 0
        depot[section].append(entry)
        already.add(url)
        room -= 1
        summary['added'].append((section, candidate['name'], reason))
        if not dry_run:
            time.sleep(0.3)  # Be nice to servers

    for name, reason in summary['rejected']:
        print(f'  - {name}: {reason}')
    for section, name, reason in summary['added']:
        print(f'  + {section}: {name} ({reason})')
    print(f'  feed {feed_id}: kept {summary["kept"]}, added {len(summary["added"])}, '
          f'expired {summary["expired"]}, rejected {len(summary["rejected"])}')
    return summary

def load_existing_depot():
    """Load existing depot.json"""
    try:
        with open(DEPOT_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception as e:
        print(f"Error loading existing depot.json: {e}")
        return {"vod": [], "live": []}


def load_state(depot=None):
    """Load the cross-run failure counters, migrating any that leaked into the asset.

    failCount belongs to the tool, not to depot.json: the asset is the seed for a fresh
    install, and shipping an entry at failCount=2 would let the device's first failed probe
    delete a source the app has never actually checked.
    """
    state = {}
    try:
        with open(STATE_FILE, 'r', encoding='utf-8') as f:
            for url, count in json.load(f).items():
                state[url] = int(count)
    except (OSError, ValueError):
        pass
    for section in validate_depot.SECTIONS:
        for entry in (depot or {}).get(section, []):
            if isinstance(entry, dict) and 'failCount' in entry:
                url = str(entry.get('url') or '')
                try:
                    state[url] = max(state.get(url, 0), int(entry['failCount'] or 0))
                except (TypeError, ValueError):
                    state.setdefault(url, 0)
                del entry['failCount']
    return state


def save_state(state):
    pathlib.Path(STATE_FILE).parent.mkdir(parents=True, exist_ok=True)
    validate_depot.atomic_write_json(pathlib.Path(STATE_FILE), state)

def deduplicate_sources(sources):
    """Remove duplicate URLs within a source list, keeping the first occurrence"""
    seen = set()
    deduped = []
    dupes = 0
    for source in sources:
        if source['url'] not in seen:
            seen.add(source['url'])
            deduped.append(source)
        else:
            dupes += 1
    if dupes > 0:
        print(f"  Removed {dupes} duplicate(s)")
    return deduped

def merge_sources(existing, new_vod, new_live):
    """Merge new sources with existing ones"""
    # Create sets of existing URLs for quick lookup
    existing_vod_urls = {s['url'] for s in existing.get('vod', [])}
    existing_live_urls = {s['url'] for s in existing.get('live', [])}
    
    # Add new sources that don't exist
    for source in new_vod:
        if source['url'] not in existing_vod_urls:
            existing['vod'].append(source)
            existing_vod_urls.add(source['url'])
    
    for source in new_live:
        if source['url'] not in existing_live_urls:
            existing['live'].append(source)
            existing_live_urls.add(source['url'])
    
    return existing

def save_depot(data, dry_run=False):
    """Save depot.json, or report what would change."""
    if dry_run:
        print(f"\nDry run: {DEPOT_FILE} left untouched")
        return
    validate_depot.atomic_write_json(pathlib.Path(DEPOT_FILE), data)
    print(f"\nSaved to {DEPOT_FILE}")

def selected_feeds(args):
    if args.no_feeds:
        return []
    if args.feed:
        unknown = [name for name in args.feed if name not in {feed['id'] for feed in FEEDS}]
        if unknown:
            raise SystemExit(f"Unknown feed id(s): {', '.join(unknown)}; "
                             f"available: {', '.join(feed['id'] for feed in FEEDS)}")
        return [feed for feed in FEEDS if feed['id'] in args.feed]
    return list(FEEDS)

def main():
    parser = argparse.ArgumentParser(description='TVAPP Source Updater')
    parser.add_argument('--refresh', action='store_true',
                        help='Force re-fetch README files from network (ignores cache)')
    parser.add_argument('--feed', action='append', metavar='ID',
                        help='Run only these structured feeds (repeatable). Default: all configured feeds')
    parser.add_argument('--no-feeds', action='store_true',
                        help='Skip structured feeds and only run the README scrape')
    parser.add_argument('--dry-run', action='store_true',
                        help='Report the gate verdicts and changes without writing depot.json')
    parser.add_argument('--list-feeds', action='store_true',
                        help='List the configured feeds and exit')
    args = parser.parse_args()

    if args.list_feeds:
        for feed in FEEDS:
            print(f"{feed['id']:<10} {feed['label']:<12} budget={feed.get('budget', FEED_BUDGET)} "
                  f"categories={feed['categories']}")
        return
    
    print("=" * 60)
    print("TVAPP Source Updater")
    if args.refresh:
        print("Mode: REFRESH (re-fetching from network)")
    else:
        print("Mode: CACHED (using saved versions)")
    if args.dry_run:
        print("Mode: DRY RUN (depot.json will not be written)")
    print("=" * 60)
    
    # Load existing depot first, then the sidecar failure counters. Reading the depot into
    # load_state also migrates any failCount that leaked into the asset.
    existing = load_existing_depot()
    state = load_state(existing)
    
    # Step 1: Deduplicate existing entries within each category
    print("\n" + "=" * 60)
    print("Deduplicating existing entries")
    print("=" * 60)
    print("VOD:")
    existing['vod'] = deduplicate_sources(existing.get('vod', []))
    print("Live:")
    existing['live'] = deduplicate_sources(existing.get('live', []))
    
    # Step 2: Age existing entries under the consecutive-failure policy. One failed fetch
    # is far more often a rate limit than a dead source, so this must never delete outright.
    print("\n" + "=" * 60)
    print("Re-validating existing VOD entries")
    print("=" * 60)
    existing['vod'] = revalidate_existing(existing['vod'], state)
    
    print("\n" + "=" * 60)
    print("Re-validating existing Live entries")
    print("=" * 60)
    existing['live'] = revalidate_existing(existing['live'], state)
    
    # Step 3: Fetch and extract new sources from READMEs
    all_vod_sources = []
    all_live_sources = []
    
    for readme_url in README_URLS:
        print(f"\n{'=' * 60}")
        print(f"Processing: {readme_url}")
        print("=" * 60)
        
        readme_content = fetch_readme(readme_url, refresh=args.refresh)
        if not readme_content:
            print(f"Failed to fetch README from {readme_url}. Skipping.")
            continue
        
        vod_sources, live_sources = extract_sources(readme_content)
        
        all_vod_sources.extend(vod_sources)
        all_live_sources.extend(live_sources)
    
    # Step 4: Validate new sources
    if all_vod_sources:
        print("\n" + "=" * 60)
        print("Validating new VOD sources")
        print("=" * 60)
        valid_vod = validate_sources(all_vod_sources)
    else:
        valid_vod = []
    
    if all_live_sources:
        print("\n" + "=" * 60)
        print("Validating new Live sources")
        print("=" * 60)
        valid_live = validate_sources(all_live_sources)
    else:
        valid_live = []
    
    # Step 5: Merge new sources into existing
    merged = merge_sources(existing, valid_vod, valid_live)

    # Step 6: Structured feeds. Runs last so a feed never duplicates a URL the README
    # scrape already admitted.
    feed_summaries = [apply_feed(merged, feed, state, refresh=args.refresh, dry_run=args.dry_run)
                      for feed in selected_feeds(args)]
    
    # Step 7: Final deduplication after merge
    print("\n" + "=" * 60)
    print("Final deduplication after merge")
    print("=" * 60)
    print("VOD:")
    merged['vod'] = deduplicate_sources(merged['vod'])
    print("Live:")
    merged['live'] = deduplicate_sources(merged['live'])
    
    # Save
    save_depot(merged, dry_run=args.dry_run)
    if not args.dry_run:
        # Only URLs that survived this run keep a counter; a deleted source starts over.
        present = {str(entry.get('url') or '') for section in validate_depot.SECTIONS
                   for entry in merged.get(section, [])}
        save_state({url: count for url, count in state.items() if url in present})
    
    # Summary
    print("\n" + "=" * 60)
    print("Summary")
    print("=" * 60)
    print(f"VOD sources: {len(merged['vod'])}")
    print(f"Live sources: {len(merged['live'])}")
    print(f"New VOD sources added: {len(valid_vod)}")
    print(f"New Live sources added: {len(valid_live)}")
    for summary in feed_summaries:
        print(f"Feed {summary['feed']}: +{len(summary['added'])} admitted, "
              f"-{summary['expired']} expired, {len(summary['rejected'])} rejected by the gate")
    print("=" * 60)

if __name__ == "__main__":
    main()
