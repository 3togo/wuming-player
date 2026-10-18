"""Offline checks for the reviewed plain-text depot merge."""

import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import import_links


class ImportLinksTest(unittest.TestCase):
    def test_normalizes_mirrors_and_deduplicates(self):
        body = """南风：https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/x.json
备用：https://gh-proxy.com/raw.githubusercontent.com/a/b/main/x.json
原址：https://raw.githubusercontent.com/a/b/main/x.json
直播：https://example.org/live.m3u
"""
        items, counts = import_links.candidates(body)
        self.assertEqual(2, len(items))
        self.assertEqual("https://raw.githubusercontent.com/a/b/main/x.json", items[0]["url"])
        self.assertEqual("南风", items[0]["name"])
        self.assertEqual("live", items[1]["section"])
        self.assertEqual(2, counts["duplicates"])

    def test_rejects_credentials_private_hosts_and_other_player_formats(self):
        body = """http://user:pass@example.org/config.json
http://127.0.0.1:8080/config.json
https://cat.example.org/index.js.md5
https://example.org/image.webp
https://example.org/tv.json
"""
        items, _ = import_links.candidates(body)
        self.assertEqual(["https://example.org/tv.json"], [item["url"] for item in items])

    def test_content_shape_decides_final_section(self):
        self.assertEqual("vod", import_links.admitted_section("config: keys: sites"))
        self.assertEqual("vod", import_links.admitted_section("warehouse index with 4 config(s)"))
        self.assertEqual("live", import_links.admitted_section("playlist: M3U playlist; streams 3/3"))
        self.assertIsNone(import_links.admitted_section("text: long page; no stream URLs inside"))

    def test_merge_preserves_existing_and_requires_apply(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "links.txt"
            depot = root / "depot.json"
            source.write_text("one: https://example.org/a.json\ntwo: https://example.org/b.json\n",
                              encoding="utf-8")
            original = {"vod": [{"url": "https://example.org/a.json", "name": "kept", "count": 7}],
                        "live": []}
            depot.write_text(json.dumps(original), encoding="utf-8")
            with mock.patch.object(import_links.update_depot, "gate_url", return_value=(True, "warehouse index with 2 config(s)")) as gate:
                import_links.merge(source, depot, 10, 10, False)
                self.assertEqual(original, json.loads(depot.read_text()))
                import_links.merge(source, depot, 10, 10, True)
            self.assertEqual(2, gate.call_count)
            result = json.loads(depot.read_text())
            self.assertEqual(original["vod"][0], result["vod"][0])
            self.assertEqual("https://example.org/b.json", result["vod"][1]["url"])
            self.assertTrue(result["vod"][1]["warehouse"])

    def test_merge_keeps_edits_made_while_validation_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "links.txt"
            depot = root / "depot.json"
            source.write_text("new: https://example.org/new.json\n", encoding="utf-8")
            depot.write_text(json.dumps({"vod": [], "live": []}), encoding="utf-8")

            def gate(_):
                depot.write_text(json.dumps({"vod": [{"url": "https://example.org/manual.json",
                                                       "name": "manual", "count": 4}], "live": []}),
                                 encoding="utf-8")
                return True, "config: sites"

            with mock.patch.object(import_links.update_depot, "gate_url", side_effect=gate):
                import_links.merge(source, depot, 1, 1, True)
            urls = [entry["url"] for entry in json.loads(depot.read_text())["vod"]]
            self.assertEqual(["https://example.org/manual.json", "https://example.org/new.json"], urls)


if __name__ == "__main__":
    unittest.main()
