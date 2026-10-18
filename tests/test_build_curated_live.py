"""Tests for the offline curated-list builder.

The rules that decide whether a generated list is trustworthy: a published name resolves to one
canonical channel (not to whichever alias was declared first), a stream this box cannot test is
kept rather than deleted, human pins survive every probe result, and a rebuild says out loud what
it would lose against the curated asset. All fetches and probes are stubbed — no sockets, no cache.
"""

import pathlib
import sys
import tempfile
import unittest
import urllib.parse
from collections import Counter
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "scripts" / "depot"))
import build_curated_live
import update_depot
import validate_depot


PROFILE = {
    "profile": "test",
    "label": "Test channels",
    "revision": 2,
    "max_urls_per_channel": 4,
    "mirrors_per_host": 1,
    "canonical_channels": {
        "ViuTV": ["viutv", "viu tv", "99台"],
        "ViuTVsix": ["viutvsix", "viutv 6", "96台"],
        "翡翠台": ["翡翠台", "tvb翡翠台", "jade"],
        "Now新聞台": ["now新聞台", "now news"],
    },
    "channel_suffixes": ["1080p", "720p", "4k", "高清"],
    "order": ["翡翠台", "ViuTV", "ViuTVsix", "Now新聞台"],
    "block_keywords": ["Now 14", "test", "電影"],
    "official_channels": [{"name": "翡翠台", "url": "https://official.test/jade.m3u8"}],
    "pinned_channels": [],
    "groups": {"official": "官方", "direct": "直连", "fallback": "备用"},
    "group_order": ["direct", "official", "fallback"],
    "default_group": "fallback",
    "host_groups": {"direct.test": "direct", "proxy.test": "fallback"},
    "reference_asset": "does-not-exist.m3u8",
    "sources": [{"url": "https://list.test/a.m3u8", "scope": "hk"}],
    "epg": "https://epg.test/x.xml",
}

# The curated asset a rebuild is measured against, used only by the compare tests.
REFERENCE_ASSET = """#EXTM3U

#EXTINF:-1 tvg-name="明珠台" group-title="备用",明珠台
https://proxy.test/pearl.m3u8
#EXTINF:-1 tvg-name="翡翠台" group-title="直连",翡翠台
https://kept.test/j.m3u8
"""


def playlist(rows):
    lines = ["#EXTM3U"]
    for name, url in rows:
        lines.append(f'#EXTINF:-1 tvg-name="{name}",{name}')
        lines.append(url)
    return "\n".join(lines) + "\n"


def host_of(url):
    return urllib.parse.urlsplit(url).hostname


class ProfileSource(unittest.TestCase):
    """The shipped profile is data the builder depends on, so a gap in it fails loudly."""

    def test_hk_profile_is_self_consistent(self):
        profile = build_curated_live.load_profile("hk")
        self.assertEqual(set(profile["order"]), set(profile["canonical_channels"]))
        self.assertEqual(set(profile["group_order"]), set(profile["groups"]))
        self.assertIn(profile["default_group"], profile["groups"])
        for channel in profile["official_channels"]:
            self.assertIn(channel["name"], profile["order"])
        for source in profile["sources"]:
            self.assertIn(source["scope"], ("hk", "global"))
        for pin in profile["pinned_channels"]:
            self.assertIn(pin["group"], profile["groups"])
        for host, group in profile["host_groups"].items():
            self.assertIn(group, profile["groups"])

    def test_hk_profile_states_what_we_changed_from_the_curator(self):
        profile = build_curated_live.load_profile("hk")
        provenance = profile["provenance"]
        self.assertTrue(provenance["seeded_blob"])
        self.assertGreater(len(profile["canonical_channels"]), 14,
                           "our table must cover the channels the shipped list carries")
        # The shipped list's reason to exist: Phoenix is what a proxy viewer opens the app for.
        for banned in ("鳳凰", "凤凰", "星河"):
            self.assertNotIn(banned, profile["block_keywords"])

    def test_unknown_profile_names_what_exists(self):
        with self.assertRaises(SystemExit) as raised:
            build_curated_live.load_profile("no-such-region")
        self.assertIn("hk", str(raised.exception))


class NameResolution(unittest.TestCase):
    def test_longest_alias_wins_so_a_sibling_channel_is_not_absorbed(self):
        self.assertEqual(build_curated_live.canonical_of("ViuTVsix 1080P", PROFILE), "ViuTVsix")
        self.assertEqual(build_curated_live.canonical_of("ViuTV 99台", PROFILE), "ViuTV")
        self.assertEqual(build_curated_live.canonical_of("viu tv six", PROFILE), "ViuTVsix")

    def test_spacing_and_decoration_do_not_hide_a_channel(self):
        self.assertEqual(build_curated_live.canonical_of("TVB翡翠台 高清", PROFILE), "翡翠台")
        self.assertEqual(build_curated_live.canonical_of("Now News 4K", PROFILE), "Now新聞台")
        self.assertEqual(build_curated_live.canonical_of("【翡翠台】", PROFILE), "翡翠台")

    def test_an_unknown_name_resolves_to_nothing_rather_than_a_guess(self):
        self.assertIsNone(build_curated_live.canonical_of("澳視澳門", PROFILE))
        self.assertIsNone(build_curated_live.canonical_of("-", PROFILE))

    def test_block_keyword_beats_alias_resolution(self):
        # 'Now 14' shares the Now prefix with Now新聞台 but the curator excluded it.
        self.assertEqual(build_curated_live.blocked_by("Now 14", PROFILE), "Now 14")
        self.assertIsNone(build_curated_live.blocked_by("Now新聞台", PROFILE))

    def test_ascii_block_keywords_need_word_boundaries(self):
        self.assertIsNone(build_curated_live.blocked_by("Jade Latest News", PROFILE))
        self.assertEqual(build_curated_live.blocked_by("翡翠台 test", PROFILE), "test")

    def test_cjk_block_keywords_ignore_spacing(self):
        self.assertEqual(build_curated_live.blocked_by("翡翠 電影", PROFILE), "電影")


class DisplayNames(unittest.TestCase):
    def test_the_canonical_name_replaces_source_decoration(self):
        self.assertEqual(
            build_curated_live.display_name("RTHK TV 31 (港台電視31) (360p) [Geo-blocked]", "港台電視31"),
            "港台電視31 360P")
        self.assertEqual(build_curated_live.display_name("VIU6", "ViuTVsix"), "ViuTVsix")

    def test_a_channel_the_alias_table_does_not_know_keeps_its_own_name(self):
        self.assertEqual(build_curated_live.display_name("Pet Club TV (1080p)", ""),
                         "Pet Club TV 1080P")

    def test_resolution_words_do_not_leak_from_a_neighbouring_word(self):
        self.assertEqual(build_curated_live.display_name("HDH Media 台", ""), "HDH Media 台")

    def test_a_name_that_already_states_its_resolution_is_not_labelled_twice(self):
        self.assertEqual(build_curated_live.display_name("明珠台 1080P", "明珠台"), "明珠台 1080P")


class Parsing(unittest.TestCase):
    def test_m3u_attributes_survive_parsing(self):
        body = ('#EXTM3U\n#EXTINF:-1 tvg-id="Jade.hk" tvg-name="J" tvg-logo="https://l.test/i.png"'
                ' group-title="G",翡翠台 4K\nhttps://s.test/j.m3u8\n')
        channel = build_curated_live.parse_playlist(body)[0]
        self.assertEqual(
            (channel.name, channel.url, channel.tvg_id, channel.tvg_logo, channel.group),
            ("翡翠台 4K", "https://s.test/j.m3u8", "Jade.hk", "https://l.test/i.png", "G"))

    def test_txt_mirrors_expand_to_separate_candidates(self):
        body = "group,#genre#\n翡翠台,https://a.test/j.m3u8#https://b.test/j.m3u8\n"
        channels = build_curated_live.parse_playlist(body)
        self.assertEqual([channel.url for channel in channels],
                         ["https://a.test/j.m3u8", "https://b.test/j.m3u8"])

    def test_non_stream_lines_are_not_channels(self):
        self.assertEqual(build_curated_live.parse_playlist("name,not-a-url\n#EXTINF:-1 broken\n"), [])


class GroupResolution(unittest.TestCase):
    def test_groups_come_from_the_declared_host_table_not_from_previous_output(self):
        declared = build_curated_live.Channel(name="n", url="https://direct.test/a.m3u8")
        self.assertEqual(build_curated_live.resolve_group(declared, PROFILE), "直连")

    def test_an_unknown_host_is_never_labelled_directly_reachable(self):
        fresh = build_curated_live.Channel(name="n", url="https://fresh.test/a.m3u8")
        self.assertEqual(build_curated_live.resolve_group(fresh, PROFILE), "备用")

    def test_an_official_url_is_official_even_its_host_is_unlisted(self):
        official = build_curated_live.Channel(name="翡翠台", url="https://official.test/jade.m3u8")
        self.assertEqual(build_curated_live.resolve_group(official, PROFILE), "官方")


class Selection(unittest.TestCase):
    def build(self, rows, gone=(), blocked=(), **overrides):
        """Run the whole pipeline against stubbed sources and stubbed stream probes."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        reference = pathlib.Path(directory.name, "curated.m3u8")
        reference.write_text(REFERENCE_ASSET, encoding="utf-8")
        keep_unmatched = overrides.pop("keep_unmatched", False)
        profile = dict(PROFILE, reference_asset=str(reference), **overrides)

        def fake_fetch_http(url, *args, **kwargs):
            host = host_of(url)
            if host in gone:
                return validate_depot.Fetch(False, "HTTP 404 Not Found")
            if host in blocked:
                return validate_depot.Fetch(False, "connection failed: timed out")
            return validate_depot.Fetch(True, "loaded 4000 byte(s)")

        with mock.patch.object(update_depot, "fetch_cached", return_value=playlist(rows)), \
                mock.patch.object(validate_depot, "fetch_http", side_effect=fake_fetch_http):
            return build_curated_live.build(
                profile, include_global=False, refresh=False, probing=True, workers=4,
                keep_unmatched=keep_unmatched)

    def kept_urls(self, result):
        return [channel.url for _, channel in result["kept"]]

    def test_a_gone_stream_is_dropped_and_counted(self):
        text, result = self.build(
            [("翡翠台", "https://dead.test/j.m3u8"), ("翡翠台", "https://direct.test/j.m3u8")],
            gone=("dead.test",))
        self.assertNotIn("https://dead.test/j.m3u8", text)
        self.assertEqual(result["per_channel"]["翡翠台"][1]["gone"], 1)
        self.assertEqual(result["totals"]["gone"], 1)

    def test_an_untestable_stream_is_kept_because_only_this_box_could_not_reach_it(self):
        _, result = self.build(
            [("ViuTV", "https://geo.test/v.m3u8"), ("ViuTV", "https://direct.test/v.m3u8")],
            blocked=("geo.test",))
        self.assertIn("https://geo.test/v.m3u8", self.kept_urls(result))
        self.assertEqual(result["verdicts"]["https://geo.test/v.m3u8"][0], "untestable")
        self.assertEqual(result["per_channel"]["ViuTV"][1]["gone"], 0)

    def test_a_second_mirror_on_one_host_is_dropped_by_default(self):
        _, result = self.build([
            ("ViuTV", "https://direct.test/a.m3u8"),
            ("ViuTV", "https://direct.test/b.m3u8"),
            ("ViuTV", "https://proxy.test/c.m3u8"),
        ])
        kept, dropped, rows = result["per_channel"]["ViuTV"]
        self.assertEqual((kept, dropped["extra mirror"]), (2, 1))
        self.assertEqual({channel.group for channel in rows}, {"直连", "备用"})

    def test_mirrors_per_host_lets_a_host_serve_two_streams_of_one_channel(self):
        _, result = self.build([
            ("ViuTV", "https://direct.test/a.m3u8"),
            ("ViuTV", "https://direct.test/b.m3u8"),
            ("ViuTV", "https://direct.test/c.m3u8"),
        ], mirrors_per_host=2)
        kept, dropped, _ = result["per_channel"]["ViuTV"]
        self.assertEqual((kept, dropped["extra mirror"]), (2, 1))

    def test_a_channel_is_capped_across_distinct_hosts(self):
        rows = [("ViuTV", f"https://h{n}.test/v.m3u8") for n in range(6)]
        _, result = self.build(rows)
        kept, dropped, chosen = result["per_channel"]["ViuTV"]
        self.assertEqual((kept, dropped["over cap"]), (4, 2))
        self.assertEqual(len({host_of(channel.url) for channel in chosen}), 4)

    def test_blocked_lines_are_attributed_to_their_keyword(self):
        _, result = self.build([
            ("翡翠台 test", "https://direct.test/j.m3u8"),
            ("Now 14", "https://direct.test/n.m3u8"),
            ("翡翠台", "https://direct.test/live.m3u8"),
        ])
        self.assertEqual(result["stats"]["blocked"]["test"], 1)
        self.assertEqual(result["stats"]["blocked"]["Now 14"], 1)
        self.assertEqual(self.kept_urls(result), ["https://direct.test/live.m3u8",
                                                  "https://official.test/jade.m3u8"])

    def test_unmatched_names_are_reported_and_left_out_unless_asked_for(self):
        _, result = self.build([("港島東频道", "https://direct.test/x.m3u8")])
        self.assertEqual(result["stats"]["unmatched"]["港島東频道"], 1)
        self.assertNotIn("港島東频道", self.kept_urls(result))
        text, _ = self.build([("港島東频道", "https://direct.test/x.m3u8")],
                             keep_unmatched=True)
        self.assertIn("港島東频道", text)

    def test_official_channels_appear_even_when_no_source_lists_them(self):
        _, result = self.build([("ViuTV", "https://direct.test/v.m3u8")])
        self.assertIn("https://official.test/jade.m3u8", self.kept_urls(result))
        official = [channel for _, channel in result["kept"]
                    if channel.url == "https://official.test/jade.m3u8"][0]
        self.assertEqual(official.group, "官方")

    def test_a_declared_official_stream_is_not_pushed_out_by_the_cap(self):
        rows = [("翡翠台", f"https://h{n}.test/j.m3u8") for n in range(5)]
        _, result = self.build(rows, max_urls_per_channel=1)
        self.assertEqual(self.kept_urls(result), ["https://official.test/jade.m3u8",
                                                  "https://h0.test/j.m3u8"])

    def test_a_source_that_relabels_a_declared_stream_under_another_channel_loses(self):
        _, result = self.build([("ViuTV", "https://official.test/jade.m3u8"),
                                ("ViuTV", "https://direct.test/v.m3u8")])
        self.assertEqual(result["per_channel"]["ViuTV"][0], 1)
        self.assertEqual(result["per_channel"]["ViuTV"][1]["mislabelled stream"], 1)
        self.assertNotIn("https://official.test/jade.m3u8",
                         [channel.url for channel in result["per_channel"]["ViuTV"][2]])

    def test_output_is_ordered_by_group_then_by_viewing_order(self):
        _, result = self.build([
            ("Now新聞台", "https://direct.test/n.m3u8"),
            ("ViuTVsix", "https://direct.test/s.m3u8"),
            ("ViuTV", "https://proxy.test/v.m3u8"),
            ("翡翠台", "https://direct.test/j.m3u8"),
        ])
        layout = [(channel.group, canonical) for canonical, channel in result["kept"]]
        self.assertEqual(layout, [
            ("直连", "翡翠台"), ("直连", "ViuTVsix"), ("直连", "Now新聞台"),
            ("官方", "翡翠台"),
            ("备用", "ViuTV"),
        ])

    def test_rendered_output_parses_back_into_the_streams_that_were_kept(self):
        rows = [("ViuTV", "https://direct.test/v.m3u8"), ("ViuTVsix", "https://direct.test/s.m3u8")]
        text, result = self.build(rows)
        self.assertEqual([channel.url for channel in build_curated_live.parse_playlist(text)],
                         self.kept_urls(result))
        self.assertEqual(text.splitlines()[0], '#EXTM3U x-tvg-url="https://epg.test/x.xml"')
        self.assertEqual(sorted(canonical for canonical, _ in result["kept"] if canonical),
                         ["ViuTV", "ViuTVsix", "翡翠台"])

    def test_probing_off_keeps_everything_and_says_so(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        profile = dict(PROFILE, reference_asset=str(pathlib.Path(directory.name, "none.m3u8")))
        with mock.patch.object(update_depot, "fetch_cached",
                               return_value=playlist([("ViuTV", "https://dead.test/v.m3u8")])):
            _, result = build_curated_live.build(
                profile, include_global=False, refresh=False, probing=False, workers=4,
                keep_unmatched=False)
        self.assertEqual(result["verdicts"], {})
        self.assertEqual(result["totals"]["gone"], 0)
        self.assertEqual(result["per_channel"]["ViuTV"][2][0].url, "https://dead.test/v.m3u8")
        self.assertEqual(result["per_channel"]["ViuTV"][2][0].group, "备用")


class PinnedChannels(unittest.TestCase):
    PIN = [{"name": "Pet Club TV", "url": "https://petclub.test/playlist.m3u8",
            "group": "direct", "note": "hand-picked"}]

    def build(self, rows, gone=(), **overrides):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        profile = dict(PROFILE, reference_asset=str(pathlib.Path(directory.name, "none.m3u8")),
                       **overrides)

        def fake_fetch_http(url, *args, **kwargs):
            host = host_of(url)
            if host in gone:
                return validate_depot.Fetch(False, "HTTP 404 Not Found")
            return validate_depot.Fetch(True, "loaded 4000 byte(s)")

        with mock.patch.object(update_depot, "fetch_cached", return_value=playlist(rows)), \
                mock.patch.object(validate_depot, "fetch_http", side_effect=fake_fetch_http):
            return build_curated_live.build(
                profile, include_global=False, refresh=False, probing=True, workers=4,
                keep_unmatched=False)

    def test_a_pin_survives_even_when_no_source_publishes_it(self):
        _, result = self.build([("ViuTV", "https://direct.test/v.m3u8")],
                               pinned_channels=self.PIN)
        kept = {channel.url for _, channel in result["kept"]}
        self.assertIn("https://petclub.test/playlist.m3u8", kept)
        pinned = [channel for _, channel in result["kept"] if channel.source == "pinned"]
        self.assertEqual(pinned[0].group, "直连")

    def test_a_pin_for_a_known_channel_carries_that_channels_canonical_name(self):
        pin = [{"name": "翡翠台 4K", "url": "https://direct.test/jade.m3u8",
                "group": "direct", "note": "hand-picked mirror"}]
        _, result = self.build([("翡翠台", "https://proxy.test/jade.m3u8")], pinned_channels=pin)
        self.assertIn(("翡翠台", "pinned"),
                      [(canonical, channel.source) for canonical, channel in result["kept"]])

    def test_a_pin_that_is_provably_dead_is_kept_and_reported(self):
        _, result = self.build([("ViuTV", "https://direct.test/v.m3u8")],
                               gone=("petclub.test",), pinned_channels=self.PIN)
        self.assertIn("https://petclub.test/playlist.m3u8",
                      {channel.url for _, channel in result["kept"]})
        self.assertEqual(len(result["pinned_notes"]), 1)
        self.assertIn("kept because a human chose it", result["pinned_notes"][0])

    def test_a_pin_already_produced_by_the_harvest_is_not_duplicated(self):
        rows = [("Pet Club TV", "https://petclub.test/playlist.m3u8")]
        _, result = self.build(rows, pinned_channels=self.PIN,
                               **{"canonical_channels": dict(PROFILE["canonical_channels"],
                                                             **{"Pet Club TV": ["pet club"]}),
                                  "order": PROFILE["order"] + ["Pet Club TV"]})
        urls = [channel.url for _, channel in result["kept"]]
        self.assertEqual(urls.count("https://petclub.test/playlist.m3u8"), 1)
        self.assertIn("already published", result["pinned_notes"][0])


class ReferenceDiff(unittest.TestCase):
    def test_the_build_reports_what_it_would_lose_against_the_curated_asset(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        reference = pathlib.Path(directory.name, "curated.m3u8")
        reference.write_text(REFERENCE_ASSET, encoding="utf-8")
        profile = dict(PROFILE, reference_asset=str(reference))
        with mock.patch.object(update_depot, "fetch_cached",
                               return_value=playlist([("翡翠台", "https://kept.test/j.m3u8")])):
            _, result = build_curated_live.build(
                profile, include_global=False, refresh=False, probing=False, workers=4,
                keep_unmatched=False)
        compare = result["compare"]
        self.assertEqual(compare["shared"], 1)
        # proxy.test/pearl.m3u8 is curated and this build has no source offering it.
        self.assertEqual([channel.name for _, channel in compare["missing"]], ["明珠台"])
        self.assertIn("https://official.test/jade.m3u8",
                      [channel.url for _, channel in compare["added"]])

    def test_no_reference_asset_means_no_diff_rather_than_a_failure(self):
        profile = dict(PROFILE, reference_asset="nowhere/none.m3u8")
        self.assertEqual(build_curated_live.compare_reference(profile, []), {})

    def test_the_diff_labels_a_stream_the_way_the_viewer_sees_it(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        reference = pathlib.Path(directory.name, "curated.m3u8")
        reference.write_text("#EXTM3U\n#EXTINF:-1 tvg-name=\"明珠台\",明珠台\nhttps://a.test/1\n",
                             encoding="utf-8")
        profile = dict(PROFILE, reference_asset=str(reference))
        source = build_curated_live.Channel(name="ViuTV (720p) [Geo-blocked]",
                                            url="https://b.test/2", group="直连")
        compare = build_curated_live.compare_reference(profile, [("ViuTV", source)])
        self.assertEqual([label for label, _ in compare["added"]], ["ViuTV 720P"])


class Report(unittest.TestCase):
    def result(self, totals=None, blocked=None, unmatched=None, pinned_notes=(), compare=None):
        channel = build_curated_live.Channel(name="翡翠台", url="https://direct.test/j.m3u8",
                                             group="直连")
        totals = totals if totals is not None else Counter()
        return {
            "per_channel": {"翡翠台": (1, totals, [channel])},
            "kept": [("翡翠台", channel)],
            "verdicts": {"https://direct.test/j.m3u8": ("alive", "loaded 4000 byte(s)")},
            "totals": totals,
            "notes": ["source unavailable, skipped: https://list.test/z.m3u8"],
            "pinned": [], "pinned_notes": list(pinned_notes),
            "compare": compare if compare is not None else {},
            "stats": {"parsed": 3, "distinct names": 3, "blocked": blocked or Counter(),
                      "unmatched": unmatched or Counter()},
        }

    def printed(self, profile, result, keep_unmatched=False):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch("builtins.print") as printed:
                build_curated_live.report(profile, result, pathlib.Path(directory, "out.m3u8"),
                                          "", keep_unmatched)
        return "\n".join(str(call.args[0]) for call in printed.call_args_list if call.args)

    def test_report_states_kept_counts_and_the_reasons_for_every_deletion(self):
        text = self.printed(PROFILE, self.result(Counter({"gone": 2, "over cap": 1})))
        self.assertIn("Kept 1 streams", text)
        self.assertIn("gone", text)
        self.assertIn("over the 4-per-channel cap", text)
        self.assertIn("Untestable streams stay", text)
        self.assertIn("source unavailable", text)
        self.assertIn("翡翠台", text)

    def test_report_tells_the_reader_the_alias_table_missed_names(self):
        text = self.printed(PROFILE, self.result(unmatched=Counter({"港島東频道": 3})))
        self.assertIn("does not know", text)
        self.assertIn("left out", text)
        self.assertIn("港島東频道", text)

    def test_report_shows_what_a_rebuild_would_take_away_from_the_curated_list(self):
        lost = build_curated_live.Channel(name="明珠台", url="https://proxy.test/pearl.m3u8",
                                         group="备用")
        compare = {"reference": [lost], "missing": [("明珠台", lost)], "added": [], "shared": 0,
                   "path": pathlib.Path("curated.m3u8")}
        text = self.printed(PROFILE, self.result(compare=compare))
        self.assertIn("1 only in the curated asset", text)
        self.assertIn("would be lost: 明珠台", text)

    def test_a_dead_pin_is_visible_in_the_report(self):
        text = self.printed(PROFILE, self.result(
            pinned_notes=["pinned stream is not answering: Pet Club TV (HTTP 404) kept"]))
        self.assertIn("Pet Club TV", text)
        self.assertIn("pinned by hand", text)


if __name__ == "__main__":
    unittest.main()
