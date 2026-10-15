"""
mcp_tool_client.py — MCP 协议工具调用客户端

本模块通过 MCP（Model Context Protocol）协议调用 Java 侧的工具。
MCP 是一种标准化的工具调用协议，比直接 HTTP 调用更规范。

通信方式：SSE（Server-Sent Events）
- Python 侧通过 SSE 长连接接收 Java 侧的响应
- 请求通过 HTTP POST 发送，响应通过 SSE 事件流返回

当前这是 Python 调用 Java Agent Tools 的唯一客户端，避免维护两套工具协议。

调用流程：
1. 建立 SSE 连接（connect）→ 获取 session_id
2. 发送 JSON-RPC 请求（call）→ 通过 HTTP POST 发送到 /mcp/message
3. 等待 SSE 响应 → 通过事件流接收结果
4. 解析响应 → 返回标准化的结果字典
"""

import json
import threading
import uuid
from typing import Any

import requests

from . import config

# 全局 MCP 会话单例（线程安全的懒加载）
_session: "McpSseSession | None" = None
_session_lock = threading.Lock()


class McpSseSession:
    """MCP SSE 会话管理类 — 管理与 Java MCP Server 的 SSE 连接。

    职责：
    1. 建立和维护 SSE 长连接
    2. 发送 JSON-RPC 请求
    3. 接收和分发 SSE 响应
    4. 管理待处理的请求（pending requests）
    """

    def __init__(self, base_url: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")  # MCP Server 的基础 URL
        self.timeout = timeout                  # 请求超时时间（秒）
        self.session_id: str | None = None      # SSE 会话 ID（由服务端分配）
        self._pending: dict[str, dict] = {}     # 待处理的请求：{req_id: {"result": ..., "event": Event}}
        self._connected = False                 # 是否已连接
        self._stream_thread: threading.Thread | None = None  # SSE 监听线程
        self._stream_resp: requests.Response | None = None   # SSE 响应对象
        self._headers = {"X-Agent-Runtime-Token": config.AGENT_INTERNAL_TOKEN}

    def connect(self) -> bool:
        """建立 SSE 连接并开始监听响应。

        连接流程：
        1. GET 请求到 /mcp/sse 建立 SSE 连接
        2. 启动后台线程监听 SSE 事件
        3. 等待服务端返回 "endpoint" 事件（包含 session_id）
        4. 连接成功后，后续的请求都通过此 session 发送

        返回值：连接是否成功
        """
        if self._connected:
            return True
        try:
            url = f"{self.base_url}/mcp/sse"
            # 建立 SSE 长连接（stream=True 表示不立即下载响应体）
            self._stream_resp = requests.get(url, headers=self._headers, stream=True, timeout=None)
            self._stream_resp.raise_for_status()
            self._connected = True
            # 启动后台线程监听 SSE 事件
            self._stream_thread = threading.Thread(target=self._listen, daemon=True)
            self._stream_thread.start()
            # 等待服务端返回 session_id（最多等 5 秒）
            import time
            for _ in range(50):
                if self.session_id:
                    return True
                time.sleep(0.1)
            return False
        except Exception:
            self._connected = False
            return False

    def _listen(self):
        """后台线程：持续监听 SSE 事件流。

        SSE 事件格式：
        - event: {事件类型}
        - data: {数据内容}

        空行表示一个事件的结束。
        """
        try:
            event_type = None
            for line in self._stream_resp.iter_lines(decode_unicode=True):
                if not line:
                    # 空行：事件结束，重置 event_type
                    event_type = None
                    continue
                if line.startswith("event: "):
                    # 事件类型行
                    event_type = line[7:].strip()
                    continue
                if line.startswith("data: "):
                    # 数据行
                    data = line[6:]
                    self._handle_event(event_type, data)
        except Exception:
            pass
        finally:
            self._connected = False

    def _handle_event(self, event_type: str | None, data: str):
        """处理接收到的 SSE 事件。

        事件类型：
        - "endpoint": 服务端返回 session_id（连接建立时）
        - "message": 服务端返回 JSON-RPC 响应（工具调用结果）
        """
        if event_type == "endpoint":
            # endpoint 事件：data 格式为 "/mcp/message?sessionId=xxx"
            if "sessionId=" in data:
                self.session_id = data.split("sessionId=")[-1]
        elif event_type == "message":
            # message 事件：data 是 JSON-RPC 响应
            try:
                msg = json.loads(data)
                msg_id = msg.get("id")
                # 找到对应的待处理请求，设置结果并唤醒等待线程
                if msg_id is not None and msg_id in self._pending:
                    self._pending[msg_id]["result"] = msg
                    self._pending[msg_id]["event"].set()
            except json.JSONDecodeError:
                pass

    def call(self, method: str, params: dict | None = None) -> dict:
        """发送 JSON-RPC 请求并等待响应。

        这是 MCP 协议的核心调用方法。
        请求通过 HTTP POST 发送，响应通过 SSE 事件流异步接收。

        参数：
        - method: MCP 方法名（如 "tools/call", "tools/list"）
        - params: 方法参数（可选）

        返回值：JSON-RPC 响应字典

        流程：
        1. 构造 JSON-RPC 请求（含唯一 ID）
        2. 通过 HTTP POST 发送到 /mcp/message?sessionId=xxx
        3. 等待 SSE 事件流返回匹配 ID 的响应（超时返回错误）
        """
        # 如果未连接，先建立连接
        if not self._connected or not self.session_id:
            if not self.connect():
                return {"error": "Cannot connect to MCP server"}

        # 构造 JSON-RPC 2.0 请求
        req_id = str(uuid.uuid4())
        payload = {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": method,
            "params": params or {},
        }

        # 注册待处理请求（使用 Event 进行线程间通信）
        event = threading.Event()
        self._pending[req_id] = {"result": None, "event": event}

        try:
            # 通过 HTTP POST 发送请求
            url = f"{self.base_url}/mcp/message?sessionId={self.session_id}"
            resp = requests.post(url, json=payload, headers=self._headers, timeout=self.timeout)
            resp.raise_for_status()
        except Exception as e:
            del self._pending[req_id]
            return {"error": str(e)}

        # 等待 SSE 响应（最多等待 self.timeout 秒）
        if event.wait(timeout=self.timeout):
            result = self._pending.pop(req_id, {}).get("result", {})
            return result
        else:
            # 超时：清理待处理请求
            self._pending.pop(req_id, None)
            return {"error": "Timeout waiting for MCP response"}

    def close(self):
        """关闭 SSE 连接。"""
        if self._stream_resp:
            self._stream_resp.close()
        self._connected = False


def _get_session() -> McpSseSession:
    """获取全局单例的 MCP 会话实例（线程安全的懒加载）。"""
    global _session
    if _session is None:
        with _session_lock:
            # 双重检查锁定（Double-Checked Locking）
            if _session is None:
                base_url = config.MCP_SERVER_URL
                _session = McpSseSession(base_url)
    return _session


def call_tool(tool_name: str, workflow_id: str, user_id: str,
              research_group_id: str, agent_name: str,
              tool_input: dict = None,
              **kwargs) -> dict:
    """通过 MCP 协议调用 Java 侧的工具。

    这是工具调用的主入口函数。所有工具（如 retrieve_evidence、list_papers 等）
    都通过此函数调用。

    参数：
    - tool_name: 工具名称（如 "retrieve_evidence"）
    - workflow_id: 工作流 ID
    - user_id: 用户 ID
    - research_group_id: 研究组 ID
    - agent_name: Agent 名称
    - tool_input: 工具特定的输入参数（如 {"query": "...", "topK": 5}）

    返回值：标准化的工具结果字典
    - success: 是否成功（bool）
    - data: 成功时的返回数据
    - errorCode: 失败时的错误代码
    - errorMessage: 失败时的错误描述
    """
    # 将工作流上下文信息注入到工具参数中（Java 侧需要这些信息）
    arguments = dict(tool_input or {})
    arguments["__workflowId"] = workflow_id
    arguments["__userId"] = user_id
    arguments["__researchGroupId"] = research_group_id
    arguments["__agentName"] = agent_name
    if kwargs.get("step_id"):
        arguments["__stepId"] = kwargs["step_id"]
    if kwargs.get("parent_step_id"):
        arguments["__parentStepId"] = kwargs["parent_step_id"]

    # 通过 MCP 协议调用工具
    session = _get_session()
    result = session.call("tools/call", {
        "name": tool_name,
        "arguments": arguments,
    })

    # 处理 JSON-RPC 级别的错误
    if "error" in result and isinstance(result["error"], dict):
        return {
            "success": False,
            "errorCode": result["error"].get("code", "MCP_ERROR"),
            "errorMessage": result["error"].get("message", str(result["error"])),
        }

    # 处理工具级别的错误（工具执行失败但 JSON-RPC 调用成功）
    rpc_result = result.get("result", result)
    if "isError" in rpc_result and rpc_result["isError"]:
        content = rpc_result.get("content", [])
        text = content[0].get("text", "{}") if content else "{}"
        try:
            err = json.loads(text)
        except (json.JSONDecodeError, TypeError):
            err = {"error": text}
        return {
            "success": False,
            "errorCode": err.get("code", "TOOL_ERROR"),
            "errorMessage": err.get("error", text),
        }

    # 成功：提取工具返回的数据
    content = rpc_result.get("content", [])
    text = content[0].get("text", "null") if content else "null"
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        data = text

    return {
        "success": True,
        "data": data,
        "rawPayloadRef": rpc_result.get("rawPayloadRef"),
    }


def list_tools() -> list[dict]:
    """列出 MCP Server 上所有可用的工具。

    通过 MCP 协议的 "tools/list" 方法获取工具列表。
    返回值：工具描述字典列表
    """
    session = _get_session()
    result = session.call("tools/list")
    return result.get("result", {}).get("tools", [])


def check_health() -> dict:
    """检查 MCP Server 是否可达。

    尝试建立 SSE 连接，如果成功则返回 {"status": "UP"}，否则返回 {"status": "DOWN"}。
    """
    try:
        session = _get_session()
        if session.connect():
            return {"status": "UP", "transport": "mcp-sse"}
        return {"status": "DOWN", "error": "Cannot establish SSE connection"}
    except Exception as e:
        return {"status": "DOWN", "error": str(e)}
