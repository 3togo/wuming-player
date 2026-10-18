import json
import pathlib
import sys
import tempfile
import threading
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import validate_depot


class ResponseHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        base = f"http://127.0.0.1:{self.server.server_port}"
        responses = {
            "/valid": (200, b'{"sites": []}', "application/json"),
            "/text": (200, b"channel,http://example.test/stream\n", "text/plain"),
            "/config-dead": (
                200,
                b'{"sites":[{"api":"/missing-one"},{"api":"/missing-two"}]}',
                "application/json",
            ),
            "/config-mixed": (
                200,
                b'{"sites":[{"api":"/missing"},{"api":"/valid"}]}',
                "application/json",
            ),
            "/playlist-dead": (
                200,
                f"one,{base}/missing-one\ntwo,{base}/missing-two\n".encode(),
                "text/plain",
            ),
            "/empty": (200, b"", "text/plain"),
            "/html": (200, b"<!doctype html><title>Error</title>", "text/html"),
        }
        status, body, content_type = responses.get(
            self.path, (404, b"missing", "text/plain")
        )
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


class ValidateDepotTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), ResponseHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def test_backs_up_then_removes_empty_failed_and_html_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            asset_root = root / "assets"
            asset_root.mkdir()
            (asset_root / "local.txt").write_text("local content", encoding="utf-8")
            original = {
                "vod": [
                    {"name": "valid", "url": self.base_url + "/valid"},
                    {"name": "empty", "url": self.base_url + "/empty"},
                    {"name": "html", "url": self.base_url + "/html"},
                    {"name": "missing", "url": self.base_url + "/missing"},
                    {"name": "asset", "url": "assets://local.txt"},
                    {"name": "empty URL", "url": ""},
                    {"name": "malformed URL", "url": "http://[invalid"},
                ],
                "live": [{"name": "text", "url": self.base_url + "/text"}],
            }
            depot_path = root / "depot.json"
            original_text = json.dumps(original, ensure_ascii=False, indent=2) + "\n"
            depot_path.write_text(original_text, encoding="utf-8")

            backup, checks = validate_depot.validate_depot(
                depot_path,
                asset_root,
                timeout=2,
                retries=0,
                workers=4,
                deep=False,
            )

            self.assertRegex(backup.name, r"^depot\.json\.\d{8}_\d{6}_\d{6}[+-]\d{4}\.bak$")
            self.assertEqual(original_text, backup.read_text(encoding="utf-8"))
            self.assertEqual(5, sum(not check.valid for check in checks))
            result = json.loads(depot_path.read_text(encoding="utf-8"))
            self.assertEqual(["valid", "asset"], [item["name"] for item in result["vod"]])
            self.assertEqual(["text"], [item["name"] for item in result["live"]])

    def test_dry_run_still_backs_up_without_changing_depot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            depot_path = root / "depot.json"
            original_text = '{"vod":[{"name":"bad","url":""}],"live":[]}\n'
            depot_path.write_text(original_text, encoding="utf-8")

            backup, checks = validate_depot.validate_depot(
                depot_path,
                root,
                timeout=1,
                retries=0,
                workers=1,
                dry_run=True,
                deep=False,
            )

            self.assertTrue(backup.is_file())
            self.assertFalse(checks[0].valid)
            self.assertEqual(original_text, depot_path.read_text(encoding="utf-8"))

    def test_malformed_json_is_backed_up_before_parse_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            depot_path = root / "depot.json"
            depot_path.write_text("not json\n", encoding="utf-8")

            with self.assertRaises(json.JSONDecodeError):
                validate_depot.validate_depot(depot_path, root)

            backups = list(root.glob("depot.json.*.bak"))
            self.assertEqual(1, len(backups))
            self.assertEqual("not json\n", backups[0].read_text(encoding="utf-8"))

    def test_internationalized_url_is_converted_before_request(self):
        captured = []

        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read(self, limit):
                return b'{"sites": []}'

        def open_request(request, **kwargs):
            captured.append(request.full_url)
            return Response()

        with mock.patch("urllib.request.urlopen", side_effect=open_request):
            valid, _ = validate_depot.check_http(
                "https://我不是.摸鱼儿.top/配置", timeout=1, retries=0
            )

        self.assertTrue(valid)
        self.assertEqual(
            "https://xn--ihqu10cn4c.xn--v4q818bf34b.top/%E9%85%8D%E7%BD%AE",
            captured[0],
        )

    def test_deep_check_rejects_catalog_when_all_nested_links_fail(self):
        entry = {"name": "dead", "url": self.base_url + "/config-dead"}

        check = validate_depot.check_entry(
            "vod", 0, entry, pathlib.Path("."), 2, 0, True, 12
        )

        self.assertFalse(check.valid)
        self.assertIn("0/2 nested URLs reachable", check.reason)

    def test_deep_check_accepts_catalog_when_a_nested_link_loads(self):
        entry = {"name": "mixed", "url": self.base_url + "/config-mixed"}

        check = validate_depot.check_entry(
            "vod", 0, entry, pathlib.Path("."), 2, 0, True, 12
        )

        self.assertTrue(check.valid)
        self.assertIn("nested reachable", check.reason)

    def test_deep_check_parses_plain_text_playlist_links(self):
        entry = {"name": "dead live", "url": self.base_url + "/playlist-dead"}

        check = validate_depot.check_entry(
            "live", 0, entry, pathlib.Path("."), 2, 0, True, 12
        )

        self.assertFalse(check.valid)
        self.assertIn("0/2 nested URLs reachable", check.reason)


if __name__ == "__main__":
    unittest.main()
