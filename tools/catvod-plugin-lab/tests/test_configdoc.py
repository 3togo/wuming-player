"""Offline tests for configuration decoding.

The stored expectations mirror the Kotlin decoder: a configuration this tool refuses to
read is a configuration the app refuses to load, and vice versa.
"""

import pytest

from catvod_lab import configdoc


def test_direct_json_is_accepted():
    payload = b'{"spider":"https://host/a.jar","sites":[]}'
    assert configdoc.decode_payload(payload).startswith("{")


def test_utf8_bom_is_stripped():
    payload = b"\xef\xbb\xbf" + b'{"spider":"a.jar","sites":[]}'
    doc = configdoc.parse_payload(payload)
    assert doc.spider == "a.jar"


def test_base64_envelope_is_decoded():
    import base64

    inner = b'{"spider":"https://host/plugin.jar","sites":[]}'
    envelope = b"\x89PNG\x0d\x0a\x1a\x0a00000000**" + base64.b64encode(inner)
    doc = configdoc.parse_payload(envelope)
    assert doc.spider == "https://host/plugin.jar"


def test_envelope_without_marker_is_rejected():
    with pytest.raises(configdoc.PayloadError):
        configdoc.decode_payload(b"not a configuration at all")


def test_non_json_document_is_rejected():
    with pytest.raises(configdoc.PayloadError):
        configdoc.decode_payload(b"#EXTM3U x-tvg-url=1,First channel")


def test_line_comments_and_trailing_commas_are_tolerated():
    text = """
    {
      // a comment with a url https://example.invalid/ignored
      "spider": "https://host/plugin.jar",
      "sites": [
        {"key":"a","name":"A","type":3,"api":"csp_X","jar":"https://host/own.jar",},
      ],
    }
    """
    doc = configdoc.parse_config(text)
    assert doc.spider == "https://host/plugin.jar"
    assert len(doc.sites) == 1
    assert doc.sites[0].required_jar == "https://host/own.jar"


def test_comment_markers_inside_strings_survive():
    text = '{"spider":"https://host/plugin.jar","sites":[]}'
    assert configdoc.loads_lenient(text)["spider"] == "https://host/plugin.jar"


def test_runtime_classification_matches_policy():
    assert configdoc.classify_runtime(3, "csp_Quark") == "spider_runtime_required"
    assert configdoc.classify_runtime(3, "https://host/app.js?x=1") == "direct_javascript"
    assert configdoc.classify_runtime(0, "https://host/api.php/provide/vod") == "maccms_api"
    assert configdoc.classify_runtime(4, "https://host/remote") == "remote_spider_http"
    assert configdoc.classify_runtime(7, "whatever") == "unsupported_type"


def test_site_prefers_jar_then_ext_and_only_for_spider_sites():
    site = configdoc.Site(key="k", name="n", type=3, api="csp_X", jar="", ext="https://host/ext.jar")
    assert site.required_jar == "https://host/ext.jar"

    json_site = configdoc.Site(type=0, api="https://host/api.php/provide/vod", jar="https://host/never.jar")
    assert json_site.required_jar == ""


def test_references_fall_back_to_top_level_spider():
    text = """
    {"spider":"https://host/plugin.jar",
     "sites":[{"key":"own","name":"Own","type":3,"api":"csp_A","jar":"https://host/own.jar"},
              {"key":"inherit","name":"Inherit","type":3,"api":"csp_B"},
              {"key":"js","name":"JS","type":3,"api":"https://host/x.js"}]}
    """
    doc = configdoc.parse_config(text)
    refs = {r["site_key"]: r for r in doc.references()}
    assert refs["own"]["ref"] == "https://host/own.jar"
    assert refs["own"]["origin"] == "site-jar"
    assert refs["inherit"]["ref"] == "https://host/plugin.jar"
    assert refs["inherit"]["origin"] == "top-level-spider"
    assert "js" not in refs
