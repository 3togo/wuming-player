"""Netdisk (cloud-drive) family dictionary and per-artifact classification.

Every family below is grounded either in this repository's OpenDrive adapters or in the
audited CatVod evidence docs:

- coverage comes from ``app/.../cloud/opendrive/OpenDriveEngine.defaultProviders()`` and
  the management catalogue in ``app/.../cloud/CloudStorageProvider.java``;
- endpoint signatures come from ``docs/provider-protocol-evidence.md`` and
  ``docs/catvod-opendrive-audit.md``.

The point of this module is *not* to prove behaviour. It produces candidate findings, all
marked ``unverified``, ranked by how many independent kinds of evidence (class name, host,
endpoint) agree. Nothing here can upgrade a finding; only readable source or a device
trace can, per the standing change rule.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Sequence, Set, Tuple

# Matching rules:
#   class_keywords are matched against each dot-separated class-name segment. Tokens
#   shorter than four characters match only as a whole segment, so "uc" never matches
#   "duck" or "educated". Longer tokens match inside a segment, so "quark" matches
#   "QuarkApi" but nothing accidentally.
_MIN_WHOLE_TOKEN = 4


@dataclass(frozen=True)
class Family:
    """One netdisk provider family."""

    id: str
    label: str
    opendrive_provider: str = ""
    coverage: str = ""  # how OpenDrive stands today, in one line
    class_keywords: Tuple[str, ...] = ()
    hosts: Tuple[str, ...] = ()
    endpoints: Tuple[str, ...] = ()

    @property
    def opendrive_kind(self) -> str:
        if self.opendrive_provider:
            return "builtin"
        return "none"


FAMILIES: Tuple[Family, ...] = (
    Family(
        id="quark",
        label="Quark 夸克/夸父",
        opendrive_provider="quark",
        coverage="device-proven for original playback; transcode selection unit-tested",
        class_keywords=("quark", "夸克", "夸父", "qrk"),
        hosts=("pan.quark.cn", "drive.quark.cn", "quark.cn", "biz-pc.quark.cn"),
        endpoints=(
            "/1/clouddrive/file/download",
            "/1/clouddrive/file/v2/play",
            "/1/clouddrive/file/sort",
            "/1/clouddrive/file/delete",
            "/1/clouddrive/share/sharepage/save",
            "action_type=2",
        ),
    ),
    Family(
        id="uc",
        label="UC 网盘/优汐",
        opendrive_provider="uc",
        coverage="device-proven; intentional superset of the pinned CatVod source",
        class_keywords=("ucapi", "ucvip", "ucdrive", "优汐", "uc云",
                        # Short tokens only match a whole dotted segment (see
                        # `matches_class_keyword`), so `com.github.catvod.spider.UC` counts
                        # while `Duck` does not.
                        "uc", "ucshare", "ucpan"),
        hosts=("drive.uc.cn", "pc-api.uc.cn", "dl-pc.drive.uc.cn", "uc.cn"),
        endpoints=(
            "/1/clouddrive/file/download?pr=UCBrowser&fr=pc",
            "/1/clouddrive/file/v2/play",
            "/1/clouddrive/share/sharepage/save",
            "save_as_top_fids",
            "__puus",
        ),
    ),
    Family(
        id="aliyun",
        label="Aliyun 阿里云盘",
        opendrive_provider="alipan",
        coverage="compatible core; preview fallback not yet device-proven",
        class_keywords=("aliyun", "alipan", "aliyunpan", "阿里云盘", "阿里网盘",
                        "ali", "alishare"),
        hosts=("alipan.com", "aliyundrive.com", "adrive.aliyundrive.com", "openapi.alipan.com"),
        endpoints=(
            "/adrive/v1.0/openFile/getDownloadUrl",
            "/adrive/v1.0/shareLink/getShareToken",
            "/adrive/v1.0/openFile/video/preview/playInfo",
            "/v2/file/get_download_url",
            "x-share-token",
        ),
    ),
    Family(
        id="baidu",
        label="Baidu 百度网盘",
        opendrive_provider="baidu",
        coverage="contract-covered; signing is account-sensitive",
        class_keywords=("baidu", "baiduwp", "百度", "panbaidu"),
        hosts=("pan.baidu.com", "d.pcs.baidu.com", "yun.baidu.com", "panapi.baidu.com"),
        endpoints=(
            "/rest/2.0/xpan/file?method=streaming",
            "/api/wapui/sharev2/wapshare Choice",
            "/api/sharedownloadverifycode",
            "/rest/2.0/xpan/nas?method=streaming",
        ),
    ),
    Family(
        id="pan123",
        label="123 网盘",
        opendrive_provider="123pan",
        coverage="core flow implemented; domains and Android headers corrected after audit",
        class_keywords=("pan123", "123pan", "p123", "cloud123"),
        hosts=("123pan.com", "123pan.cn", "123684.com", "123865.com", "123912.com", "123592.com"),
        endpoints=(
            "/api/share/get",
            "/share/download/info",
            "/api/download/info",
            "platform=android",
        ),
    ),
    Family(
        id="tianyi",
        label="Tianyi 天翼-cloud (189)",
        opendrive_provider="tianyi",
        coverage="playback contract aligned; automated login intentionally differs",
        class_keywords=("tianyi", "ty189", "天翼", "cloud189"),
        hosts=("cloud.189.cn", "tianyi.189.cn", "h5.cloud.189.cn"),
        endpoints=(
            "getNewVlcVideoPlayUrl",
            "/api/portal/share-link/info/v2/getSharPageWelcome.action",
            "/api/portal/listShares.action",
        ),
    ),
    Family(
        id="mobile139",
        label="China Mobile 139 (confirmed OpenDrive gap)",
        opendrive_provider="",
        coverage="no built-in provider; do not implement without a live share fixture",
        class_keywords=("yundrive", "mobile139", "caiyun", "139cloud", "移动云盘"),
        hosts=("caiyun.139.com", "yun.139.com", "h5.caiyun.139.com"),
        endpoints=(
            "/orchestration/",
            "/api-portal-workbench/",
            "/api-family-share-case-serv/getoutsidelink",
        ),
    ),
    Family(
        id="drive115",
        label="115 网盘",
        opendrive_provider="",
        coverage="management URL only; no browsing provider (docs/opendrive-115-review.md)",
        class_keywords=("drive115", "pan115", "115pan", "vip115", "p115", "p115share"),
        hosts=("115.com", "webapi.115.com", "v.anxia.com", "proapi.115.com"),
        endpoints=(
            "/files/download",
            "/app/chrome/down",
            "/files/video?aid=1",
        ),
    ),
    Family(
        id="pikpak",
        label="PikPak",
        opendrive_provider="",
        coverage="no provider",
        class_keywords=("pikpak", "pikpakdrive"),
        hosts=("mypikpak.com", "api-drive.mypikpak.com"),
        endpoints=(
            "/drive/v1/files/",
            "/v1/auth/token",
            "/drive/share/share-password",
        ),
    ),
    Family(
        id="xunlei",
        label="Xunlei/Thunder 迅雷网盘",
        opendrive_provider="",
        coverage="no provider; only a vendored download snapshot exists",
        class_keywords=("xunlei", "thunder", "xluser", "迅雷"),
        hosts=("pan.xunlei.com", "api-pan.xunlei.com", "api-shoulei-ssl.xunlei.com"),
        endpoints=(
            "/android/share/list",
            "/drive/v1/files/download",
            "/device/v1/files/",
        ),
    ),
    Family(
        id="alist",
        label="Alist aggregator",
        opendrive_provider="",
        coverage="separate legacy WebView stack, not an OpenDrive provider",
        class_keywords=("alist",),
        hosts=("alist.nn.ci",),
        endpoints=(
            "/api/fs/list",
            "/api/fs/get",
            "/api/me",
        ),
    ),
    Family(
        id="webdav",
        label="WebDAV",
        opendrive_provider="webdav",
        coverage="OpenDrive-only capability; not in the pinned CatVod dispatcher",
        class_keywords=("webdav", "davclient"),
        hosts=("dav.box.net",),
        endpoints=(
            "PROPFIND",
            "Depth: 1",
        ),
    ),
)

# Cloud-related regardless of family: these mark an artifact as worth deeper study even
# when no family keyword matched (a "mydrive" bundle may brand itself neutrally).
GENERIC_CLOUD = ("netdrive", "mydrive", "mydriveguard", "mdrive", "clouddrive", "driverservice",
                 "yunpan", "panshare", "cloudapi", "diskfd")

_HTTP_RE = re.compile(r"^https?://[^\s\"'<>]{6,200}$", re.IGNORECASE)
# Hostnames are case-insensitive on the wire but are written lowercase; matching case-
# sensitively lets us reject dotted *class/action names* (e.g. `android.app.ActivityThread`,
# `com.bea.xml.stream.MXParserFactory`) that would otherwise masquerade as cloud-drive hosts.
_HOST_RE = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$")
_IP_HOST_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")
# Bare dotted names that are framework/package identifiers, not cloud-drive hosts. They pass
# the lowercase host regex but carry no DNS meaning; publishing them as leads only buries signal.
_HOST_NOISE_PREFIXES = (
    "android.", "androidx.", "java.", "javax.", "com.android.", "com.google.", "com.xunlei.",
    "com.btkele.", "com.taobao.", "com.quark.", "com.ucmobile", "com.alicloud.", "com.bea.",
    "execjs.", "jaxp.", "gson.", "slf4j.", "xml.", "div.", "c.enumConstants",
)

# Endpoint mining has to reject as much as it accepts. A real plugin string pool is mostly
# framework noise: regex fragments, internal Java paths and documentation links. Anything
# that is not plainly a target address is dropped rather than published as a lead.
_REGEXY = re.compile(r"[*+?()\[\]{}$^|\\]")
_ASSET_SUFFIXES = (".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg", ".woff",
                   ".woff2", ".ttf", ".mp3", ".mp4", ".m3u8", ".ts", ".apk", ".jar",
                   ".bdmv", ".ass", ".srt", ".vtt", ".subtitles", ".db", ".json", ".xml")
_MIN_ENDPOINT_LENGTH = 8
_INTERNAL_PATHS = ("/meta-inf/", "/com/android/", "/java/", "/javax/", "/kotlin/", "/org/", "/androidx/")
_FRAMEWORK_HOSTS = (
    "schemas.android.com", "developer.android.com", "source.android.com", "android.googlesource.com",
    "www.w3.org", "xml.apache.org", "java.sun.com", "docs.oracle.com", "maven.apache.org",
    "apache.org", "jquery.org", "googleapis.com", "fonts.googleapis.com", "t.me", "github.com",
    "raw.githubusercontent.com", "gitee.com", "jitpack.io", "opensource.org",
)


def _segments(name: str) -> List[str]:
    return [s.lower() for s in name.split(".")]


def matches_class_keyword(class_name: str, keyword: str) -> bool:
    """Segment-aware keyword matching for a dotted class name."""
    token = keyword.lower()
    for segment in _segments(class_name):
        if len(token) < _MIN_WHOLE_TOKEN and len(token.encode("utf-8")) < 6:
            if segment == token:
                return True
            continue
        if segment == token or token in segment:
            return True
    return False


@dataclass
class FamilyMatch:
    """Correlation evidence for one family inside one artifact."""

    family: Family
    class_hits: List[str] = field(default_factory=list)
    host_hits: List[str] = field(default_factory=list)
    endpoint_hits: List[str] = field(default_factory=list)

    @property
    def kinds(self) -> int:
        return sum(1 for hits in (self.class_hits, self.host_hits, self.endpoint_hits) if hits)

    @property
    def confidence(self) -> str:
        if self.class_hits and (self.host_hits or self.endpoint_hits):
            return "high"
        if self.kinds >= 2:
            return "medium"
        if self.kinds == 1:
            return "low"
        return "none"


def _scan_strings(strings: Iterable[str], needles: Sequence[str], allow_variant: bool = True) -> List[str]:
    """Case-insensitive substring search returning unique *matched values*, capped."""
    found: Set[str] = set()
    lowered = [(needle.lower(), needle) for needle in needles]
    for value in strings:
        probe = value.lower()
        for needle_lower, original in lowered:
            if needle_lower not in probe:
                continue
            # Endpoint signatures are usually embedded in a longer URL; keep the value
            # only when it still looks like a path/URL so we do not publish junk.
            trimmed = value.strip()
            if allow_variant and not _looks_like_reference(trimmed, needle_lower):
                continue
            found.add(trimmed[:200])
            break
        if len(found) >= 40:
            break
    return sorted(found)


def _looks_like_reference(value: str, needle: str) -> bool:
    """Keep short, single-token evidence — hosts appear both bare and inside URLs.

    Whole sentences are dropped even when they contain the needle: publishing prose into
    a ledger row helps nobody, and a filled template is readable source-level evidence,
    not a string pool hit.
    """
    return len(value) <= 200 and not any(ch.isspace() for ch in value)


def classify(classes: Sequence[str], strings: Sequence[str]) -> List[FamilyMatch]:
    """Return per-family correlation evidence for one set of dexs."""
    matches: List[FamilyMatch] = []
    for family in FAMILIES:
        match = FamilyMatch(family=family)
        for name in classes:
            if any(matches_class_keyword(name, keyword) for keyword in family.class_keywords):
                match.class_hits.append(name)
        match.host_hits = _scan_strings(strings, family.hosts)[:12]
        match.endpoint_hits = _scan_strings(strings, family.endpoints)[:12]
        if match.confidence != "none":
            matches.append(match)
    matches.sort(key=lambda m: (m.kinds, len(m.class_hits)), reverse=True)
    return matches


def is_cloud_related(classes: Sequence[str], strings: Sequence[str], matches: Sequence[FamilyMatch]) -> bool:
    """True when an artifact looks like a cloud-drive plugin even without a family hit."""
    if matches:
        return True
    for name in list(classes)[:4000]:
        lowered = name.lower()
        if any(token in lowered for token in GENERIC_CLOUD):
            return True
    probe = " ".join(list(strings)[:20000]).lower()
    return any(token in probe for token in GENERIC_CLOUD)


def mine_addresses(strings: Sequence[str], limit: int = 200) -> Dict[str, int]:
    """Address-like literals (hosts, URL-shaped, deep paths) from one artifact's string pool.

    Shared by :func:`endpoint_candidates` (which additionally rejects known family
    signatures) and the extraction stage, which needs the full pool to attribute
    addresses to families. Framework and documentation hosts, asset suffixes, internal
    Java paths and regex fragments are dropped; everything left is a *lead*, never proof.
    """
    counts: Dict[str, int] = {}
    for value in strings:
        candidate = value.strip().strip("\"'`;,)]}")
        if not candidate:
            continue
        if candidate.startswith(("https://", "http://")):
            try:
                from urllib.parse import urlparse

                parsed = urlparse(candidate)
            except ValueError:
                continue
            host = parsed.netloc.lower().split(":")[0]
            # Drop framework and documentation hosts; a query string is meaningless here.
            if "." not in host or host in ("localhost",) or _IP_HOST_RE.match(host) \
                    or any(host == frame or host.endswith("." + frame) for frame in _FRAMEWORK_HOSTS):
                continue
            if parsed.path.lower().endswith(_ASSET_SUFFIXES):
                continue
            candidate = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
        elif candidate.startswith("/"):
            if candidate.count("/") < 2 or candidate.lower().endswith(_ASSET_SUFFIXES):
                continue
        elif not _HOST_RE.match(candidate):
            continue
        elif candidate.startswith(_HOST_NOISE_PREFIXES):
            continue
        if any(ch.isspace() for ch in candidate) or _REGEXY.search(candidate):
            continue
        if len(candidate) < _MIN_ENDPOINT_LENGTH:
            continue
        lowered = candidate.lower()
        if lowered.startswith(_INTERNAL_PATHS):
            continue
        counts[candidate] = counts.get(candidate, 0) + 1
        if len(counts) > limit * 40:
            break
    return dict(sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:limit])


def endpoint_candidates(strings: Sequence[str], limit: int = 200) -> Dict[str, int]:
    """Mine URL-shaped / path-shaped literals that are not already known family signatures.

    These are *leads*: every returned string is unverified evidence of something the
    plugin talks to, never of what it means.
    """
    known = {needle.lower() for family in FAMILIES for needle in family.endpoints}
    known.update(host.lower() for family in FAMILIES for host in family.hosts)
    mined = mine_addresses(strings, limit * 40)
    filtered = {candidate: count for candidate, count in mined.items()
                if candidate.lower() not in known
                and not any(needle and needle in candidate.lower() for needle in known)}
    return dict(list(filtered.items())[:limit])


def families_by_id() -> Dict[str, Family]:
    return {family.id: family for family in FAMILIES}
