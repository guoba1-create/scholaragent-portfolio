"""
runtime_state_store.py — 工作流运行时状态持久化存储

本模块负责将工作流的运行时状态保存到 JSON 文件，用于：
1. 恢复执行：被中断/暂停的工作流可以从保存的状态恢复
2. 状态查询：Java 侧可以查询工作流的当前状态

每个工作流对应一个 JSON 文件，文件名为 {workflow_id}.json。
存储位置：data/langgraph/runtime-state/
可通过环境变量 LANGGRAPH_RUNTIME_STATE_DIR 覆盖。

与 checkpoint_store.py 的区别：
- checkpoint_store: 保存 LangGraph 图的检查点（节点级别的细粒度状态）
- runtime_state_store: 保存工作流的整体运行状态（粗粒度，用于恢复和查询）
"""

from __future__ import annotations

import json
import os
import threading
import time
import re
from pathlib import Path
from typing import Any

from .runtime_support import now_iso

# 默认 TTL（Time To Live）：7 天（秒）
# 超过 7 天的状态文件会被 cleanup_expired() 自动删除
DEFAULT_TTL_SECONDS = 7 * 24 * 3600


class RuntimeStateStore:
    """工作流运行时状态存储类。

    每个工作流的状态保存为一个独立的 JSON 文件。
    使用原子写入确保崩溃安全，使用线程锁确保线程安全。
    """

    def __init__(self, base_dir: str, ttl_seconds: int = DEFAULT_TTL_SECONDS):
        self.base_dir = Path(base_dir)         # 状态文件存储目录
        self.base_dir.mkdir(parents=True, exist_ok=True)  # 确保目录存在
        self._lock = threading.RLock()         # 读写锁
        self._ttl_seconds = ttl_seconds        # 文件过期时间

    def save(self, workflow_id: str, record: dict[str, Any]):
        """保存工作流状态到 JSON 文件。

        使用原子写入（先写 .tmp 临时文件，再 os.replace 替换），
        确保即使在写入过程中进程崩溃，也不会损坏原有文件。
        """
        with self._lock:
            payload = dict(record)
            payload["workflowId"] = workflow_id
            payload["updatedAt"] = now_iso()
            path = self._path(workflow_id)
            temp = path.with_suffix(".tmp")
            temp.write_text(json.dumps(payload, ensure_ascii=False, default=str, indent=2), encoding="utf-8")
            os.replace(temp, path)  # 原子替换

    def load(self, workflow_id: str) -> dict[str, Any] | None:
        """从 JSON 文件加载工作流状态。如果文件不存在，返回 None。"""
        with self._lock:
            path = self._path(workflow_id)
            if not path.exists():
                return None
            return json.loads(path.read_text(encoding="utf-8"))

    def delete(self, workflow_id: str):
        """删除指定工作流的状态文件。"""
        with self._lock:
            path = self._path(workflow_id)
            if path.exists():
                path.unlink()

    def cleanup_expired(self) -> int:
        """清理过期的状态文件（超过 TTL 的文件）。

        在程序启动时调用，删除超过 7 天的旧状态文件，释放磁盘空间。

        返回值：删除的文件数量
        """
        deleted = 0
        cutoff = time.time() - self._ttl_seconds
        with self._lock:
            for path in self.base_dir.glob("*.json"):
                try:
                    if path.stat().st_mtime < cutoff:
                        path.unlink()
                        deleted += 1
                except OSError:
                    pass
        return deleted

    def _path(self, workflow_id: str) -> Path:
        """根据 workflow_id 生成状态文件的完整路径。"""
        value = str(workflow_id or "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,100}", value):
            raise ValueError("Invalid workflow id for runtime state storage")
        return self.base_dir / f"{value}.json"


# 全局状态存储单例
_STATE_STORE = None


def get_state_store():
    """获取全局单例的状态存储实例。

    使用懒加载模式：首次调用时创建实例，后续调用直接返回缓存。
    存储路径优先使用环境变量 LANGGRAPH_RUNTIME_STATE_DIR，
    默认为当前工作目录下的 data/langgraph/runtime-state/。
    """
    global _STATE_STORE
    if _STATE_STORE is None:
        base_dir = os.getenv(
            "LANGGRAPH_RUNTIME_STATE_DIR",
            os.path.join(os.getcwd(), "data", "langgraph", "runtime-state"),
        )
        _STATE_STORE = RuntimeStateStore(base_dir)
    return _STATE_STORE
