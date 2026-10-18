"""Response headers and body decide catalog type independently of filename."""

import io
import pathlib
import shutil
import subprocess
import sys
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import check_link_catalog
import update_depot


class Response:
    def __init__(self, body, content_type):
        self.body = io.BytesIO(body)
        self.headers = {"Content-Type": content_type}
        self.status = 200

    def __enter__(self): return self
    def __exit__(self, *_): self.body.close()
    def read(self, n): return self.body.read(n)


class CheckLinkCatalogTest(unittest.TestCase):
    def test_json_body_under_txt_url_is_vod(self):
        opener = mock.Mock()
        opener.open.return_value = Response(b'{"sites":[{"key":"a"}]}', "text/plain; charset=utf-8")
        with mock.patch.object(check_link_catalog.urllib.request, "build_opener", return_value=opener):
            result = check_link_catalog.probe("https://example.org/config.txt")
        self.assertEqual(("reachable", "vod", "text/plain"),
                         (result["status"], result["category"], result["contentType"]))

    def test_playlist_body_under_json_url_is_live(self):
        opener = mock.Mock()
        opener.open.return_value = Response(b'#EXTM3U\n#EXTINF:-1,One\nhttps://example.org/a.m3u8\n',
                                            "application/vnd.apple.mpegurl")
        with mock.patch.object(check_link_catalog.urllib.request, "build_opener", return_value=opener):
            result = check_link_catalog.probe("https://example.org/live.json")
        self.assertEqual(("reachable", "live"), (result["status"], result["category"]))

    def test_redirect_is_uncertain_without_following(self):
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError(
            "https://example.org/a.json", 302, "Found", {"Content-Type": "text/html"}, None)
        with mock.patch.object(check_link_catalog.urllib.request, "build_opener", return_value=opener):
            result = check_link_catalog.probe("https://example.org/a.json")
        self.assertEqual(("uncertain", 302), (result["status"], result["statusCode"]))
        self.assertEqual(1, opener.open.call_count)

    def test_partial_json_is_uncertain(self):
        opener = mock.Mock()
        response = Response(b'{"sites":[', "application/json")
        response.status = 206
        response.headers["Content-Range"] = "bytes 0-9/300000"
        opener.open.return_value = response
        with mock.patch.object(check_link_catalog.urllib.request, "build_opener", return_value=opener):
            result = check_link_catalog.probe("https://example.org/config.json")
        self.assertEqual("uncertain", result["status"])
        self.assertIn("partial response", result["reason"])

    def test_tvbox_txt_playlist_with_plain_text_header_is_live(self):
        opener = mock.Mock()
        opener.open.return_value = Response(
            "体育,#genre#\nCCTV1,http://example.org/one\nCCTV2,http://example.org/two\n".encode(),
            "text/plain",
        )
        with mock.patch.object(check_link_catalog.urllib.request, "build_opener", return_value=opener):
            result = check_link_catalog.probe("https://example.org/iptv.txt")
        self.assertEqual(("reachable", "live"), (result["status"], result["category"]))

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL is needed to exercise the TVBox envelope")
    def test_supported_2423_config_is_vod(self):
        key_text = "test"
        iv_text = "1234567890123"
        key = key_text.ljust(16, "0").encode()
        iv = iv_text.ljust(16, "0").encode()
        encrypted = subprocess.run(
            ["openssl", "enc", "-aes-128-cbc", "-e", "-K", key.hex(), "-iv", iv.hex()],
            input=b'{"sites":[{"key":"a"}]}', capture_output=True, check=True,
        ).stdout
        payload = ("2423" + key_text.encode().hex() + "2324" + encrypted.hex() +
                   iv_text.encode().hex()).encode()
        opener = mock.Mock()
        opener.open.return_value = Response(payload, "text/plain")
        with mock.patch.object(check_link_catalog.urllib.request, "build_opener", return_value=opener):
            result = check_link_catalog.probe("https://example.org/encoded.json")
        self.assertEqual(("reachable", "vod"), (result["status"], result["category"]))

    def test_hash_comments_in_tvbox_config(self):
        body = '{\n## group\n"sites":[{"key":"x","api":"https://example.org/#frag"}]\n}'
        self.assertEqual("config", update_depot.classify_text(body)[0])


if __name__ == "__main__":
    unittest.main()
