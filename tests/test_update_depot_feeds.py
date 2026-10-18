"""Tests for the depot intake layers added on top of the README scrape.

Covers the three rules that decide whether harvesting helps or hurts: a candidate must be a
shape the app can act on, an existing entry must never die from one failed fetch, and a feed
may only touch the entries it owns.
"""

import json
import pathlib
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import validate_depot
import update_depot


CONFIG_BODY = json.dumps({"sites": [{"key": "k", "name": "n"}], "spider": "./x.jar"})
LIVES_BODY = json.dumps({"lives": [{"name": "n", "url": "./lives.txt"}]})
CATALOG = {
    "version": 1,
    "resources": [
        {"id": "good", "name": "可用源", "category": "tvbox_config", "url": "BASE/config"},
        # Rejected by the site's own daily check: ordering signal only, never an admission.
        {"id": "gone", "name": "失效源", "category": "tvbox_config", "url": "BASE/html"},
        # Not a player configuration at all.
        {"id": "site", "name": "在线影视站", "category": "online_video", "url": "BASE/config"},
        {"id": "dup", "name": "重复源", "category": "tvbox_config", "url": "BASE/config"},
        {"id": "proxy", "name": "镜像链接", "category": "tvbox_config",
         "url": "https://ghproxy.net/https://example.test/tv.json"},
    ],
}
AVAILABILITY = {
    "results": [
        {"resource_id": "gone", "status": "unreachable"},
        {"resource_id": "good", "status": "reachable"},
    ]
}


def _playlist(urls):
    lines = ['#EXTM3U']
    for index, url in enumerate(urls, 1):
        lines.append(f'#EXTINF:-1 tvg-id="",频道{index}')
        lines.append(url)
    if not urls:
        lines.append('这是一份只有频道名称、没有任何可测链接的列表，用来验证采样不会凭空拒绝。')
    return '\n'.join(lines) + '\n'


class ResponseHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        base = f"http://127.0.0.1:{self.server.server_port}"
        bodies = {
            "/config": (200, CONFIG_BODY.encode(), "application/json"),
            "/lives": (200, LIVES_BODY.encode(), "application/json"),
            "/banner": (200, ("///以下内容为互联网收集 " + CONFIG_BODY).encode(), "text/plain"),
            "/banner2": (200, ("///以下全部出自网络 [所有内容仅供学习使用]\n" + CONFIG_BODY
                               + "\n如有冒犯请联系删除").encode(), "text/plain"),
            "/warehouse": (200, json.dumps({
                "urls": [{"name": "first", "url": "/lives"}, {"name": "second", "url": "/config"}],
            }).encode(), "application/json"),
            "/warehouse-dead": (200, json.dumps({
                "urls": [{"name": "first", "url": "/missing"}],
            }).encode(), "application/json"),
            "/comments": (200, ('{\n // 免责声明\n  "sites": [{"key": "k", "api": "' + base +
                                '/playlist"}],\n  /* 结束 */\n  "spider": "./a.jar"\n}'
                                ).encode(), "application/json"),
            "/playlist": (200, b"#EXTM3U\n#EXTINF:-1,t\thttp://127.0.0.1:1/s\n", "text/plain"),
            "/html": (200, b"<!doctype html><title>login</title>", "text/html"),
            "/error": (200, json.dumps({"msg": "token expired"}).encode(), "application/json"),
            "/tiny": (200, b"nope", "text/plain"),
            "/binary": (200, b"\xff\xd8\xff\xe0\x00\x10" + b"\x00" * 200, "application/octet-stream"),
            "/scalar": (200, b'"' + b'x' * 100 + b'"', "application/json"),
            "/object": (200, json.dumps({"code": 200, "data": {}}).encode(), "application/json"),
            "/catalog": (200, json.dumps(CATALOG).replace("BASE", base).encode(), "application/json"),
            "/availability": (200, json.dumps(AVAILABILITY).encode(), "application/json"),
            # A playlist with names but no probeable stream URLs, for the channel sampler.
            "/pl-names-only": (200, _playlist([]).encode(), "audio/x-mpegurl"),
        }
        status, body, content_type = bodies.get(self.path, (404, b"missing", "text/plain"))
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


class DepotFeedTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), ResponseHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        # Keep the cache out of the repository and never let a cached verdict hide a check.
        cache = tempfile.TemporaryDirectory()
        self.addCleanup(cache.cleanup)
        self.cache_dir = pathlib.Path(cache.name) / "readme"
        self.tmp = mock.patch.object(update_depot, "CACHE_DIR", str(self.cache_dir))
        self.tmp.start()
        self.addCleanup(self.tmp.stop)

    def path(self, name):
        return self.base + name

    # -- content-shape gate ------------------------------------------------

    def test_accepts_shapes_the_app_can_act_on(self):
        for route in ("/config", "/lives", "/banner", "/banner2", "/comments", "/playlist"):
            ok, reason = update_depot.gate_url(self.path(route))
            self.assertTrue(ok, f"{route}: {reason}")

    def test_rejects_bodies_that_are_not_playable_configs(self):
        cases = {
            "/html": "HTML",
            "/error": "msg",
            "/tiny": "small",
            "/binary": "binary",
            "/scalar": "scalar",
            "/object": "without TVBox",
            "/missing": "HTTP 404",
        }
        for route, expected in cases.items():
            ok, reason = update_depot.gate_url(self.path(route))
            self.assertFalse(ok, f"{route} unexpectedly passed: {reason}")
            self.assertIn(expected, reason)

    def test_warehouse_index_is_followed_to_its_first_entry(self):
        # BaseConfig.parseDepot keeps configs.get(0), so only the first inner URL matters.
        ok, reason = update_depot.gate_url(self.path("/warehouse"))
        self.assertTrue(ok, reason)
        self.assertIn("first warehouse", reason)

        ok, reason = update_depot.gate_url(self.path("/warehouse-dead"))
        self.assertFalse(ok, reason)
        self.assertIn("HTTP 404", reason)

    def test_first_json_value_tolerates_banner_and_trailing_prose(self):
        # Real curated configs put "[所有内容]" style brackets in the banner and text after the
        # payload; Gson reads them leniently, so the gate has to as well.
        self.assertEqual({"a": 1}, update_depot.first_json_value('///免责 [声明] {"a":1}'))
        self.assertEqual({"a": 1}, update_depot.first_json_value('\ufeff\t{"a":1}\n以下已结束'))
        self.assertEqual({"a": 1}, update_depot.first_json_value('{"a":1} [1,2]'))
        self.assertIsNone(update_depot.first_json_value('no json here'))
        # JSON-with-comments is what TVBox actually ships; "http://" inside a value survives.
        self.assertEqual({"u": "http://x.test/a", "sites": []},
                         update_depot.first_json_value(
                             '{ // 说明\n "u": "http://x.test/a", /* 行内 */ "sites": []\n}'))
        # A malformed outer document must not be "rescued" by a nested fragment.
        self.assertIsNone(update_depot.first_json_value('{"sites": [1,2], bad: }'))
        self.assertEqual((None, 'JSON scalar, not a config'),
                         update_depot.classify_text('"just a string"'))

    # -- inner-stream sampling ---------------------------------------------

    def sampled(self, verdicts):
        """Run sample_streams over a synthetic playlist, with each host's verdict fixed.

        Probes are stubbed: which channels a real list points at must not decide whether the
        rule itself works, and the loopback test server cannot stand in for many hosts.
        """
        body = _playlist([f'http://{host}/x.m3u8' for host in verdicts]).encode()

        def fake_fetch(url, *args, **kwargs):
            verdict = verdicts[url.split('//', 1)[1].split('/', 1)[0]]
            if verdict is True:
                return validate_depot.Fetch(True, 'loaded 4000 byte(s)', b'#EXTM3U\n')
            if verdict == 'gone':
                return validate_depot.Fetch(False, 'HTTP 404')
            return validate_depot.Fetch(False, 'connection failed: [Errno 111] Connection refused')

        with mock.patch.object(update_depot.validate_depot, 'fetch_http', side_effect=fake_fetch):
            return update_depot.sample_streams(body, 'http://list.test/x.m3u')

    def test_healthy_channels_pass_the_sample(self):
        ok, detail = self.sampled({'a.test': True, 'b.test': True, 'c.test': True})
        self.assertTrue(ok, detail)
        self.assertIn('3/3 hosts serve content', detail)

    def test_dead_majority_inside_a_valid_playlist_is_rejected(self):
        # The endpoint answers 200 and parses as M3U; three of its five testable hosts are gone.
        ok, detail = self.sampled({'a.test': 'gone', 'b.test': 'gone', 'c.test': 'gone',
                                   'd.test': True, 'e.test': True})
        self.assertFalse(ok, detail)
        self.assertIn('2/5 hosts serve content', detail)

    def test_two_dead_hosts_are_not_enough_to_refuse_a_source(self):
        # Calibrated against ChinaIPTV, which we ship: two operator-specific 404s out of a
        # ten-host sample is a routing difference, not a dead list.
        ok, detail = self.sampled({'a.test': 'gone', 'b.test': 'gone',
                                   'c.test': True, 'd.test': True})
        self.assertTrue(ok, detail)
        self.assertIn('2/4 hosts serve content', detail)

    def test_untestable_channels_never_reject_a_source(self):
        # Refused/timeout/no-IPv6-route are properties of this probe box, not of the list: a
        # region-locked HK source reads exactly like a dead one from here.
        ok, detail = self.sampled({'a.test': True, 'b.test': 'untestable', 'c.test': 'untestable',
                                   'd.test': 'untestable'})
        self.assertTrue(ok, detail)
        self.assertIn('too few testable hosts', detail)

    def test_playlist_without_probeable_urls_is_not_invented_against(self):
        ok, reason = update_depot.gate_url(self.path('/pl-names-only'))
        self.assertTrue(ok, reason)
        self.assertIn('no stream URLs inside', reason)

    def test_configs_are_not_channel_sampled(self):
        # A config's own sites are checked by validate_depot separately; the shape is the gate.
        ok, reason = update_depot.gate_url(self.path('/config'))
        self.assertTrue(ok, reason)
        self.assertNotIn('streams', reason)

    def test_evidence_split_separates_gone_from_untestable(self):
        cases = {
            'connection failed: [Errno -2] Name or service not known': 'gone',
            'connection failed: [Errno -3] Temporary failure in name resolution': 'gone',
            'HTTP 404': 'gone',
            'HTTP 410': 'gone',
            'empty response': 'gone',
            'HTML response': 'gone',
            'HTTP 403': 'untestable',
            'HTTP 429': 'untestable',
            'HTTP 503': 'untestable',
            'timeout': 'untestable',
            'connection failed: [Errno 111] Connection refused': 'untestable',
            'connection failed: [Errno 101] Network is unreachable': 'untestable',
        }
        for reason, expected in cases.items():
            self.assertEqual(expected, update_depot.stream_evidence(reason), reason)

    def test_sample_counts_hosts_not_channels(self):
        # 44 channels behind one domain are one vote, and two runs must reach the same verdict.
        urls = [f'http://a.test/{index}' for index in range(44)] + ['http://b.test/1', 'http://c.test/1']
        picked = update_depot.host_diverse_sample(urls, 2)
        self.assertEqual(['http://a.test/0', 'http://b.test/1'], picked)
        self.assertEqual(picked, update_depot.host_diverse_sample(urls, 2))

    # -- catalog adapter ---------------------------------------------------

    def feed(self, **overrides):
        entry = {
            "id": "testfeed",
            "label": "测试_feed",
            "catalog": self.path("/catalog"),
            "availability": self.path("/availability"),
            "categories": {"tvbox_config": "vod"},
        }
        entry.update(overrides)
        return entry

    def test_catalog_maps_only_config_categories_and_skips_non_endpoints(self):
        candidates = update_depot.feed_candidates(self.feed(), refresh=True)
        # Extraction does not vet: /html is a legitimate candidate the gate later rejects.
        # What it does drop is a non-config category, a duplicate URL, and a proxy-embedded link.
        self.assertEqual([self.path("/config"), self.path("/html")],
                         [candidate["url"] for candidate in candidates])
        self.assertEqual("测试_feed · 可用源", candidates[0]["name"])
        self.assertEqual("vod", candidates[0]["section"])
        self.assertEqual("testfeed", candidates[0]["feed"])

    # -- provenance merge, budget, expiry ----------------------------------

    def depot(self):
        return {
            "vod": [
                {"url": self.path("/config"), "name": "手工精选"},
                {"url": self.path("/missing"), "name": "本 feed 的失效条目", "feed": "testfeed"},
            ],
            "live": [{"url": self.path("/playlist"), "name": "直播手工精选"}],
        }

    def test_feed_never_duplicates_or_relabels_entries_it_does_not_own(self):
        depot, state = self.depot(), {}
        summary = update_depot.apply_feed(depot, self.feed(), state, refresh=True)
        # The catalog's good URL is already in the depot untagged: it stays hand-picked.
        self.assertEqual([], summary["added"])
        self.assertEqual([("测试_feed · 失效源", "HTML response")],
                         [(name, reason.split(';')[0]) for name, reason in summary["rejected"]])
        owned = [entry for entry in depot["vod"] if entry.get("feed") == "testfeed"]
        self.assertEqual([self.path("/missing")], [entry["url"] for entry in owned])
        self.assertEqual(1, state[self.path("/missing")])
        self.assertNotIn("failCount", owned[0])
        self.assertEqual("手工精选", depot["vod"][0]["name"])
        self.assertNotIn("feed", depot["vod"][0])

    def test_feed_admits_a_passing_candidate_with_provenance(self):
        depot, state = {"vod": [{"url": self.path("/error"), "name": "手工精选"}], "live": []}, {}
        summary = update_depot.apply_feed(depot, self.feed(), state, refresh=True)
        self.assertEqual([("vod", "测试_feed · 可用源")],
                         [(section, name) for section, name, _ in summary["added"]])
        added = [entry for entry in depot["vod"] if entry.get("feed") == "testfeed"]
        self.assertEqual(1, len(added))
        self.assertEqual({"url", "name", "feed"}, set(added[0]))
        self.assertEqual(0, state[added[0]["url"]])

    def test_feed_budget_limits_how_many_entries_one_source_may_add(self):
        depot = {"vod": [], "live": []}
        summary = update_depot.apply_feed(depot, self.feed(budget=0), {}, refresh=True)
        self.assertEqual([], summary["added"])
        self.assertIn(("测试_feed · 可用源", "feed budget reached"), summary["rejected"])
        self.assertEqual([], depot["vod"])

    def test_untagged_entries_survive_one_failed_check_and_die_after_three(self):
        entry = {"url": self.path("/missing"), "name": "暂时连不上"}
        entries, state = [entry], {}
        for expected in (1, 2):
            kept = update_depot.revalidate_existing(entries, state)
            self.assertEqual([entry], kept)
            self.assertEqual(expected, state[entry["url"]])
            self.assertNotIn("failCount", kept[0])
        self.assertEqual([], update_depot.revalidate_existing(entries, state))
        # A dropped source loses its history; re-adding it starts from zero.
        self.assertEqual({}, state)

    def test_success_after_failures_clears_the_counter(self):
        entry = {"url": self.path("/missing"), "name": "恢复了"}
        state = {entry["url"]: 2}
        with mock.patch.object(update_depot, "validate_url", return_value=True):
            kept = update_depot.revalidate_existing([entry], state)
        self.assertEqual([entry], kept)
        self.assertEqual(0, state[entry["url"]])

    def test_idn_hosts_are_recorded_as_no_check_instead_of_fabricating_a_pass(self):
        entry = {"url": "http://www.饭太硬.net/tv", "name": "打不开的中文域名"}
        state = {entry["url"]: 2}
        with mock.patch.object(update_depot, "validate_url") as probe:
            kept = update_depot.revalidate_existing([entry], state)
        self.assertEqual([entry], kept)
        self.assertTrue(entry["noCheck"])
        self.assertNotIn("failCount", entry)
        # noCheck entries carry no history: the app never probes them either.
        self.assertEqual({}, state)
        probe.assert_not_called()

    def test_bundled_asset_entries_are_verified_locally_not_pruned(self):
        # An "assets://" source ships inside the APK. Fetching it over HTTP has no transport,
        # so a naive probe fails every run and would prune a built-in source after three.
        root = self.cache_dir.parent
        (root / "assets/live").mkdir(parents=True)
        (root / "assets/live/x.m3u8").write_bytes(b"#EXTM3U\n#EXTINF:-1,t\nhttp://127.0.0.1:1/s\n")
        entry = {"url": "assets://live/x.m3u8", "name": "内置直播"}
        probed = []

        def fake_fetch(url, *args, **kwargs):
            probed.append(url)
            return validate_depot.Fetch(True, 'loaded 4000 byte(s)', b'#EXTM3U\n')

        with mock.patch.object(update_depot, "ASSET_ROOT", root / "assets"), \
                mock.patch.object(update_depot, "VALIDATION_CACHE_DIR", str(root / "validation")), \
                mock.patch.object(update_depot.validate_depot, "fetch_http", side_effect=fake_fetch):
            ok, reason = update_depot.gate_url(entry["url"])
            state = {entry["url"]: 2}
            kept = update_depot.revalidate_existing([entry], state)
        self.assertTrue(ok, reason)
        self.assertEqual([entry], kept)
        self.assertEqual(0, state[entry["url"]])
        # The bundled file itself is read from disk; only the channels it lists get fetched.
        self.assertNotIn('assets://', probed)

    def test_counters_live_in_the_sidecar_not_the_shipped_asset(self):
        # depot.json seeds a fresh install; an entry at failCount=2 would let the device's
        # first failed probe delete a source the app has never actually checked.
        depot = {"vod": [{"url": self.path("/config"), "name": "残留计数", "failCount": 2}],
                 "live": []}
        with mock.patch.object(update_depot, "STATE_FILE", self.state_file()):
            state = update_depot.load_state(depot)
        self.assertEqual({self.path("/config"): 2}, state)
        self.assertNotIn("failCount", depot["vod"][0])

    def test_sidecar_roundtrip(self):
        with mock.patch.object(update_depot, "STATE_FILE", self.state_file()):
            update_depot.save_state({self.path("/config"): 1})
            self.assertEqual({self.path("/config"): 1}, update_depot.load_state())

    def state_file(self):
        return str(self.cache_dir.parent / "depot_state.json")

    def test_prune_threshold_mirrors_the_app(self):
        # DepotPrune.PRUNE_AFTER_FAILS is the single policy; drift here would make the shipped
        # asset and the device copy disagree about when a source is gone.
        self.assertEqual(3, update_depot.PRUNE_AFTER_FAILS)
        self.assertTrue(update_depot.keep_entry(2))
        self.assertFalse(update_depot.keep_entry(3))
        self.assertEqual(0, update_depot.next_fail_count(2, True))
        self.assertEqual(3, update_depot.next_fail_count(2, False))


if __name__ == "__main__":
    unittest.main()
