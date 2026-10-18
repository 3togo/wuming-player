"""Journal and artifact-store tests.

The ledgers are the only durable output of a fetch run, so "append-only, deduplicated,
content-addressed" has to hold even when a run crashes midway and is retried.
"""

from catvod_lab.store import Layout


def test_artifacts_are_content_addressed_and_written_once(tmp_path):
    layout = Layout.create(tmp_path)
    path = layout.save_artifact(b"plugin bytes", "a" * 64, "so")
    assert path.parent.name == "aa"
    assert path.name.endswith(".so")
    assert layout.has_artifact("a" * 64)
    assert layout.resolve_artifact("a" * 64).read_bytes() == b"plugin bytes"


def test_unknown_extension_falls_back_to_bin(tmp_path):
    layout = Layout.create(tmp_path)
    path = layout.save_artifact(b"x", "b" * 64, "../../evil")
    assert path.suffix == ".bin"


def test_journals_append_in_order(tmp_path):
    layout = Layout.create(tmp_path)
    layout.append("configs", {"n": 1})
    layout.append("configs", {"n": 2})
    rows = layout.read("configs")
    assert [row["n"] for row in rows] == [1, 2]


def test_latest_collapses_by_key_keeping_the_last_written(tmp_path):
    layout = Layout.create(tmp_path)
    layout.append("artifacts", {"sha256": "abc", "source": "first"})
    layout.append("artifacts", {"sha256": "abc", "source": "second"})
    latest = layout.latest("artifacts", "sha256")
    assert latest["abc"]["source"] == "second"


def test_ledger_is_append_only_and_deduplicated(tmp_path):
    layout = Layout.create(tmp_path)
    first = layout.append_ledger([{"id": "001", "observed": "x"}, {"id": "002", "observed": "y"}])
    assert len(first) == 2
    second = layout.append_ledger([{"id": "001", "observed": "x"}, {"id": "003", "observed": "z"}])
    assert [row["id"] for row in second] == ["003"]
    assert [row["id"] for row in layout.read_ledger()] == ["001", "002", "003"]


def test_config_snapshot_is_stored_outside_the_journal(tmp_path):
    layout = Layout.create(tmp_path)
    relative = layout.save_config_snapshot(b'{"spider":"a.jar"}')
    assert (tmp_path / relative).read_bytes() == b'{"spider":"a.jar"}'
