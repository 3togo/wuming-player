"""Artifact inspection tests for the container shapes we actually meet.

The `.so` cases matter most: a plugin shipped as a shared object arrives either as a ZIP
with the wrong extension, or as an ELF carrying a DEX past its header. Both must end up
with readable class tables or the corpus silently loses every guarded provider.
"""

import io
import struct
import zipfile

import pytest

from catvod_lab import artifact
from dexfactory import build_dex  # synthetic DEX builder shared with the reader tests


def _fake_elf(payload: bytes = b"") -> bytes:
    """64-byte ELF header for aarch64 dynamic object, followed by arbitrary bytes."""
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2  # ELFCLASS64
    header[5] = 1  # ELFDATA2LSB
    struct.pack_into("<HH", header, 16, 3, 183)  # ET_DYN, EM_AARCH64
    return bytes(header) + payload


def test_zip_with_dex_is_read(tmp_path):
    image = build_dex(["https://pan.quark.cn/s/x"], ["com.github.catvod.spider.Quark"])
    path = tmp_path / "spider.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", image)
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")

    report = artifact.inspect(path)
    assert report["container"] == "zip"
    assert report["loader_shape"] == "zip-with-dex"
    assert report["zip"]["dex_entries"] == 1
    assert report["dex"][0]["catvod_classes"] == ["com.github.catvod.spider.Quark"]


def test_so_named_zip_is_detected_by_magic_not_extension(tmp_path):
    image = build_dex(["https://cloud.189.cn/a"], ["com.github.catvod.spider.Tianyi"])
    path = tmp_path / "libspider.so"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", image)

    report = artifact.inspect(path)
    assert report["container"] == "zip"
    assert report["loader_shape"] == "zip-with-dex"


def test_elf_with_embedded_dex_is_carved(tmp_path):
    image = build_dex(["https://drive.uc.cn/file/a"], ["com.github.catvod.spider.UCApi"])
    path = tmp_path / "libguard.so"
    path.write_bytes(_fake_elf(b"\x00" * 4096 + image))

    report = artifact.inspect(path)
    assert report["container"] == "elf"
    assert report["elf"]["machine"] == "aarch64"
    assert report["loader_shape"] == "embedded-dex"
    sources = [d["source"] for d in report["dex"]]
    assert any(source.startswith("dex@") for source in sources)


def test_elf_with_embedded_zip_is_carved(tmp_path):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("AndroidManifest.xml", "\x03\x00\x08\x00")
        archive.writestr("classes.dex", build_dex(["https://pan.baidu.com/s/1"], ["com.github.catvod.spider.Baidu"]))
    path = tmp_path / "libpacked.so"
    path.write_bytes(_fake_elf(b"\x91" * 2048 + buffer.getvalue()))

    report = artifact.inspect(path)
    assert report["loader_shape"] == "embedded-dex"
    assert any(entry["dex_entries"] >= 1 for entry in report["embedded"])


def test_plain_dex_file(tmp_path):
    image = build_dex(["https://123pan.com/a"], ["com.github.catvod.spider.Pan123"])
    path = tmp_path / "classes.dex"
    path.write_bytes(image)

    report = artifact.inspect(path)
    assert report["container"] == "dex"
    assert report["loader_shape"] == "raw-dex"
    assert report["dex"][0]["catvod_class_count"] == 1


def test_html_error_page_is_not_mistaken_for_an_artifact(tmp_path):
    path = tmp_path / "plugin.jar"
    path.write_text("<html><head><title>403 Forbidden</title></head><body>nope</body></html>")

    report = artifact.inspect(path)
    assert report["container"] == "text"
    assert report["loader_shape"] == "none"
    assert report["dex"] == []


def test_broken_zip_is_reported_and_survives(tmp_path):
    path = tmp_path / "broken.jar"
    path.write_bytes(b"PK\x03\x04" + b"\x00" * 64)

    report = artifact.inspect(path)
    assert report["container"] == "broken-zip"
    assert any("bad zip" in note for note in report["notes"])


def test_unknown_container_has_no_dex_but_reports_a_head(tmp_path):
    path = tmp_path / "opaque.bin"
    path.write_bytes(b"\x00\x01\x02\x03" + b"\xff" * 128)

    report = artifact.inspect(path)
    assert report["container"] == "unknown"
    assert report["head_hex"].startswith("00010203")
    assert report["loader_shape"] == "none"


def test_html_and_json_payloads_are_refused():
    assert artifact.accepts_payload(b"") == "empty body"
    assert artifact.accepts_payload(b"<html><body>403</body></html>").startswith("text/error page")
    assert artifact.accepts_payload(b'{"sites":[]}').startswith("text/error page")
    assert artifact.accepts_payload(b"maintenance window, try later") .startswith("text payload")
    # Non-UTF-8 binary that is too small to be a launcher: kept only as a refusal reason.
    assert artifact.accepts_payload(b"\x00\xff\xfe" * 67).startswith("smaller than any real plugin")


def test_plugin_containers_are_accepted():
    assert artifact.accepts_payload(b"PK\x03\x04" + b"\x00" * 900) == ""
    assert artifact.accepts_payload(b"\x1f\x8b\x08" + b"\x00" * 900) == ""
    assert artifact.accepts_payload(_fake_elf(b"\x00" * 900)) == ""
    assert artifact.accepts_payload(build_dex(["x"], ["com.example.A"]) + b"\x00" * 512) == ""


def test_opaque_binary_kept_because_guarded_launchers_have_no_magic():
    assert artifact.accepts_payload(bytes(range(256)) * 8) == ""


@pytest.mark.parametrize("head,expected", [
    (b"PK\x03\x04rest", "zip"),
    (b"\x7fELF\x02\x01\x01", "elf"),
    (b"\x1f\x8b\x08\x00", "gzip"),
    (b"<html><body>", "text"),
])
def test_container_detection(head, expected):
    assert artifact.detect_container(head) == expected
