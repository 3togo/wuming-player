#!/usr/bin/env python3
"""Fetch the reviewed current fm.jar into ignored local artifacts, preserving its pinned identity."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]


def prepare(root=ROOT, opener=urlopen):
    manifest = json.loads((root / 'desktop/src/test/resources/guarded/fm-current/manifest.json').read_text())
    target = root / 'artifacts/desktop-provider-fixtures/fm.jar'
    expected = manifest['jarSha256']
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        temp = None
        try:
            with opener(manifest['source'], timeout=15) as response:
                if response.status != 200:
                    raise HTTPError(manifest['source'], response.status, 'Expected HTTP 200', None, None)
                with tempfile.NamedTemporaryFile(dir=target.parent, prefix='.fm-', delete=False) as stream:
                    temp = Path(stream.name)
                    digest = hashlib.sha256()
                    size = 0
                    deadline = time.monotonic() + 45
                    while True:
                        if time.monotonic() > deadline:
                            raise TimeoutError('Fixture download exceeded its deadline')
                        chunk = response.read(65536)
                        if not chunk:
                            break
                        size += len(chunk)
                        if size > 16 * 1024 * 1024:
                            raise ValueError('Fixture download exceeds size limit')
                        digest.update(chunk)
                        stream.write(chunk)
                if digest.hexdigest() != expected:
                    raise ValueError('fm.jar SHA-256 mismatch; retain the pin and review the new upstream artifact')
                os.replace(temp, target)
                return target
        except (HTTPError, URLError, TimeoutError) as error:
            if isinstance(error, HTTPError):
                error.close()
            if isinstance(error, HTTPError) and error.code not in (408, 429, 500, 502, 503, 504):
                raise
            if attempt == 2:
                raise
        finally:
            if temp is not None:
                temp.unlink(missing_ok=True)
    raise RuntimeError('Fixture download did not complete')


if __name__ == '__main__':
    print(prepare())
