import time

import pytest
from langgraph.checkpoint.base import empty_checkpoint

from langgraph_runtime.checkpoint_store import FileBackedMemorySaver
from langgraph_runtime.runtime_state_store import RuntimeStateStore


def test_checkpoint_round_trip_preserves_langgraph_serialized_values(tmp_path):
    path = tmp_path / "checkpoint.json"
    config = {"configurable": {"thread_id": "wf_checkpoint_01", "checkpoint_ns": ""}}
    checkpoint = empty_checkpoint()
    checkpoint["channel_values"] = {"state": {"answer": "ok", "values": [1, 2]}}
    checkpoint["channel_versions"] = {"state": "0001"}

    saver = FileBackedMemorySaver(str(path))
    saver.put(
        config,
        checkpoint,
        {"source": "loop", "step": 1, "parents": {}},
        {"state": "0001"},
    )

    restored = FileBackedMemorySaver(str(path)).get_tuple(
        {"configurable": {"thread_id": "wf_checkpoint_01"}}
    )
    assert restored is not None
    assert restored.checkpoint["channel_values"]["state"]["answer"] == "ok"
    assert restored.checkpoint["channel_values"]["state"]["values"] == [1, 2]


def test_checkpoint_cleanup_removes_expired_thread(tmp_path):
    path = tmp_path / "checkpoint.json"
    saver = FileBackedMemorySaver(str(path))
    saver.storage["wf_expired_01"][""]["checkpoint"] = ("json", b"{}")
    saver._thread_updated_at["wf_expired_01"] = time.time() - 100
    saver._persist()

    assert saver.cleanup_expired(ttl_seconds=1) == 1
    assert "wf_expired_01" not in saver.storage


def test_checkpoint_cleanup_only_removes_stale_matching_temp_files(tmp_path):
    path = tmp_path / "checkpoint.json"
    saver = FileBackedMemorySaver(str(path))
    stale = tmp_path / "checkpoint.json.abandoned.tmp"
    fresh = tmp_path / "checkpoint.json.active.tmp"
    unrelated = tmp_path / "other.json.abandoned.tmp"
    stale.write_text("stale", encoding="utf-8")
    fresh.write_text("fresh", encoding="utf-8")
    unrelated.write_text("unrelated", encoding="utf-8")
    old_time = time.time() - 120
    stale.touch()
    import os
    os.utime(stale, (old_time, old_time))
    os.utime(unrelated, (old_time, old_time))

    assert saver.cleanup_stale_temp_files(max_age_seconds=60) == 1
    assert not stale.exists()
    assert fresh.exists()
    assert unrelated.exists()


def test_runtime_state_rejects_path_traversal(tmp_path):
    store = RuntimeStateStore(str(tmp_path))
    with pytest.raises(ValueError):
        store.save("../outside", {"status": "RUNNING"})
