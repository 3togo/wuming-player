"""DEX reader tests against synthetic images built here.

Generating the images locally keeps the suite offline and deterministic, which also means
the expectations here describe *our* parser, not the ART verifier. It reads item tables
only; anything relying on verification or bytecode semantics is out of scope.
"""

from catvod_lab import dex
from dexfactory import build_dex


def test_class_names_are_recovered():
    image = build_dex(
        ["https://pan.quark.cn/s/abc", "/1/clouddrive/file/download"],
        ["com.github.catvod.spider.Quark", "com.github.catvod.spider.Init"],
    )
    parsed = dex.parse(image)
    assert parsed.error == ""
    assert parsed.classes == ["com.github.catvod.spider.Quark", "com.github.catvod.spider.Init"]
    assert parsed.package_classes("com.github.catvod.spider") == parsed.classes


def test_string_pool_is_recovered():
    image = build_dex(["https://pan.quark.cn/s/abc"], ["com.example.A"])
    parsed = dex.parse(image)
    assert "https://pan.quark.cn/s/abc" in parsed.strings


def test_non_dex_input_is_reported_not_thrown():
    assert dex.parse(b"not a dex").error == "not a DEX image"


def test_truncated_image_is_reported_not_thrown():
    parsed = dex.parse(b"dex\n035\x00" + b"\x00" * 20)
    assert parsed.error
    assert parsed.classes == []


def test_magic_detection_and_declared_size():
    image = build_dex(["x"], ["com.example.A"])
    assert dex.looks_like_dex(image)
    assert dex.file_size_at(image) == len(image)
    assert dex.looks_like_dex(b"\x7fELF" + image) is False
    # The declared file_size is the image's own length, not the bytes left after it.
    assert dex.file_size_at(b"\x7fELF" + image, offset=4) == len(image)
