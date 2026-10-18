"""Offline link manager edits survive catalog rebuilds without probing the network."""

import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import build_link_catalog
import link_manager


class LinkManagerTest(unittest.TestCase):
    def test_overrides_and_managed_links_rebuild_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            depot = root / "depot.json"
            overrides = root / "overrides.json"
            managed = root / "managed.json"
            depot.write_text('{"vod": [], "live": []}', encoding="utf-8")
            token_url = "https://example.org/private.json?access_token=secret"
            excluded_url = "https://example.org/old.json"
            link_manager.add_managed(token_url, "Private", managed)
            report = {"version": 1, "checkedAt": "2026-10-02T00:00:00Z", "results": [
                {"name": "Old", "url": excluded_url, "status": "reachable", "category": "vod",
                 "reason": "config: sites"}], "counts": {}}
            link_manager.merge_managed(report, link_manager.load_managed(managed))
            link_manager.set_verdict(report, token_url, {"status": "reachable", "category": "vod",
                                                        "reason": "config: sites", "statusCode": 200,
                                                        "contentType": "text/plain"})
            link_manager.set_override(token_url, name="Spring", path=overrides)
            link_manager.set_override(excluded_url, enabled=False, path=overrides)
            catalog = build_link_catalog.build_from_report(depot, report, overrides)
            entries = [entry for group in catalog["groups"] for entry in group["entries"]]
            self.assertEqual([(token_url, "Spring", True)],
                             [(entry["url"], entry["name"], entry["protected"]) for entry in entries])
            self.assertNotIn("secret", link_manager.display_url(token_url))
            self.assertNotIn(token_url, json.dumps(link_manager.load_overrides(overrides)))

    def test_managed_url_validation_and_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "managed.json"
            with self.assertRaises(ValueError):
                link_manager.add_managed("file:///tmp/test.json", "Local", path)
            link_manager.add_managed("https://example.org/a.json", "A", path)
            with self.assertRaises(ValueError):
                link_manager.add_managed("https://example.org/a.json", "A", path)
            self.assertTrue(link_manager.replace_managed("https://example.org/a.json",
                                                         "https://example.org/b.json", path))
            self.assertEqual("https://example.org/b.json", link_manager.load_managed(path)["links"][0]["url"])
            self.assertTrue(link_manager.remove_managed("https://example.org/b.json", path))
            self.assertEqual([], link_manager.load_managed(path)["links"])


if __name__ == "__main__":
    unittest.main()
