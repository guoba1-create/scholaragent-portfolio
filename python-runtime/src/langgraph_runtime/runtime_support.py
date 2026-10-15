"""
runtime_support.py — 运行时辅助工具函数

本模块提供各种通用的工具函数，被其他模块广泛使用。
主要功能：
1. 时间和 ID 生成（now_iso, short_id）
2. 文本截断（preview）
3. 状态标准化（normalize_status）
4. 构建追踪记录（build_trace）
5. 构建工具观察记录（build_tool_observation）
6. 构建文档引用（evidence_to_document_ref）
7. 构建 Agent 间通信消息（build_handoff_messages）
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
import hashlib
import json
import re
import uuid


def now_iso() -> str:
    """获取当前 UTC 时间的 ISO 格式字符串。

    例如："2024-01-15T10:30:00.123456+00:00"
    用于记录事件的创建时间和完成时间。
    """
    return datetime.now(timezone.utc).isoformat()


def short_id(prefix: str) -> str:
    """生成带前缀的短唯一 ID。

    格式：{prefix}_{8位随机十六进制}
    例如："obs_a1b2c3d4", "step_e5f6g7h8"

    用于为各种记录（observation, step, message 等）生成唯一标识。
    """
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def preview(value: Any, limit: int = 300) -> str:
    """将任意值截断为指定长度的预览文本。

    用于在日志、追踪记录、事件摘要中显示数据的简短版本。
    超过限制的部分会被截断并添加 "..."。

    参数：
    - value: 要截断的值（任意类型，会先转为字符串）
    - limit: 最大字符数（默认 300）

    示例：
        preview("很长的文本..." * 100, 50)  → "很长的文本...很长的文本...很长的文本..."
        preview(None)  → ""
    """
    if value is None:
        return ""
    text = value if isinstance(value, str) else str(value)
    return text if len(text) <= limit else text[:limit] + "..."


def normalize_status(value: str | None, *, default: str = "FAILED") -> str:
    """将各种状态字符串标准化为统一的格式。

    不同模块可能使用不同的状态表示（如 "success", "COMPLETED", "ok"），
    此函数将它们统一映射为以下标准状态：
    - "SUCCESS": 成功完成
    - "WARNING": 有警告但可继续
    - "FAILED": 失败
    - "RUNNING": 正在运行
    - "WAITING_USER": 等待用户输入

    参数：
    - value: 原始状态字符串
    - default: 值为 None 时的默认状态

    示例：
        normalize_status("success")  → "SUCCESS"
        normalize_status("COMPLETED")  → "SUCCESS"
        normalize_status("fail")  → "FAILED"
    """
    if not value:
        return default
    upper = value.upper()
    if upper in {"SUCCESS", "COMPLETED"}:
        return "SUCCESS"
    if upper in {"WARNING", "WARN"}:
        return "WARNING"
    if upper in {"FAILED", "FAIL", "ERROR"}:
        return "FAILED"
    if upper in {"RUNNING", "PENDING", "PROCESSING"}:
        return "RUNNING"
    if upper in {"ASK_USER", "WAITING_USER"}:
        return "WAITING_USER"
    return upper


def build_trace(
    *,
    workflow_id: str,
    step_index: int,
    step_type: str,
    agent_name: str | None = None,
    tool_name: str | None = None,
    display_name: str | None = None,
    phase: str | None = None,
    input_data: Any = None,
    output_data: Any = None,
    status: str = "SUCCESS",
    latency_ms: int = 0,
    error_message: str | None = None,
    summary: str | None = None,
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """构建一条执行追踪记录（trace）。

    追踪记录用于记录工作流执行过程中的每一步操作。
    前端会在 "Trace" 面板中展示这些记录，方便用户了解执行过程。

    参数：
    - workflow_id: 工作流 ID
    - step_index: 步骤编号（从 1 开始递增）
    - step_type: 步骤类型（"NODE" / "TOOL" / "ROUTER" / "MONITOR" / "AGENT"）
    - agent_name: 仅真实 Agent 步骤填写；固定工作流节点保持为空
    - tool_name: 工具名称（如 "retrieve_evidence"）
    - display_name: 前端显示名称（优先使用，否则用 agent_name 或 tool_name）
    - phase: 阶段标识（如 "plan", "tool", "agent"）
    - input_data: 输入数据（会被截断为 500 字符的预览）
    - output_data: 输出数据（会被截断为 500 字符的预览）
    - status: 执行状态
    - latency_ms: 执行耗时（毫秒）
    - error_message: 错误信息（失败时）
    - summary: 摘要文本（前端展示）
    - metadata: 附加元数据

    返回值：trace 字典（会被添加到 state["traces"] 中）
    """
    output_preview = preview(output_data, 500)
    input_preview = preview(input_data, 500)
    normalized = normalize_status(status)
    trace = {
        "traceId": f"{workflow_id}_{step_type.lower()}_{(agent_name or tool_name or 'step')}_{step_index}",
        "workflowId": workflow_id,
        "stepIndex": step_index,
        "stepType": step_type.upper(),
        "agentName": agent_name,
        "toolName": tool_name,
        "displayName": display_name or agent_name or tool_name or step_type,
        "phase": phase,
        "summary": summary or output_preview or input_preview,
        "statusReason": "completed" if normalized == "SUCCESS" else (error_message or normalized.lower()),
        "inputPreview": input_preview,
        "outputPreview": output_preview,
        "fullInput": str(input_data) if input_data is not None else None,
        "fullOutput": str(output_data) if output_data is not None else None,
        "status": normalized,
        "latencyMs": latency_ms,
        "errorMessage": error_message,
        "createdAt": now_iso(),
        "completedAt": now_iso(),
        "metadata": metadata or {},
    }
    return trace


def build_tool_observation(
    *,
    workflow_id: str,
    step_id: str,
    tool_name: str,
    tool_result: dict[str, Any],
    summary: str | None = None,
    evidence: list[dict[str, Any]] | None = None,
    citations: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """构建一条工具调用观察记录（tool observation）。

    工具观察记录用于记录每次工具调用的详细信息。
    前端会在 "Diagnostics" 面板中展示这些记录。

    参数：
    - workflow_id: 工作流 ID
    - step_id: 步骤 ID（如 "step_a1b2c3d4"）
    - tool_name: 工具名称（如 "retrieve_evidence"）
    - tool_result: 工具调用的原始返回结果（含 success, data, errorMessage 等）
    - summary: 摘要文本（可选，不提供时自动生成）
    - evidence: 相关的证据片段列表（可选）
    - citations: 相关的文档引用列表（可选）

    返回值：observation 字典（会被添加到 state["tool_observations"] 中）
    """
    success = bool(tool_result.get("success"))
    data = tool_result.get("data")
    error_message = tool_result.get("errorMessage")
    latency_ms = int(tool_result.get("latencyMs") or 0)
    raw_text = _stable_json(data if data is not None else error_message)
    evidence_rows = [dict(item) for item in (evidence or [])[:5] if isinstance(item, dict)]
    citation_rows = [dict(item) for item in (citations or [])[:5] if isinstance(item, dict)]
    facts = [_observation_fact(item) for item in evidence_rows]
    facts = [item for item in facts if item]
    compressed_text = _observation_text(
        tool_name, "SUCCESS" if success else "FAILED", facts,
        summary or preview(data or error_message, 1000),
    )
    return {
        "observationId": short_id("obs"),
        "workflowId": workflow_id,
        "stepId": step_id,
        "toolName": (tool_name or "unknown_tool").strip().lower().replace("-", "_"),
        "status": "SUCCESS" if success else "FAILED",
        "summary": summary or ("Tool execution completed" if success else (error_message or "Tool execution failed")),
        "compressedText": compressed_text,
        "rawPayloadRef": tool_result.get("rawPayloadRef")
        or "payload:sha256:" + hashlib.sha256(raw_text.encode("utf-8")).hexdigest(),
        "rawPayloadPreview": preview(raw_text, 20000),
        "rawPayloadTruncated": len(raw_text) > 20000,
        "latencyMs": latency_ms,
        "rawTokenCount": _estimate_tokens(raw_text),
        "compressedTokenCount": _estimate_tokens(compressed_text),
        "facts": facts,
        "warnings": [] if facts or not success else ["Tool returned no structured evidence facts."],
        "citations": citation_rows,
        "evidence": evidence_rows,
        "createdAt": now_iso(),
    }


def _stable_json(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    except Exception:
        return str(value or "")


def _estimate_tokens(value: str) -> int:
    if not value:
        return 0
    cjk = len(re.findall(r"[\u3400-\u9fff]", value))
    return max(1, cjk + (len(value) - cjk + 3) // 4)


def _observation_fact(row: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "documentId", "chunkId", "title", "section", "sectionPath",
        "pageNumber", "pageStart", "pageEnd", "chunkIndex",
        "relevanceScore", "score", "url", "text", "content",
    )
    fact: dict[str, Any] = {}
    for key in keys:
        value = row.get(key)
        if value is None or str(value).strip() == "":
            continue
        if key in {"text", "content"}:
            value = preview(value, 600)
        fact[key] = value
    return fact


def _observation_text(tool_name: str, status: str, facts: list[dict[str, Any]], fallback: str) -> str:
    lines = [f"tool={(tool_name or 'unknown_tool').replace('-', '_')} status={status}"]
    for index, fact in enumerate(facts[:5], start=1):
        fields = [f"{key}={value}" for key, value in fact.items()]
        lines.append(f"[E{index}] " + "; ".join(fields))
    if not facts and fallback:
        lines.append("result=" + preview(fallback, 1500))
    return preview("\n".join(lines), 1800)


def evidence_to_document_ref(span: dict[str, Any], research_group_id: str | None = None) -> dict[str, Any]:
    """将 evidence span 转换为文档引用格式。

    evidence span 是从检索工具返回的原始数据，包含文本、标题、页码等。
    文档引用是前端 "Sources" 面板展示的格式，只保留关键的引用信息。

    参数：
    - span: evidence span 字典（含 documentId, title, section, pageNumber 等）
    - research_group_id: 研究组 ID（可选）

    返回值：文档引用字典（前端 Sources 面板使用）
    """
    return {
        "documentId": span.get("documentId"),
        "researchGroupId": research_group_id,
        "filename": span.get("title"),
        "title": span.get("title"),
        "section": span.get("section"),
        "pageNumber": span.get("pageNumber"),
        "chunkIndex": span.get("chunkIndex"),
        "score": span.get("relevanceScore"),
        "sourcePath": span.get("sourcePath"),
    }


def build_handoff_messages(
    *,
    workflow_id: str,
    parent_step_id: str,
    sender: str,
    receiver: str,
    task: str,
    response: dict[str, Any],
) -> list[dict[str, Any]]:
    """构建 Agent 间的委托通信消息（handoff messages）。

    当一个 Agent（sender）将任务委托给另一个 Agent（receiver）时，
    会生成一对消息：请求消息和响应消息。
    这些消息会被记录在 state["messages"] 中，用于追踪 Agent 间的协作过程。

    参数：
    - workflow_id: 工作流 ID
    - parent_step_id: 父步骤 ID（发起委托的步骤）
    - sender: 发送方 Agent 名称
    - receiver: 接收方 Agent 名称
    - task: 委托的任务描述
    - response: 接收方的执行结果

    返回值：包含两个消息字典的列表（请求 + 响应）
    """
    request_id = short_id("msg")
    response_id = short_id("msg")
    created_at = now_iso()
    return [
        {
            "messageId": request_id,
            "workflowId": workflow_id,
            "parentStepId": parent_step_id,
            "sender": sender,
            "receiver": receiver,
            "messageType": "HANDOFF_REQUEST",
            "task": task,
            "payload": {"task": task},
            "status": "COMPLETED",
            "createdAt": created_at,
            "completedAt": now_iso(),
        },
        {
            "messageId": response_id,
            "workflowId": workflow_id,
            "parentStepId": parent_step_id,
            "sender": receiver,
            "receiver": sender,
            "messageType": "HANDOFF_RESPONSE",
            "response": response,
            "status": "COMPLETED",
            "createdAt": created_at,
            "completedAt": now_iso(),
        },
    ]
