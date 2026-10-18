"""Reference resolution tests.

Relative references are the silent-corruption risk in this pipeline: resolve against the
requested URL instead of the post-redirect URL and you download a different host's file
while recording it under the original config.
"""

from catvod_lab import http, resolve


def test_md5_suffix_is_split_off_the_url():
    head, md5 = resolve.split_md5("https://host/plugin.jar;md5;dffec63fb83a2b31b8606bd4bf6bead9")
    assert head == "https://host/plugin.jar"
    assert md5 == "dffec63fb83a2b31b8606bd4bf6bead9"


def test_absolute_url_is_untouched():
    resolved = resolve.resolve_reference("https://host/a.jar", "https://config.example/x.json")
    assert resolved.kind == "http"
    assert resolved.url == "https://host/a.jar"
    assert resolved.fetchable


def test_current_directory_reference_resolves_against_final_config_url():
    final = "https://config.example/tv/sub/api.json"
    resolved = resolve.resolve_reference("./jar/pg.jar", final)
    assert resolved.url == "https://config.example/tv/sub/jar/pg.jar"
    assert resolved.kind == "relative"


def test_parent_directory_reference_climbs_from_the_final_path():
    final = "https://config.example/tv/sub/api.json"
    resolved = resolve.resolve_reference("../jar/pro.jar", final)
    assert resolved.url == "https://config.example/tv/jar/pro.jar"


def test_app_local_references_are_recorded_but_never_fetched():
    for reference, kind in (("assets://web/custom.jar", "assets"), ("file:///sdcard/local.jar", "file")):
        resolved = resolve.resolve_reference(reference, "https://config.example/api.json")
        assert resolved.kind == kind
        assert resolved.skip_reason
        assert not resolved.fetchable


def test_extension_hint_never_drives_detection():
    # A `.so` may be a zip and a `.jar` may be an ELF; the hint only names stored bytes.
    assert resolve.resolve_reference("https://h/libcrypto.so", "").extension_hint() == "so"
    assert resolve.resolve_reference("https://h/plugin.jar", "").extension_hint() == "jar"
    assert resolve.resolve_reference("https://h/download.php?f=1", "").extension_hint() == "bin"


def test_declared_md5_may_be_a_url():
    resolved = resolve.resolve_reference("https://h/p.jar;md5;https://h/p.jar.md5", "")
    assert resolved.md5_is_url
    value = resolve.expected_md5(resolved, lambda url: http.FetchResult(ok=True, url=url, body=b" abc123 \n"))
    assert value == "abc123"


def test_missing_md5_source_yields_no_expectation():
    resolved = resolve.resolve_reference("https://h/p.jar;md5;https://h/gone", "")
    value = resolve.expected_md5(resolved, lambda url: http.FetchResult(ok=False, url=url))
    assert value is None


def test_document_urls_are_refused_before_any_download():
    """Sites ship page URLs in the same fields as jar references.

    Downloading them would flood the corpus with HTML and nested configurations, so the
    refusal happens before a request is made and is recorded with its reason.
    """
    for url in ("http://host/tv/json/site.json", "https://host/", "https://host/jar.php?f=1",
                "https://host/logo.png"):
        resolved = resolve.resolve_reference(url, "https://config.example/api.json")
        assert resolved.kind == "non-artifact"
        assert resolved.skip_reason
        assert not resolved.fetchable


def test_extensionless_references_are_allowed_to_reach_the_content_gate():
    resolved = resolve.resolve_reference("./CFlLnSbJ", "https://config.example/tv/api.json")
    assert resolved.kind == "relative"
    assert resolved.fetchable


def test_empty_reference_is_skipped():
    resolved = resolve.resolve_reference("   ", "https://config.example/api.json")
    assert resolved.skip_reason == "empty reference"
