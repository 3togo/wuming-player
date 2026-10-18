"""Bounded HTTP retrieval.

Third-party configuration hosts are unreliable: some are reachable only over HTTP, some
only through a GitHub mirror from certain networks, and some serve an HTML error page
with HTTP 200. Every failure mode that changes what we believe we downloaded either stops
the request or is recorded, so a later stage never mistakes an error page for an artifact.

Nothing here authenticates, sends cookies, or follows anything other than `http(s)`.
"""

from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

FALLBACK_MIRRORS = ("ghproxy.net", "gh-proxy.com")
_MIRRORED_HOSTS = ("raw.githubusercontent.com", "github.com", "api.github.com")
READ_CHUNK = 64 * 1024


class TooLarge(Exception):
    """The server sent more bytes than the caller allowed."""


@dataclass
class FetchResult:
    """Outcome of one retrieval attempt."""

    ok: bool
    url: str
    requested_url: str = ""
    via_mirror: str = ""
    status: Optional[int] = None
    content_type: str = ""
    content_length: Optional[int] = None
    history: List[str] = field(default_factory=list)
    body: bytes = b""
    error: str = ""
    elapsed_ms: int = 0

    @property
    def final_url(self) -> str:
        """The URL after redirects — what relative references must resolve against."""
        return self.history[-1] if self.history else self.url

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.body).hexdigest() if self.body else ""

    @property
    def md5(self) -> str:
        return hashlib.md5(self.body).hexdigest() if self.body else ""

    def as_record(self) -> Dict[str, object]:
        return {
            "ok": self.ok,
            "url": self.url,
            "requested_url": self.requested_url,
            "via_mirror": self.via_mirror,
            "status": self.status,
            "content_type": self.content_type,
            "content_length": self.content_length,
            "history": self.history,
            "bytes": len(self.body),
            "sha256": self.sha256,
            "md5": self.md5,
            "error": self.error,
            "elapsed_ms": self.elapsed_ms,
        }


def load_mirrors(mirror_file: Optional[Path] = None) -> List[str]:
    """Read the repository mirror list, falling back to a small builtin set.

    ``github_proxies.json`` is already used by the desktop client's GitHub retrieval,
    so reusing it keeps behaviour consistent across the project.
    """
    from pathlib import Path as _Path

    path = _Path(mirror_file) if mirror_file is not None else None
    if path is not None and path.is_file():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            hosts = [str(p.get("host", "")).strip() for p in data.get("proxies", [])]
            return [h.strip("/") for h in hosts if h]
        except Exception:
            return list(FALLBACK_MIRRORS)
    return list(FALLBACK_MIRRORS)


class Fetcher:
    """Small retry/mirror aware HTTP client with hard byte limits."""

    def __init__(
        self,
        timeout: float = 20.0,
        retries: int = 2,
        max_bytes: int = 64 * 1024 * 1024,
        mirrors: Optional[Sequence[str]] = None,
        allow_http_fallback: bool = True,
        session=None,
    ) -> None:
        self.timeout = timeout
        self.retries = max(0, retries)
        self.max_bytes = max_bytes
        self.mirrors = [m for m in (mirrors or []) if m]
        self.allow_http_fallback = allow_http_fallback
        self._session = session
        self.request_count = 0

    def _session_instance(self):
        if self._session is None:
            import requests  # imported lazily so offline stages need no dependency

            self._session = requests.Session()
        return self._session

    def candidates(self, url: str) -> List[tuple]:
        """Build the download candidates for a URL as ``(url, route)`` pairs.

        `route` records how the candidate differs from the original request so the
        provenance ledger can show that a GitHub artifact arrived through a mirror.
        """
        out: List[tuple] = [(url, "direct")]
        lowered = url.lower()
        if any(host in lowered for host in _MIRRORED_HOSTS):
            out.extend((f"https://{mirror}/{url}", f"mirror:{mirror}") for mirror in self.mirrors)
        if self.allow_http_fallback and lowered.startswith("https://"):
            out.append(("http://" + url[len("https://"):], "http-downgrade"))
        return out

    def get(self, url: str, accept: Optional[str] = None) -> FetchResult:
        """Fetch `url`, trying mirrors and an HTTP downgrade before giving up."""
        last_error = "no candidate URL"
        for candidate, route in self.candidates(url):
            result = self._attempt(candidate, requested=url, accept=accept, route=route)
            if result.ok:
                return result
            last_error = result.error or last_error
        return FetchResult(ok=False, url=url, requested_url=url, error=last_error)

    def _attempt(self, candidate: str, requested: str, accept: Optional[str], route: str = "direct") -> FetchResult:
        session = self._session_instance()
        headers = {"User-Agent": USER_AGENT}
        if accept:
            headers["Accept"] = accept
        started = time.time()
        for attempt in range(self.retries + 1):
            self.request_count += 1
            try:
                response = session.get(
                    candidate,
                    timeout=self.timeout,
                    headers=headers,
                    allow_redirects=True,
                    stream=True,
                )
            except Exception as error:  # network-level failure; retry on another attempt
                if attempt >= self.retries:
                    return FetchResult(
                        ok=False,
                        url=candidate,
                        requested_url=requested,
                        error=f"{type(error).__name__}: {error}"[:300],
                        elapsed_ms=int((time.time() - started) * 1000),
                    )
                continue

            history = [response.url] + [h.url for h in reversed(response.history)]
            head = {"Content-Type": response.headers.get("Content-Type", ""),
                    "Content-Length": response.headers.get("Content-Length", "")}
            declared = head["Content-Length"]
            if declared and declared.isdigit() and int(declared) > self.max_bytes:
                response.close()
                return FetchResult(
                    ok=False,
                    url=candidate,
                    requested_url=requested,
                    status=response.status_code,
                    history=history,
                    error=f"too_large: content-length {declared} > cap {self.max_bytes}",
                    elapsed_ms=int((time.time() - started) * 1000),
                )

            chunks: List[bytes] = []
            size = 0
            try:
                for chunk in response.iter_content(chunk_size=READ_CHUNK):
                    if not chunk:
                        continue
                    size += len(chunk)
                    if size > self.max_bytes:
                        response.close()
                        return FetchResult(
                            ok=False,
                            url=candidate,
                            requested_url=requested,
                            status=response.status_code,
                            history=history,
                            error=f"too_large: read past cap {self.max_bytes}",
                            elapsed_ms=int((time.time() - started) * 1000),
                        )
                    chunks.append(chunk)
            except Exception as error:
                response.close()
                if attempt >= self.retries:
                    return FetchResult(
                        ok=False,
                        url=candidate,
                        requested_url=requested,
                        status=response.status_code,
                        history=history,
                        error=f"read failed: {type(error).__name__}: {error}"[:300],
                        elapsed_ms=int((time.time() - started) * 1000),
                    )
                continue

            body = b"".join(chunks)
            ok = response.status_code == 200 and bool(body)
            return FetchResult(
                ok=ok,
                url=candidate,
                requested_url=requested,
                via_mirror=route,
                status=response.status_code,
                content_type=head["Content-Type"].split(";")[0].strip(),
                content_length=len(body),
                history=history,
                body=body,
                error="" if ok else f"http_status={response.status_code}",
                elapsed_ms=int((time.time() - started) * 1000),
            )
        return FetchResult(ok=False, url=candidate, requested_url=requested, error="exhausted retries")
