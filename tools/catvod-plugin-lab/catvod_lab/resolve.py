"""Turn a plugin reference from a config into an absolute download URL.

Three behaviours are copied from ``app/.../api/loader/JarLoader.parseJar`` because they
decide whether a reference is fetchable at all:

1. ``<url>;md5;<md5>`` — everything after ``;md5;`` is integrity metadata, not part of the
   URL. The md5 slot may itself be a URL that must be fetched to learn the digest.
2. A reference without a scheme is **page-relative**. configs really do ship
   ``./jar/pg.jar`` and ``../jar/pro.jar``, so these resolve against the *final* config
   URL after redirects; resolving against the requested URL silently downloads the wrong
   host.
3. ``assets://`` and ``file://`` references are local to an installed app. They are
   recorded as skipped and never fetched.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional
from urllib.parse import urljoin, urlparse

MD5_SEPARATOR = ";md5;"
_EXTENSION_FALLBACK = {
    ".jar": "jar",
    ".zip": "zip",
    ".apk": "apk",
    ".so": "so",
    ".dex": "dex",
    ".aar": "aar",
}

# Reference pre-filter. Sites routinely carry an `ext` field holding a *page* URL (a
# nested configuration or the maintainer's homepage). Downloading those turns the corpus
# into a haystack of HTML, so extensions we would never expect on a plugin are refused
# before a request is made. `artifact.accepts_payload` is the second gate, because
# hosting configurations lie about extensions.
PLUGIN_EXTENSIONS = frozenset({".jar", ".zip", ".apk", ".dex", ".aar", ".so", ".bin", ".pak", ".dat"})
NEVER_PLUGIN_EXTENSIONS = frozenset({
    ".json", ".json5", ".js", ".m3u", ".m3u8", ".txt", ".xml", ".html", ".htm",
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".css", ".md", ".pdf", ".doc", ".docx",
    ".xls", ".xlsx", ".epub", ".torrent", ".mp4", ".mkv", ".ts", ".gz",
})
MIN_ARTIFACT_BYTES = 512


@dataclass
class Resolved:
    """A plugin reference broken into its download parts."""

    raw: str
    kind: str  # http | assets | file | relative | unsupported
    url: str = ""
    md5: str = ""
    md5_is_url: bool = False
    skip_reason: str = ""

    @property
    def fetchable(self) -> bool:
        return bool(self.url) and not self.skip_reason

    def extension_hint(self) -> str:
        """Best-effort file extension from the URL path; used for storage naming only.

        Detection never relies on this: a `.so` may be a ZIP and a `.jar` may be an ELF.
        """
        path = urlparse(self.url).path.rsplit("/", 1)[-1]
        dot = path.rfind(".")
        if dot <= 0:
            return "bin"
        return _EXTENSION_FALLBACK.get(path[dot:].lower(), "bin")


def split_md5(value: str) -> tuple:
    """Split ``<ref>;md5;<md5-or-url>`` into its parts."""
    if MD5_SEPARATOR not in value:
        return value.strip(), ""
    head, _, tail = value.partition(MD5_SEPARATOR)
    return head.strip(), tail.strip()


def resolve_reference(raw: str, config_url: str) -> Resolved:
    """Resolve one reference declared by a configuration served from `config_url`.

    Parameters
    ----------
    raw
        The literal reference from the configuration (may include `;md5;`).
    config_url
        The *final* URL the configuration was served from, so relative references land
        on the same host after redirects.
    """
    reference = (raw or "").strip()
    if not reference:
        return Resolved(raw=raw, kind="empty", skip_reason="empty reference")

    head, md5 = split_md5(reference)
    md5_is_url = md5.lower().startswith(("http://", "https://"))

    lowered = head.lower()
    if lowered.startswith("assets://"):
        return Resolved(raw=raw, kind="assets", md5=md5, md5_is_url=md5_is_url,
                        skip_reason="app-local asset reference")
    if lowered.startswith("file://"):
        return Resolved(raw=raw, kind="file", md5=md5, md5_is_url=md5_is_url,
                        skip_reason="local file reference")
    if lowered.startswith(("http://", "https://")):
        return _decide(raw, head, "http", md5, md5_is_url)

    base = (config_url or "").strip()
    if not base:
        return Resolved(raw=raw, kind="unsupported", md5=md5, md5_is_url=md5_is_url,
                        skip_reason="relative reference without a config base URL")
    joined = urljoin(base, head)
    if not joined.lower().startswith(("http://", "https://")):
        return Resolved(raw=raw, kind="unsupported", md5=md5, md5_is_url=md5_is_url,
                        skip_reason=f"resolved to a non-http target: {joined[:120]}")
    return _decide(raw, joined, "relative", md5, md5_is_url)


def _decide(raw: str, url: str, kind: str, md5: str, md5_is_url: bool) -> Resolved:
    refusal = refusal_reason(url)
    final_kind = "non-artifact" if refusal else kind
    return Resolved(raw=raw, kind=final_kind, url=url, md5=md5, md5_is_url=md5_is_url,
                    skip_reason=refusal)


def refusal_reason(url: str) -> str:
    """Why `url` cannot be a plugin artifact, or "" when it plausibly is one."""
    path = urlparse(url).path
    if not path or path.endswith("/"):
        return "points at a directory, not a file"
    segment = path.rsplit("/", 1)[-1]
    dot = segment.rfind(".")
    if dot <= 0:
        # Extension-less references really are served (`./CFlLnSbJ`); let the content
        # check decide rather than trusting the absence of a suffix either way.
        return ""
    extension = segment[dot:].lower()
    if extension in NEVER_PLUGIN_EXTENSIONS:
        return f"extension suggests a document, not a plugin ({extension})"
    if extension not in PLUGIN_EXTENSIONS:
        return f"unlikely plugin extension ({extension})"
    return ""


def expected_md5(resolved: Resolved, fetch) -> Optional[str]:
    """Resolve the declared md5, downloading it first when the slot is itself a URL."""
    if not resolved.md5:
        return None
    if not resolved.md5_is_url:
        return resolved.md5 or None
    result = fetch(resolved.md5)
    return (result.body.decode("utf-8", errors="replace").strip() or None) if result.ok else None
