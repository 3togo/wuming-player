import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_fm_fixture', ROOT / 'scripts/desktop/prepare-fm-fixture.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Response(io.BytesIO):
    def __init__(self, body, status=200):
        super().__init__(body)
        self.status = status


class FmFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        manifest = self.root / 'desktop/src/test/resources/guarded/fm-current/manifest.json'
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps({'source': 'http://fixture.invalid/fm.jar',
                                        'jarSha256': hashlib.sha256(b'reviewed fixture').hexdigest()}))
        self.target = self.root / 'artifacts/desktop-provider-fixtures/fm.jar'

    def test_valid_download_is_published_and_reused(self):
        self.assertEqual(self.target, module.prepare(self.root, lambda *a, **k: Response(b'reviewed fixture')))
        self.assertEqual(b'reviewed fixture', self.target.read_bytes())
        module.prepare(self.root, lambda *a, **k: self.fail('A verified fixture should be reused'))

    def test_checksum_failure_preserves_existing_artifact(self):
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(b'previous artifact')
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            module.prepare(self.root, lambda *a, **k: Response(b'changed upstream jar'))
        self.assertEqual(b'previous artifact', self.target.read_bytes())
        self.assertEqual([self.target], list(self.target.parent.iterdir()))

    def test_http_error_is_not_misreported_as_checksum_failure(self):
        with self.assertRaises(HTTPError) as caught:
            module.prepare(self.root, lambda *a, **k: Response(b'Not found', 404))
        self.assertEqual(404, caught.exception.code)
        self.assertFalse(self.target.exists())

    def test_transient_http_failure_is_retried_with_the_same_pin(self):
        replies = iter([Response(b'temporary error', 503), Response(b'reviewed fixture')])
        module.prepare(self.root, lambda *a, **k: next(replies))
        self.assertEqual(b'reviewed fixture', self.target.read_bytes())


if __name__ == '__main__':
    unittest.main()
