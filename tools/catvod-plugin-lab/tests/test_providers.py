"""Family classification tests.

The corpus exists to produce *leads*, not verdicts, so these tests pin the two failure
modes that would make it useless: false positives from fuzzy keyword matching, and
findings that quietly arrive classified as anything but `unverified`.
"""

from catvod_lab import providers


def _match_for(family_id, matches):
    return next(m for m in matches if m.family.id == family_id)


def test_mine_addresses_keeps_address_like_literals_and_drops_noise():
    strings = [
        "https://drive.uc.cn/1/clouddrive/file/download?pr=UCBrowser&fr=pc",
        "/api/fs/search",
        "pan.baidu.com",
        "https://github.com/x/y/blob/main/q",          # framework host -> dropped
        "https://www.w3.org/TR/xml/",                   # documentation host -> dropped
        "/org/apache/commons/io/IOUtils.java",         # internal path -> dropped
        "/1/clouddrive/file/v2/play",
        "{\"regex\": \"a+b*\"}",                        # regex fragment -> dropped
        "some long prose sentence that contains pan.baidu.com inside it",  # prose -> dropped
        "com.android.contacts",                        # Android package, not a host -> dropped
        "execjs.content",                               # framework identifier -> dropped
        "android.resource",                             # framework identifier -> dropped
    ]
    mined = providers.mine_addresses(strings)
    keys = list(mined)
    assert "https://drive.uc.cn/1/clouddrive/file/download" in keys
    assert "/api/fs/search" in keys
    assert "pan.baidu.com" in keys
    assert "/1/clouddrive/file/v2/play" in keys
    # Framework / documentation hosts are dropped.
    assert all("github.com" not in k and "w3.org" not in k for k in keys)
    # Internal Java paths and regex fragments are dropped.
    assert all(not k.startswith("/org") for k in keys)
    assert all("regex" not in k for k in keys)
    # Dotted class/action names (mixed-case TLD segment) must NOT pass as hosts.
    assert all("ActivityThread" not in k and "MXParserFactory" not in k
               and "XMLInputFactory" not in k for k in keys)
    # Android/Java package and framework identifiers must not pass as hosts.
    assert all("com.android.contacts" not in k and "execjs.content" not in k
               and "android.resource" not in k for k in keys)
    # Loopback / IP hosts are not cloud-drive targets.
    assert all("127.0.0.1" not in k for k in keys)
    # Prose (whitespace inside a token) is dropped even when it contains a host.
    assert all("prose" not in k for k in keys)



def test_quark_is_classified_from_class_name_plus_host():
    matches = providers.classify(
        ["com.github.catvod.spider.Quark", "com.github.catvod.spider.Init"],
        ["https://pan.quark.cn/s/6d2d6b2b1f1f", "/1/clouddrive/file/download"],
    )
    match = _match_for("quark", matches)
    assert match.class_hits == ["com.github.catvod.spider.Quark"]
    assert match.host_hits
    assert match.endpoint_hits
    assert match.confidence == "high"


def test_short_keywords_match_a_whole_class_segment_only():
    """Real bundles name their netdisk entry classes `UC`, `Ali` and `P115`.

    Two- or three-letter keywords therefore have to count, but only when they are an
    entire dotted segment — otherwise every `Duck` or `Qualified` class becomes a lead.
    """
    for family_id, name in (("uc", "UC"), ("aliyun", "Ali"), ("drive115", "P115")):
        assert next((m for m in providers.classify([f"com.github.catvod.spider.{name}"], [])
                     if m.family.id == family_id), None) is not None

    assert all(m.family.id != "uc"
               for m in providers.classify(["com.github.catvod.spider.Duck"], []))


def test_whole_segment_rule_does_not_allow_substring_leaks():
    assert not providers.matches_class_keyword("com.github.catvod.spider.Duck", "uc")
    assert not providers.matches_class_keyword("com.github.catvod.spider.Qualified", "ali")
    assert providers.matches_class_keyword("com.github.catvod.spider.UCShare", "ucshare")


def test_longer_keywords_match_inside_a_segment():
    matches = providers.classify(["com.github.catvod.spider.MyUCApiv2"], [])
    hit = next((m for m in matches if m.family.id == "uc"), None)
    assert hit is not None
    assert hit.confidence == "low"


def test_host_only_evidence_is_low_confidence():
    matches = providers.classify([], ["https://cloud.189.cn/api/portal/x"])
    match = _match_for("tianyi", matches)
    assert match.confidence == "low"
    assert not match.class_hits


def test_every_finding_starts_unverified():
    # The pipeline may never promote a finding; only readable source or a trace can.
    for row in providers.FAMILIES:
        assert row.label
    matches = providers.classify(["com.github.catvod.spider.Quark"], ["https://pan.quark.cn/s/x"])
    assert matches
    assert all(m.confidence != "none" for m in matches)


def test_coverage_kinds_distinguish_builtin_from_missing_providers():
    table = providers.families_by_id()
    assert table["quark"].opendrive_kind == "builtin"
    assert table["mobile139"].opendrive_kind == "none"
    assert table["drive115"].opendrive_kind == "none"


def test_generic_cloud_detection_catches_neutral_branding():
    classes = ["com.github.catvod.spider.MyDriveGuard"]
    assert providers.is_cloud_related(classes, [], [])

    classes = ["com.github.catvod.spider.Random"]
    strings = ["https://example.invalid/api/list"]
    assert providers.is_cloud_related(classes, strings, []) is False


def test_endpoint_candidates_exclude_known_signatures_and_junk():
    strings = [
        "https://pan.quark.cn/s/abc",                # known host — excluded
        "/1/clouddrive/file/download",               # known signature — excluded
        "https://unknown-host.example/api/v3/share/download?x=1",
        "not a url at all",                          # sentence, not an endpoint
        "/api/p([a-z]+)",                            # regex fragment, not an endpoint
        "/META-INF/services/org.xmlpull.v1.XmlPullParserFactory",
        "/TV.subtitles/BDMV/index.bdmv",             # media asset path
        "//",                                        # punctuation
        "/a",
    ]
    candidates = providers.endpoint_candidates(strings)
    assert "https://unknown-host.example/api/v3/share/download" in candidates
    for junk in ("not a url at all", "/api/p([a-z]+)",
                 "/META-INF/services/org.xmlpull.v1.XmlPullParserFactory",
                 "/TV.subtitles/BDMV/index.bdmv", "//", "/a"):
        assert junk not in candidates
