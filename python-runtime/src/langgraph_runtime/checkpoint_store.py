"""Durable, JSON-backed LangGraph checkpointer for the local runtime.

LangGraph's in-memory saver stores serialized channel values as bytes inside
tuple-heavy mappings. Persisting those objects with ``default=str`` produces a
readable file but destroys the types required for resume. This adapter uses
explicit JSON type markers and Base64, atomic replacement, and per-thread TTL.
"""

from __future__ import annotations

import base64
import json
import os
import threading
import time
import uuid
from collections import defaultdict
from pathlib import Path
from typing import Any

from langgraph.checkpoint.memory import InMemorySaver


DEFAULT_CHECKPOINT_TTL_SECONDS = 7 * 24 * 3600


class FileBackedMemorySaver(InMemorySaver):
    """Persist ``InMemorySaver`` state without changing LangGraph semantics."""

    def __init__(self, path: str):
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._thread_updated_at: dict[str, float] = {}
        super().__init__()
        self._load()

    def put(self, config, checkpoint, metadata, new_versions):
        with self._lock:
            result = super().put(config, checkpoint, metadata, new_versions)
            self._touch(config)
            self._persist()
            return result

    def put_writes(self, config, writes, task_id, task_path=""):
        with self._lock:
            super().put_writes(config, writes, task_id, task_path)
            self._touch(config)
            self._persist()

    def clear_thread(self, thread_id: str):
        with self._lock:
            self._remove_thread(thread_id)
            self._persist()

    def cleanup_expired(self, ttl_seconds: int = DEFAULT_CHECKPOINT_TTL_SECONDS) -> int:
        cutoff = time.time() - max(1, ttl_seconds)
        with self._lock:
            expired = [
                thread_id
                for thread_id, updated_at in self._thread_updated_at.items()
                if updated_at < cutoff
            ]
            for thread_id in expired:
                self._remove_thread(thread_id)
            if expired:
                self._persist()
            return len(expired)

    def cleanup_stale_temp_files(self, max_age_seconds: int = 3600) -> int:
        """Remove abandoned atomic-write files created by crashed processes.

        Only files matching this checkpointer's own
        ``<checkpoint-name>.<uuid>.tmp`` pattern are considered. Fresh files
        are left alone so concurrent writers are not disturbed.
        """
        cutoff = time.time() - max(1, max_age_seconds)
        prefix = f"{self._path.name}."
        deleted = 0
        try:
            candidates = list(self._path.parent.iterdir())
        except OSError:
            return 0
        for candidate in candidates:
            if (
                not candidate.is_file()
                or not candidate.name.startswith(prefix)
                or not candidate.name.endswith(".tmp")
            ):
                continue
            try:
                if candidate.stat().st_mtime < cutoff:
                    candidate.unlink()
                    deleted += 1
            except OSError:
                continue
        return deleted

    def _remove_thread(self, thread_id: str):
        self.storage.pop(thread_id, None)
        self.blobs = defaultdict(
            None, {key: value for key, value in self.blobs.items() if key[0] != thread_id}
        )
        self.writes = defaultdict(
            dict, {key: value for key, value in self.writes.items() if key[0] != thread_id}
        )
        self._thread_updated_at.pop(thread_id, None)

    def _touch(self, config: dict[str, Any] | None):
        configurable = (config or {}).get("configurable") or {}
        thread_id = str(configurable.get("thread_id") or "").strip()
        if thread_id:
            self._thread_updated_at[thread_id] = time.time()

    def _persist(self):
        payload = {
            "schema_version": 2,
            "thread_updated_at": self._thread_updated_at,
            "storage": {
                thread_id: {
                    namespace: self._jsonify(entries)
                    for namespace, entries in namespaces.items()
                }
                for thread_id, namespaces in self.storage.items()
            },
            "writes": self._jsonify(dict(self.writes)),
            "blobs": self._jsonify(dict(self.blobs)),
        }
        temp_path = self._path.with_name(f"{self._path.name}.{uuid.uuid4().hex}.tmp")
        try:
            with temp_path.open("w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
            try:
                os.replace(temp_path, self._path)
            except PermissionError:
                with self._path.open("w", encoding="utf-8") as handle:
                    json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
        finally:
            if temp_path.exists():
                try:
                    temp_path.unlink()
                except OSError:
                    pass

    @classmethod
    def _encode_key(cls, key: Any) -> str:
        if isinstance(key, tuple):
            encoded = cls._jsonify(key)
            return "__typed_key__:" + json.dumps(encoded, ensure_ascii=False, separators=(",", ":"))
        return str(key)

    @classmethod
    def _decode_key(cls, key: str) -> Any:
        if isinstance(key, str) and key.startswith("__typed_key__:"):
            try:
                return cls._dejsonify(json.loads(key[len("__typed_key__:"):]))
            except (json.JSONDecodeError, TypeError, ValueError):
                return key
        # Read the previous schema's tuple-key representation when possible.
        if isinstance(key, str) and key.startswith("__tuple__:"):
            try:
                return tuple(cls._dejsonify(item) for item in json.loads(key[len("__tuple__:"):]))
            except (json.JSONDecodeError, TypeError, ValueError):
                return key
        return key

    @classmethod
    def _jsonify(cls, value: Any) -> Any:
        if isinstance(value, bytes):
            return {"__type__": "bytes", "base64": base64.b64encode(value).decode("ascii")}
        if isinstance(value, bytearray):
            return {"__type__": "bytes", "base64": base64.b64encode(bytes(value)).decode("ascii")}
        if isinstance(value, dict):
            return {cls._encode_key(key): cls._jsonify(item) for key, item in value.items()}
        if isinstance(value, list):
            return [cls._jsonify(item) for item in value]
        if isinstance(value, tuple):
            return {"__type__": "tuple", "items": [cls._jsonify(item) for item in value]}
        if isinstance(value, set):
            return {"__type__": "set", "items": [cls._jsonify(item) for item in value]}
        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        raise TypeError(f"Unsupported checkpoint value type: {type(value).__name__}")

    @classmethod
    def _dejsonify(cls, value: Any) -> Any:
        if isinstance(value, dict):
            marker = value.get("__type__")
            if marker == "bytes":
                return base64.b64decode(value.get("base64") or "")
            if marker == "tuple":
                return tuple(cls._dejsonify(item) for item in value.get("items", []))
            if marker == "set":
                return set(cls._dejsonify(item) for item in value.get("items", []))
            return {cls._decode_key(key): cls._dejsonify(item) for key, item in value.items()}
        if isinstance(value, list):
            return [cls._dejsonify(item) for item in value]
        return value

    def _load(self):
        if not self._path.exists():
            return
        try:
            payload = json.loads(self._path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError, OSError):
            return

        storage = defaultdict(lambda: defaultdict(dict))
        for thread_id, namespaces in (payload.get("storage") or {}).items():
            storage[thread_id] = defaultdict(
                dict,
                {
                    namespace: dict(self._dejsonify(entries))
                    for namespace, entries in namespaces.items()
                },
            )
        self.storage = storage
        self.writes = defaultdict(dict, self._dejsonify(payload.get("writes") or {}))
        self.blobs = defaultdict(None, self._dejsonify(payload.get("blobs") or {}))
        self._thread_updated_at = {
            str(key): float(value)
            for key, value in (payload.get("thread_updated_at") or {}).items()
        }
        if not self._thread_updated_at:
            fallback_time = self._path.stat().st_mtime
            self._thread_updated_at = {
                str(thread_id): fallback_time for thread_id in self.storage.keys()
            }


_CHECKPOINTER = None


def get_checkpointer():
    global _CHECKPOINTER
    if _CHECKPOINTER is None:
        path = os.getenv(
            "LANGGRAPH_CHECKPOINT_PATH",
            os.path.join(os.getcwd(), "data", "langgraph", "checkpoints", "langgraph-checkpoints.json"),
        )
        _CHECKPOINTER = FileBackedMemorySaver(path)
    return _CHECKPOINTER
