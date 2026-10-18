"""The shipped offline catalog marks credential-bearing links for redacted browsing."""

import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import build_link_catalog


class BuildLinkCatalogTest(unittest.TestCase):
    def test_groups_unverified_links_with_protected_token_and_no_curated_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "links.txt"
            depot = root / "depot.json"
            report = root / "health.json"
            source.write_text("""curated: https://example.org/old.json
private: https://example.org/config.json?token=secret
new: https://example.org/new.json
mirror: https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/x.json
live: https://example.org/live.m3u
""", encoding="utf-8")
            depot.write_text(json.dumps({"vod": [{"url": "https://example.org/old.json"}], "live": []}),
                             encoding="utf-8")
            report.write_text(json.dumps({"version": 1, "checkedAt": "2026-10-02T00:00:00Z", "results": [
                {"url": url, "status": "reachable", "category": kind,
                 "reason": "depot: warehouse index" if url.endswith("new.json") else "config: sites"}
                for url, kind in (
                    ("https://example.org/old.json", "vod"),
                    ("https://example.org/config.json?token=secret", "vod"),
                    ("https://example.org/new.json", "vod"),
                    ("https://raw.githubusercontent.com/a/b/main/x.json", "vod"),
                    ("https://example.org/live.m3u", "live"),
                )]}), encoding="utf-8")
            catalog = build_link_catalog.build(source, depot, report)
            self.assertEqual(1, catalog["version"])
            items = [entry for group in catalog["groups"] for entry in group["entries"]]
            self.assertEqual({"https://example.org/config.json?token=secret",
                              "https://example.org/new.json",
                              "https://raw.githubusercontent.com/a/b/main/x.json",
                              "https://example.org/live.m3u"}, {entry["url"] for entry in items})
            self.assertEqual({"vod", "live"}, {group["kind"] for group in catalog["groups"]})
            self.assertTrue(next(item for item in items if item["url"].endswith("new.json"))["warehouse"])
            self.assertTrue(next(item for item in items if item["url"].endswith("token=secret"))["protected"])


if __name__ == "__main__":
    unittest.main()
