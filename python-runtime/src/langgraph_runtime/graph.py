"""
graph.py — ReAct 图定义（推理-行动循环）

本模块定义了 ReAct（Reasoning + Acting）图的完整结构。

什么是 ReAct？
ReAct 是一种 AI Agent 的执行模式，核心思想是"想一步、做一步"：
1. Reason（推理）：LLM 分析当前状态，决定下一步做什么
2. Act（行动）：执行 LLM 决定的动作（调用工具、委托其他 Agent 等）
3. Observe（观察）：获取动作的结果，更新状态
4. 循环：回到步骤 1，直到任务完成

图结构：
    reason（推理节点）→ 根据动作类型路由到：
        ├── tool（工具调用）→ monitor（质量监控）→ reason（循环）
        ├── handoff（委托其他 Agent）→ monitor → reason（循环）
        ├── finish（完成任务）→ END
        └── ask_user（询问用户）→ END

与 workflow_graph.py 的区别：
- graph.py: ReAct 模式（LLM 驱动的推理-行动循环，灵活但不可预测）
- workflow_graph.py: Workflow 模式（预定义的管线流程，确定性强）
"""

import copy
import json
import time
import uuid
from typing import TypedDict, Any
from langgraph.graph import StateGraph, END

from .checkpoint_store import get_checkpointer
from .profiles import get_profile, AgentProfile
from .prompts import (
    DECISION_SYSTEM_PROMPT,
    MONITOR_SYSTEM_PROMPT,
    build_profile_prompt,
    build_decision_context,
    build_monitor_context,
)
from .llm_client import chat_json
from .runtime_support import build_handoff_messages, preview
from .mcp_tool_client import call_tool


# ---------------------------------------------------------------------------
# 状态定义
# ---------------------------------------------------------------------------

class ReActState(TypedDict, total=False):
    """ReAct 图的状态定义。

    所有图节点共享此状态。total=False 表示所有字段都是可选的。

    字段分组：
    - 标识字段：workflow_id, profile, user_id, research_group_id, conversation_id
    - 输入字段：query（用户问题）, route_decision（路由决策）, context_budget（上下文预算）
    - 执行状态：step_index（当前步骤）, tool_call_count（工具调用次数）, status（运行状态）
    - 工作记忆：working_memory（收集的证据、验证结果等中间数据）
    - 决策相关：last_action（上一次 LLM 决策的动作）, should_continue（是否继续）
    - 输出字段：final_answer（最终回答）
    - 可观测性：observations（工具调用记录）, warnings（警告）, react_steps（步骤记录）, messages（Agent 间消息）
    """
    workflow_id: str           # 工作流唯一 ID
    profile: str               # Agent profile id, such as "deep_research_react"
    user_id: str               # 用户 ID
    research_group_id: str     # 研究组 ID（用于多租户文档隔离）
    conversation_id: str       # 会话 ID
    query: str                 # 用户的原始问题
    route_decision: dict       # Java 侧的路由决策
    context_budget: dict       # 上下文预算配置
    prompt_context: str        # 经过预算裁剪后的上下文文本
    step_index: int            # 当前步骤编号（从 0 开始）
    tool_call_count: int       # 已执行的工具调用次数（用于预算控制）
    observations: list[dict]   # 工具调用观察记录列表
    working_memory: dict       # 工作记忆（收集的证据、验证结果等中间数据）
    last_action: dict          # 上一次 LLM 决策的动作（含 actionType, actionName, arguments）
    final_answer: str          # 最终回答（Markdown 格式）
    status: str                # 运行状态（RUNNING / COMPLETED / FAILED / ASK_USER）
    should_continue: bool      # 是否应该继续执行（monitor 节点设置）
    warnings: list[str]        # 警告信息列表
    messages: list[dict]       # Agent 间通信消息列表（handoff 场景）
    react_steps: list[dict]    # ReAct 步骤记录列表（用于追踪和调试）
    sequence: int              # 事件序列号（单调递增）
    handoff_depth: int         # 委托深度（防止无限委托，最大 2 层）
    research_plan: dict        # DeepResearch 显式计划（goal, plan_steps, rationale）
    research_evaluation: dict  # DeepResearch 覆盖度/质量评估
    deep_replan_count: int     # DeepResearch 已重规划次数
    max_deep_replan: int       # DeepResearch 最大重规划次数
    deep_handoff_count: int    # DeepResearch 已委托次数
    deep_handoff_history: list[dict]  # DeepResearch 专属 handoff 记录


def _new_state(
    workflow_id: str,
    profile: str,
    user_id: str,
    research_group_id: str,
    query: str,
    conversation_id: str | None = None,
    route_decision: dict | None = None,
    context_budget: dict | None = None,
) -> ReActState:
    """创建全新的 ReActState。

    在以下场景被调用：
    1. gRPC 首次收到 RunWorkflow（react 引擎）请求时
    2. handoff（委托）时为子 Agent 创建新状态

    返回值：初始化完成的状态字典，所有列表为空，状态为 RUNNING。
    """
    return {
        "workflow_id": workflow_id,
        "profile": profile,
        "user_id": user_id,
        "research_group_id": research_group_id,
        "conversation_id": conversation_id or workflow_id,
        "query": query,
        "route_decision": route_decision or {},
        "context_budget": context_budget or {},
        "prompt_context": (context_budget or {}).get("promptContext", ""),
        "step_index": 0,
        "tool_call_count": 0,
        "observations": [],
        "working_memory": {
            "atomic_claims": [],
            "evidence_spans": [],
            "verification_results": [],
            "draft_answer": "",
            "warnings": [],
            "custom_data": {},
        },
        "last_action": {},
        "final_answer": "",
        "status": "RUNNING",
        "should_continue": True,
        "warnings": [],
        "messages": [],
        "react_steps": [],
        "sequence": 0,
        "handoff_depth": 0,
        "research_plan": {"goal": query, "plan_steps": [], "rationale": "", "status": "NEW"},
        "research_evaluation": {},
        "deep_replan_count": 0,
        "max_deep_replan": 2,
        "deep_handoff_count": 0,
        "deep_handoff_history": [],
    }


# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------

def _next_seq(state: ReActState) -> int:
    """获取下一个事件序列号（单调递增）。"""
    s = state.get("sequence", 0) + 1
    state["sequence"] = s
    return s


def _record_step(state: ReActState, step: dict):
    """记录一个 ReAct 步骤（添加到 react_steps 列表末尾）。"""
    state.setdefault("react_steps", []).append(step)


def _record_observation(state: ReActState, obs: dict):
    """记录一个工具调用观察（添加到 observations 列表末尾）。"""
    state.setdefault("observations", []).append(obs)


def _build_failure_observation(target: str | None, code: str, message: str) -> dict[str, Any]:
    """构建一个失败的观察记录（用于 handoff 失败等场景）。"""
    return {
        "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
        "success": False,
        "tool_name": target or "handoff",
        "summary": message,
        "data_preview": "",
        "data": {"error": message},
        "error": message,
        "error_code": code,
        "latency_ms": 0,
    }


DEEP_RESEARCH_EXECUTABLE_TOOLS = {
    "get_library_summary",
    "list_papers",
    "diagnose_library",
    "memory_recall",
    "query_rewrite",
    "retrieve_evidence",
    "read_document",
    "judge_evidence_quality",
}

DEEP_RESEARCH_EXTERNAL_AGENT = "external_scholar_scout_react"

DEEP_RESEARCH_EXTERNAL_SIGNALS = (
    "最新", "近几年", "近年", "趋势", "研究空白", "空白", "sota", "state of the art",
    "benchmark", "survey", "综述", "调研", "recent", "latest", "trend", "gap",
)


def _is_deep_research(state: ReActState) -> bool:
    """DeepResearch uses a dedicated PER branch inside the ReAct graph."""
    return state.get("profile") == "deep_research_react"


def _needs_external_search(query: str) -> bool:
    lower = (query or "").lower()
    return any(signal in lower for signal in DEEP_RESEARCH_EXTERNAL_SIGNALS)


def _safe_text(value: Any, max_len: int = 4000) -> str:
    if value is None:
        return ""
    text = str(value)
    return text if len(text) <= max_len else text[:max_len] + "..."


def _safe_json(value: Any, max_len: int = 12000) -> str:
    try:
        text = json.dumps(value, ensure_ascii=False)
    except Exception:
        text = str(value)
    return text if len(text) <= max_len else text[:max_len] + "..."


def _normalize_plan_step(raw: Any, index: int, *, source: str, allowed_tools: set[str]) -> dict[str, Any]:
    """Normalize LLM/fallback DeepResearch plan steps into a stable schema."""
    item = raw if isinstance(raw, dict) else {"description": str(raw)}
    query = _safe_text(item.get("search_query") or item.get("searchQuery") or item.get("query") or item.get("description"))
    tool_hint = str(item.get("tool_hint") or item.get("toolHint") or "retrieve_evidence").strip()
    if tool_hint not in DEEP_RESEARCH_EXECUTABLE_TOOLS:
        tool_hint = "retrieve_evidence"
    if allowed_tools and tool_hint not in allowed_tools:
        tool_hint = "retrieve_evidence" if "retrieve_evidence" in allowed_tools else next(iter(sorted(allowed_tools)), "retrieve_evidence")
    return {
        "step_id": str(item.get("step_id") or item.get("stepId") or f"dr-{index}"),
        "description": _safe_text(item.get("description") or query or f"DeepResearch step {index}", 800),
        "tool_hint": tool_hint,
        "search_query": query,
        "expected_evidence": _safe_text(item.get("expected_evidence") or item.get("expectedEvidence") or "", 800),
        "requires_external_search": bool(item.get("requires_external_search") or item.get("requiresExternalSearch") or tool_hint in {"search_papers", "web_search"}),
        "status": "PENDING",
        "attempts": int(item.get("attempts") or 0),
        "evidence_ids": [],
        "source": source,
    }


def _fallback_deep_research_plan(state: ReActState, allowed_tools: set[str]) -> list[dict[str, Any]]:
    """Deterministic fallback plan when LLM planning fails or returns an invalid plan."""
    query = state.get("query", "")
    raw_steps: list[dict[str, Any]] = [
        {
            "step_id": "dr-1",
            "description": "Inspect the current local literature library before researching.",
            "tool_hint": "get_library_summary" if "get_library_summary" in allowed_tools else "list_papers",
            "search_query": query,
        },
        {
            "step_id": "dr-2",
            "description": "Retrieve local evidence related to the research topic.",
            "tool_hint": "retrieve_evidence",
            "search_query": query,
        },
        {
            "step_id": "dr-3",
            "description": "Judge whether collected local evidence is sufficient for the topic.",
            "tool_hint": "judge_evidence_quality",
            "search_query": query,
        },
    ]
    return [
        _normalize_plan_step(step, idx + 1, source="fallback", allowed_tools=allowed_tools)
        for idx, step in enumerate(raw_steps)
    ]


def _deep_result_count(tool_name: str, data: Any) -> int:
    """Estimate whether a tool result contains useful payload for plan-step status."""
    if data is None:
        return 0
    data = _tool_payload(data)
    if isinstance(data, list):
        return len(data)
    if not isinstance(data, dict):
        return 1 if str(data).strip() else 0
    if tool_name == "retrieve_evidence":
        spans = data.get("evidenceSpans") or data.get("evidence") or []
        return len(spans) if isinstance(spans, list) else 0
    if tool_name == "search_papers":
        papers = data.get("papers") or []
        return len(papers) if isinstance(papers, list) else int(bool(data.get("totalResults")))
    if tool_name == "web_search":
        results = data.get("results") or data.get("items") or []
        return len(results) if isinstance(results, list) else int(bool(data.get("totalResults")))
    if tool_name == "get_library_summary":
        return 1 if data.get("summary") else 0
    if tool_name == "list_papers":
        papers = data.get("papers") or []
        return len(papers) if isinstance(papers, list) else int(data.get("paperCount") or 0)
    if tool_name == "judge_evidence_quality":
        return 1 if data.get("supportStatus") or data.get("judgment") else 0
    if tool_name == "query_rewrite":
        return 1 if data.get("rewrittenQuery") else 0
    if tool_name == "memory_recall":
        return 1 if data else 0
    if tool_name == "read_document":
        return 1 if data.get("text") or data.get("content") or data.get("result") else 0
    return 1 if data else 0


def _tool_payload(data: Any) -> Any:
    """Unwrap Java tool outputs that store the real payload under result."""
    if isinstance(data, dict) and isinstance(data.get("result"), dict):
        return data.get("result")
    return data


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        if value is None:
            return default
        return int(value)
    except Exception:
        return default


def _deep_handoff_already(state: ReActState, target_profile: str) -> bool:
    return any(
        item.get("target") == target_profile
        for item in state.get("deep_handoff_history", [])
        if isinstance(item, dict)
    )


def _library_inventory_empty(state: ReActState) -> bool:
    """Detect a real library/binding problem from working_memory and recent observations."""
    wm = state.get("working_memory", {})
    custom = wm.get("custom_data", {}) if isinstance(wm.get("custom_data"), dict) else {}
    if custom.get("library_inventory_observed"):
        count = _safe_int(custom.get("library_paper_count"), 0)
        if count <= 0:
            return True

    text_chunks: list[str] = []
    for key in ("library_summary", "library_status", "library_diagnostics"):
        value = custom.get(key)
        if value:
            text_chunks.append(str(value))
    for obs in state.get("observations", [])[-8:]:
        if not isinstance(obs, dict):
            continue
        if obs.get("tool_name") in {"list_papers", "get_library_summary", "retrieve_evidence"}:
            text_chunks.append(str(obs.get("summary", "")))
            text_chunks.append(str(obs.get("data_preview", "")))

    combined = " ".join(text_chunks).lower()
    empty_signals = (
        "no uploaded document",
        "no uploaded documents",
        "no document manifests",
        "paper count=0",
        "papers=[]",
        "total=0",
        "匹配数量=0",
        "文献库为空",
        "没有上传",
    )
    return any(signal in combined for signal in empty_signals)


def _should_deep_diagnose_library(state: ReActState) -> bool:
    if any(obs.get("tool_name") == "diagnose_library" for obs in state.get("observations", [])):
        return False
    return _library_inventory_empty(state)


def _gap_requires_external(gap: Any) -> bool:
    if not isinstance(gap, dict):
        return False
    tool = str(gap.get("suggested_tool") or gap.get("suggestedTool") or "").lower()
    reason = " ".join(str(gap.get(k, "")) for k in ("reason", "suggested_query", "suggestedQuery")).lower()
    return tool in {"search_papers", "web_search"} or _needs_external_search(reason)


def _should_deep_handoff_external(state: ReActState) -> bool:
    if _deep_handoff_already(state, DEEP_RESEARCH_EXTERNAL_AGENT):
        return False
    if state.get("deep_handoff_count", 0) >= 2:
        return False
    wm = state.get("working_memory", {})
    custom = wm.get("custom_data", {}) if isinstance(wm.get("custom_data"), dict) else {}
    if custom.get("external_results"):
        return False

    evaluation = state.get("research_evaluation") or {}
    gaps = evaluation.get("gaps") if isinstance(evaluation.get("gaps"), list) else []
    if any(_gap_requires_external(gap) for gap in gaps):
        return True
    return bool(evaluation.get("external_required"))


def _normalize_external_results(items: Any) -> list[dict[str, Any]]:
    """Normalize and deduplicate child-Agent results before parent-state merge."""
    if isinstance(items, dict):
        rows = items.get("papers") or items.get("results") or items.get("items") or [items]
    elif isinstance(items, list):
        rows = items
    elif items:
        rows = [{"text": str(items)}]
    else:
        rows = []

    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for raw in rows:
        if not isinstance(raw, dict):
            raw = {"text": str(raw)}
        title = _safe_text(raw.get("title") or raw.get("name") or "Untitled external source", 500)
        url = _safe_text(raw.get("url") or raw.get("link") or raw.get("paperUrl") or "", 1200)
        snippet = _safe_text(
            raw.get("abstract") or raw.get("abstract_") or raw.get("snippet")
            or raw.get("text") or raw.get("content") or "", 1800)
        key = (url or title).strip().lower()
        if not key or key in seen:
            continue
        seen.add(key)
        normalized.append({
            "citationId": f"X{len(normalized) + 1}",
            "sourceType": "EXTERNAL",
            "title": title,
            "year": raw.get("year") or raw.get("publicationYear"),
            "venue": raw.get("venue") or raw.get("journal"),
            "url": url,
            "snippet": snippet,
            "relevanceReason": _safe_text(raw.get("reason") or raw.get("relevanceReason") or "", 600),
            "provider": raw.get("provider") or raw.get("source") or "external_scholar_scout",
        })
        if len(normalized) >= 12:
            break
    return normalized


def _build_deep_tool_args(tool_name: str, step: dict[str, Any], state: ReActState, wm: dict) -> dict[str, Any]:
    """Build tool arguments from a DeepResearch plan step."""
    query = _safe_text(step.get("search_query") or step.get("description") or state.get("query", ""))
    if tool_name in {"get_library_summary", "list_papers", "diagnose_library"}:
        return {}
    if tool_name == "memory_recall":
        return {"query": query or state.get("query", ""), "mode": "structured"}
    if tool_name == "query_rewrite":
        return {"originalQuery": query or state.get("query", ""), "context": state.get("prompt_context", "")[:1500]}
    if tool_name == "retrieve_evidence":
        return {"query": query or state.get("query", ""), "topK": 8}
    if tool_name == "search_papers":
        return {"query": query or state.get("query", ""), "limit": 8}
    if tool_name == "web_search":
        return {"query": query or state.get("query", ""), "limit": 5, "language": "auto"}
    if tool_name == "read_document":
        args: dict[str, Any] = {}
        for key in ("documentId", "chunkIndex", "pageStart", "pageEnd"):
            if key in step:
                args[key] = step[key]
        if not args:
            args["query"] = query or state.get("query", "")
        return args
    if tool_name == "judge_evidence_quality":
        evidence_text = "\n\n".join(
            preview(span.get("text", ""), 500)
            for span in wm.get("evidence_spans", [])[-5:]
            if isinstance(span, dict)
        )
        external_text = "\n\n".join(
            preview(item.get("abstract_") or item.get("abstract") or item.get("snippet") or item.get("text") or "", 300)
            for item in wm.get("custom_data", {}).get("external_results", [])[-5:]
            if isinstance(item, dict)
        )
        return {
            "claim": query or state.get("query", ""),
            "evidence": (evidence_text + "\n\n" + external_text).strip()[:4000],
        }
    return {"query": query or state.get("query", "")}


def _record_deep_step(state: ReActState, phase: str, *, summary: str, payload: dict | None = None,
                      status: str = "SUCCESS", latency_ms: int = 0):
    """Record a DeepResearch PER step in the same ReActSteps list used by the UI."""
    _record_step(
        state,
        {
            "stepId": f"step_{uuid.uuid4().hex[:8]}",
            "workflowId": state.get("workflow_id", ""),
            "agentProfile": state.get("profile", "deep_research_react"),
            "stepIndex": state.get("step_index", 0),
            "phase": phase,
            "reasoningSummary": summary,
            "observation": payload or {},
            "status": status,
            "latencyMs": latency_ms,
        },
    )


# ---------------------------------------------------------------------------
# 图节点（Nodes）
# ---------------------------------------------------------------------------
# 每个节点是一个 Python 函数，接收 state，返回 dict（自动合并到 state 中）。
# 节点是 ReAct 循环的核心执行单元。

def reason_node(state: ReActState) -> dict:
    """推理节点 — 调用 LLM 决定下一步动作。

    这是 ReAct 循环的"大脑"。每次执行时：
    1. 根据 AgentProfile 构造系统提示词
    2. 将当前状态（查询、工作记忆、最近观察等）构造为上下文
    3. 调用 LLM，让 LLM 决定下一步做什么
    4. 解析 LLM 的 JSON 回答，提取动作类型和参数

    LLM 返回的动作类型：
    - TOOL: 调用工具（如 retrieve_evidence）
    - HANDOFF: 委托给其他 Agent
    - ASK_USER: 询问用户更多信息
    - FINISH: 完成任务，生成最终回答

    返回值：{"last_action": {...}} — 包含 LLM 决策的动作信息
    """
    # 获取当前 Agent 的配置
    prof = get_profile(state.get("profile", "deep_research_react"))
    if prof is None:
        prof = get_profile("deep_research_react")

    # 构造提示词
    profile_prompt = build_profile_prompt(prof)
    context = build_decision_context(state)
    system_msg = DECISION_SYSTEM_PROMPT.format(profile_prompt=profile_prompt)

    step_start = time.time()
    step_id = f"step_{uuid.uuid4().hex[:8]}"
    step_index = state.get("step_index", 0)

    # 调用 LLM 获取决策
    try:
        result = chat_json(system_msg, context)
    except Exception as e:
        # LLM 调用失败时，使用兜底决策：直接完成任务
        result = {
            "reasoningSummary": f"LLM call failed: {e}",
            "action": {"actionType": "finish", "actionName": "", "arguments": {}},
            "confidence": 0.0,
        }

    latency_ms = int((time.time() - step_start) * 1000)

    # 解析 LLM 返回的动作
    action = result.get("action", {})
    action_type = action.get("actionType", "finish").upper()  # 动作类型（大写）
    action_name = action.get("actionName", "")                  # 动作名称（工具名或 Agent 名）
    action_args = action.get("arguments", {})                   # 动作参数
    reasoning = result.get("reasoningSummary", "")              # 推理摘要
    confidence = result.get("confidence", 0.5)                  # 置信度

    last_action = {
        "actionType": action_type,
        "actionName": action_name,
        "arguments": action_args,
        "reasoningSummary": reasoning,
        "confidence": confidence,
    }

    # 记录步骤（用于追踪和调试）
    step = {
        "stepId": step_id,
        "workflowId": state.get("workflow_id", ""),
        "agentProfile": state.get("profile", ""),
        "stepIndex": step_index,
        "phase": "REASON",
        "reasoningSummary": reasoning,
        "action": {
            "actionType": action_type,
            "actionName": action_name,
            "arguments": action_args,
            "reasoningSummary": reasoning,
            "confidence": confidence,
        },
        "status": "SUCCESS",
        "latencyMs": latency_ms,
    }
    _record_step(state, step)

    return {"last_action": last_action}


def tool_node(state: ReActState) -> dict:
    """工具调用节点 — 执行 LLM 决策的工具调用。

    当 reason_node 的决策是 "TOOL" 时，图流转到此节点。
    执行流程：
    1. 从 last_action 中提取工具名称和参数
    2. 检查工具是否在 Agent 的允许列表中
    3. 通过 MCP 协议调用 Java 侧的工具
    4. 记录工具调用结果（observation）
    5. 更新工作记忆（working_memory）

    返回值：更新后的 observations, working_memory, tool_call_count, react_steps
    """
    action = state.get("last_action", {})
    tool_name = action.get("actionName", "")
    tool_args = action.get("arguments", {})
    wf_id = state.get("workflow_id", "")
    user_id = state.get("user_id", "")
    group_id = state.get("research_group_id", "")
    agent_name = state.get("profile", "deep_research_react")
    prof = get_profile(agent_name)

    step_index = state.get("step_index", 0)
    step_id = f"step_{uuid.uuid4().hex[:8]}"

    # Check tool allowlist
    if prof and not prof.is_tool_allowed(tool_name):
        obs = {
            "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
            "success": False,
            "tool_name": tool_name,
            "summary": f"Tool '{tool_name}' is not allowed for profile '{agent_name}'",
            "data_preview": "",
            "error": "TOOL_NOT_ALLOWED",
            "error_code": "TOOL_NOT_ALLOWED",
            "latency_ms": 0,
        }
        _record_observation(state, obs)
        new_tc = state.get("tool_call_count", 0)
        return {
            "observations": state["observations"],
            "tool_call_count": new_tc,
        }

    # Call tool via MCP protocol (tool_name used directly)
    start = time.time()

    try:
        result = call_tool(
            tool_name,
            wf_id,
            user_id,
            group_id,
            agent_name,
            tool_args,
            conversation_id=state.get("conversation_id"),
            step_id=step_id,
            route_decision=state.get("route_decision"),
            context_budget=state.get("context_budget"),
        )
        latency = int((time.time() - start) * 1000)
        success = result.get("success", False)
        data = result.get("data")
        data_preview = str(data)[:500] if data else ""
        error_msg = result.get("errorMessage", "")
        error_code = result.get("errorCode", "")
    except Exception as e:
        latency = int((time.time() - start) * 1000)
        success = False
        data = None
        data_preview = ""
        error_msg = str(e)
        error_code = "CALL_ERROR"

    obs = {
        "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
        "success": success,
        "tool_name": tool_name,
        "summary": f"{tool_name}: {'SUCCESS' if success else 'FAILED'} ({latency}ms)",
        "data_preview": data_preview,
        "data": data,
        "error": error_msg,
        "error_code": error_code,
        "latency_ms": latency,
    }
    _record_observation(state, obs)

    # Update working memory
    wm = state.get("working_memory", {})
    _update_working_memory(wm, tool_name, success, data)

    # Record step (ACT + OBSERVE combined)
    step = {
        "stepId": step_id,
        "workflowId": wf_id,
        "agentProfile": agent_name,
        "stepIndex": step_index,
        "phase": "OBSERVE",
        "action": {
            "actionType": "TOOL",
            "actionName": tool_name,
            "arguments": tool_args,
        },
        "observation": obs,
        "status": "SUCCESS" if success else "FAILED",
        "latencyMs": latency,
    }
    _record_step(state, step)

    new_tc = state.get("tool_call_count", 0) + 1

    return {
        "observations": state["observations"],
        "working_memory": wm,
        "tool_call_count": new_tc,
        "react_steps": state["react_steps"],
    }


def handoff_node(state: ReActState) -> dict:
    """委托节点 — 将任务委托给另一个 Agent 执行。

    当 reason_node 的决策是 "HANDOFF" 时，图流转到此节点。
    委托流程：
    1. 获取目标 Agent 的配置
    2. 检查委托深度（防止无限委托，最大 2 层）
    3. 为子 Agent 创建新的状态
    4. 调用子 Agent 的图执行（递归调用 get_graph().invoke()）
    5. 合并子 Agent 的执行结果到当前状态

    返回值：更新后的 observations, working_memory, react_steps, messages, warnings
    """
    action = state.get("last_action", {})
    target_profile = action.get("actionName", "")
    reasoning = action.get("reasoningSummary", "")
    parent_profile = state.get("profile", "deep_research_react")
    workflow_id = state.get("workflow_id", "")
    handoff_depth = int(state.get("handoff_depth", 0))
    parent_steps = state.get("react_steps", [])
    parent_step_id = parent_steps[-1].get("stepId") if parent_steps else f"step_{state.get('step_index', 0)}"
    step_id = f"step_{uuid.uuid4().hex[:8]}"
    step_index = state.get("step_index", 0)

    target_agent = get_profile(target_profile)
    if not target_profile or target_agent is None:
        observation = _build_failure_observation(
            target_profile,
            "AGENT_NOT_FOUND",
            f"Target Agent profile does not exist: {target_profile or 'empty'}",
        )
        _record_observation(state, observation)
        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "OBSERVE",
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": action.get("arguments", {})},
                "observation": observation,
                "status": "FAILED",
                "latencyMs": 0,
            },
        )
        state.setdefault("warnings", []).append(observation["summary"])
        return {
            "observations": state["observations"],
            "react_steps": state["react_steps"],
            "warnings": state["warnings"],
            "messages": state.get("messages", []),
            "handoff_target": target_profile,
            "handoff_status": "FAILED",
        }

    if handoff_depth >= 2:
        observation = _build_failure_observation(
            target_profile,
            "HANDOFF_DEPTH_EXCEEDED",
            f"Handoff depth exceeded for target profile: {target_profile}",
        )
        _record_observation(state, observation)
        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "OBSERVE",
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": action.get("arguments", {})},
                "observation": observation,
                "status": "FAILED",
                "latencyMs": 0,
            },
        )
        state.setdefault("warnings", []).append(observation["summary"])
        return {
            "observations": state["observations"],
            "react_steps": state["react_steps"],
            "warnings": state["warnings"],
            "messages": state.get("messages", []),
            "handoff_target": target_profile,
            "handoff_status": "FAILED",
        }

    child_query = action.get("arguments", {}).get("task") or action.get("arguments", {}).get("query") or state.get("query", "")
    child_state = _new_state(
        workflow_id=workflow_id,
        profile=target_profile,
        user_id=state.get("user_id", ""),
        research_group_id=state.get("research_group_id", ""),
        query=child_query,
        conversation_id=state.get("conversation_id"),
        route_decision=state.get("route_decision"),
        context_budget=state.get("context_budget"),
    )
    child_state["working_memory"] = copy.deepcopy(state.get("working_memory", {}))
    child_state["handoff_depth"] = handoff_depth + 1
    child_state["messages"] = []

    start = time.time()
    child_config = {"configurable": {"thread_id": f"{workflow_id}:{target_profile}:{step_id}"}}
    child_graph = get_graph()
    try:
        child_graph.invoke(child_state, config=child_config)
        child_graph_state = child_graph.get_state(child_config)
        child_values = child_graph_state.values if child_graph_state and child_graph_state.values else child_state
        latency = int((time.time() - start) * 1000)

        response_payload = {
            "status": child_values.get("status", "COMPLETED"),
            "finalAnswer": child_values.get("final_answer", ""),
            "steps": len(child_values.get("react_steps", [])),
        }
        handoff_messages = build_handoff_messages(
            workflow_id=workflow_id,
            parent_step_id=parent_step_id,
            sender=parent_profile,
            receiver=target_profile,
            task=reasoning or child_query,
            response=response_payload,
        )

        observation = {
            "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
            "success": child_values.get("status", "COMPLETED") != "FAILED",
            "tool_name": target_profile,
            "summary": f"Handoff completed via {target_profile}",
            "data_preview": preview(child_values.get("final_answer", ""), 500),
            "data": response_payload,
            "error": "",
            "error_code": "",
            "latency_ms": latency,
        }
        _record_observation(state, observation)

        wm = state.get("working_memory", {})
        child_wm = child_values.get("working_memory") or {}
        if child_values.get("final_answer"):
            wm["draft_answer"] = child_values.get("final_answer")
        if child_wm.get("warnings"):
            wm.setdefault("warnings", []).extend(child_wm.get("warnings", []))
        if child_wm.get("evidence_spans"):
            wm.setdefault("evidence_spans", []).extend(child_wm.get("evidence_spans", []))
        if child_wm.get("verification_results"):
            wm.setdefault("verification_results", []).extend(child_wm.get("verification_results", []))

        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "OBSERVE",
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": action.get("arguments", {})},
                "observation": observation,
                "status": "SUCCESS" if observation["success"] else "FAILED",
                "latencyMs": latency,
            },
        )

        merged_steps = list(state.get("react_steps", []))
        for child_step in child_values.get("react_steps", []):
            cloned = dict(child_step)
            cloned["agentProfile"] = cloned.get("agentProfile") or target_profile
            merged_steps.append(cloned)

        merged_messages = (
            list(state.get("messages", []))
            + handoff_messages
            + list(child_values.get("messages", []))
        )

        warnings = list(state.get("warnings", []))
        warnings.extend(child_values.get("warnings", []))

        return {
            "observations": state["observations"],
            "working_memory": wm,
            "react_steps": merged_steps,
            "messages": merged_messages,
            "warnings": warnings,
            "handoff_target": target_profile,
            "handoff_status": child_values.get("status", "COMPLETED"),
        }
    except Exception as exc:
        latency = int((time.time() - start) * 1000)
        observation = _build_failure_observation(target_profile, "HANDOFF_FAILED", str(exc))
        observation["latency_ms"] = latency
        _record_observation(state, observation)
        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "OBSERVE",
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": action.get("arguments", {})},
                "observation": observation,
                "status": "FAILED",
                "latencyMs": latency,
            },
        )
        state.setdefault("warnings", []).append(f"Handoff failed: {exc}")
        return {
            "observations": state["observations"],
            "react_steps": state["react_steps"],
            "warnings": state["warnings"],
            "messages": state.get("messages", []),
            "handoff_target": target_profile,
            "handoff_status": "FAILED",
        }


def _deep_handoff_node(state: ReActState, target_profile: str, task: str, reason: str) -> dict:
    """DeepResearch-controlled handoff for bounded specialist delegation.

    This is intentionally separate from ordinary ReAct handoff: DeepResearch does
    not route through reason_node, so deep_evaluate decides the specialist and
    this helper executes one child Agent, then merges structured results back.
    """
    target_agent = get_profile(target_profile)
    parent_profile = state.get("profile", "deep_research_react")
    workflow_id = state.get("workflow_id", "")
    step_id = f"deep_handoff_{target_profile}_{uuid.uuid4().hex[:6]}"
    step_index = state.get("step_index", 0)
    history = list(state.get("deep_handoff_history", []))
    warnings = list(state.get("warnings", []))

    if target_agent is None:
        observation = _build_failure_observation(
            target_profile,
            "AGENT_NOT_FOUND",
            f"DeepResearch handoff target does not exist: {target_profile}",
        )
        _record_observation(state, observation)
        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "DEEP_HANDOFF",
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": {"task": task}},
                "observation": observation,
                "status": "FAILED",
                "latencyMs": 0,
            },
        )
        warnings.append(observation["summary"])
        history.append({"target": target_profile, "status": "FAILED", "reason": observation["summary"]})
        return {
            "observations": state["observations"],
            "react_steps": state["react_steps"],
            "warnings": warnings,
            "deep_handoff_history": history,
            "deep_handoff_count": state.get("deep_handoff_count", 0) + 1,
        }

    child_state = _new_state(
        workflow_id=workflow_id,
        profile=target_profile,
        user_id=state.get("user_id", ""),
        research_group_id=state.get("research_group_id", ""),
        query=task,
        conversation_id=state.get("conversation_id"),
        route_decision=state.get("route_decision"),
        context_budget=state.get("context_budget"),
    )
    child_state["working_memory"] = copy.deepcopy(state.get("working_memory", {}))
    child_state["handoff_depth"] = int(state.get("handoff_depth", 0)) + 1
    child_state["messages"] = []

    start = time.time()
    child_config = {"configurable": {"thread_id": f"{workflow_id}:{target_profile}:{step_id}"}}
    child_graph = get_graph()
    try:
        child_graph.invoke(child_state, config=child_config)
        child_graph_state = child_graph.get_state(child_config)
        child_values = child_graph_state.values if child_graph_state and child_graph_state.values else child_state
        latency = int((time.time() - start) * 1000)
        child_status = child_values.get("status", "COMPLETED")
        child_answer = child_values.get("final_answer", "")
        child_wm = child_values.get("working_memory") or {}

        wm = state.get("working_memory", {})
        custom = wm.setdefault("custom_data", {})
        child_custom = child_wm.get("custom_data", {}) if isinstance(child_wm.get("custom_data"), dict) else {}
        handoff_rows = custom.setdefault("agent_handoffs", [])
        handoff_rows.append({
            "target": target_profile,
            "status": child_status,
            "reason": reason,
            "answerPreview": preview(child_answer, 500),
        })

        if target_profile == DEEP_RESEARCH_EXTERNAL_AGENT:
            external_results = custom.setdefault("external_results", [])
            child_external = child_custom.get("external_results", [])
            external_results.extend(_normalize_external_results(child_external))
            if child_answer and not child_external:
                external_results.extend(_normalize_external_results({
                    "source": target_profile,
                    "title": "External Scholar Scout result",
                    "text": child_answer,
                }))

        if child_wm.get("evidence_spans"):
            wm.setdefault("evidence_spans", []).extend(child_wm.get("evidence_spans", []))
        if child_wm.get("verification_results"):
            wm.setdefault("verification_results", []).extend(child_wm.get("verification_results", []))

        response_payload = {
            "status": child_status,
            "targetProfile": target_profile,
            "finalAnswer": child_answer,
            "steps": len(child_values.get("react_steps", [])),
        }
        observation = {
            "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
            "success": child_status != "FAILED",
            "tool_name": target_profile,
            "summary": f"DeepResearch handoff completed via {target_profile}",
            "data_preview": preview(child_answer, 700),
            "data": response_payload,
            "error": "",
            "error_code": "",
            "latency_ms": latency,
        }
        _record_observation(state, observation)

        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "DEEP_HANDOFF",
                "reasoningSummary": reason,
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": {"task": task}},
                "observation": observation,
                "status": "SUCCESS" if observation["success"] else "FAILED",
                "latencyMs": latency,
            },
        )

        merged_steps = list(state.get("react_steps", []))
        for child_step in child_values.get("react_steps", []):
            cloned = dict(child_step)
            cloned["agentProfile"] = cloned.get("agentProfile") or target_profile
            merged_steps.append(cloned)

        handoff_messages = build_handoff_messages(
            workflow_id=workflow_id,
            parent_step_id=step_id,
            sender=parent_profile,
            receiver=target_profile,
            task=task,
            response=response_payload,
        )
        merged_messages = (
            list(state.get("messages", []))
            + handoff_messages
            + list(child_values.get("messages", []))
        )
        warnings.extend(child_values.get("warnings", []))
        history.append({"target": target_profile, "status": child_status, "reason": reason})

        return {
            "observations": state["observations"],
            "working_memory": wm,
            "react_steps": merged_steps,
            "messages": merged_messages,
            "warnings": warnings,
            "deep_handoff_history": history,
            "deep_handoff_count": state.get("deep_handoff_count", 0) + 1,
        }
    except Exception as exc:
        latency = int((time.time() - start) * 1000)
        observation = _build_failure_observation(target_profile, "DEEP_HANDOFF_FAILED", str(exc))
        observation["latency_ms"] = latency
        _record_observation(state, observation)
        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": workflow_id,
                "agentProfile": parent_profile,
                "stepIndex": step_index,
                "phase": "DEEP_HANDOFF",
                "action": {"actionType": "HANDOFF", "actionName": target_profile, "arguments": {"task": task}},
                "observation": observation,
                "status": "FAILED",
                "latencyMs": latency,
            },
        )
        warnings.append(f"DeepResearch handoff failed: {exc}")
        history.append({"target": target_profile, "status": "FAILED", "reason": str(exc)})
        return {
            "observations": state["observations"],
            "react_steps": state["react_steps"],
            "warnings": warnings,
            "deep_handoff_history": history,
            "deep_handoff_count": state.get("deep_handoff_count", 0) + 1,
        }


def deep_diagnose_library_node(state: ReActState) -> dict:
    """Run deterministic library diagnostics as a tool, not as a child Agent."""
    workflow_id = state.get("workflow_id", "")
    profile = state.get("profile", "deep_research_react")
    step_id = f"deep_diagnose_library_{uuid.uuid4().hex[:6]}"
    started_at = time.time()
    warnings = list(state.get("warnings", []))
    try:
        result = call_tool(
            "diagnose_library",
            workflow_id,
            state.get("user_id", ""),
            state.get("research_group_id", ""),
            profile,
            {},
            conversation_id=state.get("conversation_id"),
            step_id=step_id,
            route_decision=state.get("route_decision"),
            context_budget=state.get("context_budget"),
        )
        success = bool(result.get("success", False))
        data = result.get("data")
        error = result.get("errorMessage", "")
        error_code = result.get("errorCode", "")
    except Exception as exc:
        success = False
        data = None
        error = str(exc)
        error_code = "CALL_ERROR"

    latency = int((time.time() - started_at) * 1000)
    observation = {
        "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
        "success": success,
        "tool_name": "diagnose_library",
        "summary": "Local library diagnostics completed." if success else "Local library diagnostics failed.",
        "data_preview": preview(data, 900),
        "data": data,
        "error": error,
        "error_code": error_code,
        "latency_ms": latency,
    }
    _record_observation(state, observation)
    _update_working_memory(state.get("working_memory", {}), "diagnose_library", success, data)
    if not success:
        warnings.append(f"Library diagnostics failed: {error or error_code}")
    _record_step(state, {
        "stepId": step_id,
        "workflowId": workflow_id,
        "agentProfile": profile,
        "stepIndex": state.get("step_index", 0),
        "phase": "DEEP_DIAGNOSE_LIBRARY",
        "reasoningSummary": "Diagnose an empty or unavailable local knowledge space.",
        "action": {"actionType": "TOOL", "actionName": "diagnose_library", "arguments": {}},
        "observation": observation,
        "status": "SUCCESS" if success else "FAILED",
        "latencyMs": latency,
    })
    return {
        "observations": state["observations"],
        "working_memory": state.get("working_memory", {}),
        "tool_call_count": int(state.get("tool_call_count", 0)) + 1,
        "warnings": warnings,
        "react_steps": state["react_steps"],
    }


def deep_handoff_external_node(state: ReActState) -> dict:
    evaluation = state.get("research_evaluation") or {}
    task = (
        "Search external academic/web sources to fill DeepResearch coverage gaps. "
        "Return only structured external supplements; do not write the final report. "
        f"Original user query: {state.get('query', '')}. "
        f"Current gaps: {_safe_json(evaluation.get('gaps', []), 2000)}"
    )
    return _deep_handoff_node(
        state,
        DEEP_RESEARCH_EXTERNAL_AGENT,
        task,
        "DeepResearch needs latest/SOTA/survey/benchmark or external coverage.",
    )


def _update_working_memory(wm: dict, tool_name: str, success: bool, data):
    """根据工具调用结果更新工作记忆。

    工作记忆（working_memory）是 Agent 的"短期记忆"，存储已收集的证据和验证结果。
    不同工具的结果会被提取并存储到不同的字段中：
    - retrieve_evidence → evidence_spans（证据片段）
    - list_papers → custom_data（文献库信息）
    - get_library_summary → custom_data（文献库摘要）
    - judge_evidence_quality → verification_results（验证结果）
    """
    if not success or data is None:
        return

    data = _tool_payload(data)

    if tool_name == "retrieve_evidence" and isinstance(data, dict):
        spans = data.get("evidenceSpans") or data.get("evidence") or []
        if isinstance(spans, list):
            for span in spans:
                wm.setdefault("evidence_spans", []).append(span)
            if not wm.get("draft_answer") and spans:
                wm["draft_answer"] = f"Retrieved {len(spans)} evidence span(s)."

    elif tool_name == "list_papers" and isinstance(data, dict):
        cd = wm.setdefault("custom_data", {})
        cd["library_inventory_observed"] = True
        cd["library_paper_count"] = data.get("paperCount", data.get("total", data.get("documentCount", 0)))
        cd["library_summary"] = data.get("summary", cd.get("library_summary", ""))
        cd["library_warnings"] = data.get("warnings", cd.get("library_warnings", []))
        papers = data.get("papers", [])
        cd["library_paper_titles"] = [p.get("title", "") for p in papers[:10]]
        if not wm.get("draft_answer"):
            titles = [p.get("title", "?") for p in papers[:5]]
            wm["draft_answer"] = f"Library contains {len(papers)} paper(s): " + ", ".join(titles)

    elif tool_name == "get_library_summary" and isinstance(data, dict):
        cd = wm.setdefault("custom_data", {})
        cd["library_inventory_observed"] = True
        cd["library_summary"] = data.get("summary", "")
        cd["library_paper_count"] = data.get("paperCount", data.get("total", data.get("documentCount", cd.get("library_paper_count", 0))))
        cd["library_warnings"] = data.get("warnings", cd.get("library_warnings", []))
        if not wm.get("draft_answer"):
            wm["draft_answer"] = data.get("summary", "Library summary retrieved.")

    elif tool_name == "diagnose_library" and isinstance(data, dict):
        cd = wm.setdefault("custom_data", {})
        cd["library_diagnostics"] = data
        cd["library_paper_count"] = data.get("paperCount", cd.get("library_paper_count", 0))
        cd["library_warnings"] = data.get("warnings", cd.get("library_warnings", []))

    elif tool_name == "judge_evidence_quality" and isinstance(data, dict):
        wm.setdefault("verification_results", []).append({
            "claim": data.get("claim", ""),
            "support_status": data.get("supportStatus", data.get("judgment", "unknown")),
            "reason": data.get("reason", ""),
            "confidence": data.get("confidence", 0),
        })

    elif tool_name in {"search_papers", "web_search"}:
        cd = wm.setdefault("custom_data", {})
        results = cd.setdefault("external_results", [])
        if isinstance(data, dict):
            papers = data.get("papers") or data.get("results") or data.get("items") or []
            if isinstance(papers, list):
                for item in papers[:8]:
                    results.append(item)
            else:
                results.append(data)
        elif isinstance(data, list):
            results.extend(data[:8])
        elif data:
            results.append({"text": str(data)[:1000]})
        if results and not wm.get("draft_answer"):
            wm["draft_answer"] = f"Collected {len(results)} external research result(s)."

    elif tool_name == "memory_recall" and isinstance(data, dict):
        cd = wm.setdefault("custom_data", {})
        cd["memory_recall"] = data

    elif tool_name == "query_rewrite" and isinstance(data, dict):
        cd = wm.setdefault("custom_data", {})
        cd["last_rewritten_query"] = data.get("rewrittenQuery") or data.get("rewritten_query")
        cd["query_rewrite_changes"] = data.get("changes", "")

    elif tool_name == "read_document" and isinstance(data, dict):
        cd = wm.setdefault("custom_data", {})
        reads = cd.setdefault("document_reads", [])
        reads.append(data)


def deep_plan_node(state: ReActState) -> dict:
    """DeepResearch plan node — explicitly creates research_plan.plan_steps.

    Unlike ordinary ReAct, DeepResearch starts with a structured research plan.
    The later execute/evaluate/replan nodes consume and update these steps.
    """
    prof = get_profile(state.get("profile", "deep_research_react")) or get_profile("deep_research_react")
    allowed_tools = set(prof.allowed_tools if prof else DEEP_RESEARCH_EXECUTABLE_TOOLS)
    query = state.get("query", "")
    context = {
        "query": query,
        "routeDecision": state.get("route_decision") or {},
        "promptContext": state.get("prompt_context", "")[:3000],
        "allowedTools": sorted(allowed_tools),
        "externalSearchPolicy": "External search is delegated after evaluation and is not a plan tool.",
    }
    system_msg = """你是 DeepResearch Agent 的 deep_plan 节点。
请为用户的深度研究任务生成显式 research_plan。计划必须先检查本地文献库，再检索本地证据；
计划中只使用当前 DeepResearch Agent 的本地工具。外部检索由 evaluate 节点决定是否 handoff，不写入本地工具计划。

每个 plan step 必须包含：
- step_id
- description
- tool_hint：只能从 allowedTools 中选择
- search_query
- expected_evidence
- requires_external_search: true/false

严格输出 JSON：
{"goal":"...","rationale":"...","plan_steps":[{"step_id":"dr-1","description":"...","tool_hint":"get_library_summary","search_query":"...","expected_evidence":"...","requires_external_search":false}]}"""

    start = time.time()
    source = "llm"
    try:
        result = chat_json(system_msg, _safe_json(context))
        raw_steps = result.get("plan_steps") or result.get("steps") or []
        goal = _safe_text(result.get("goal") or query, 1000)
        rationale = _safe_text(result.get("rationale") or "", 1500)
    except Exception as exc:
        raw_steps = []
        goal = query
        rationale = f"Plan fallback because LLM planning failed: {exc}"
        source = "fallback"

    if not isinstance(raw_steps, list) or not raw_steps:
        raw_steps = _fallback_deep_research_plan(state, allowed_tools)
        source = "fallback"
        normalized_steps = raw_steps
    else:
        normalized_steps = [
            _normalize_plan_step(step, idx + 1, source=source, allowed_tools=allowed_tools)
            for idx, step in enumerate(raw_steps[:8])
        ]

    # Keep DeepResearch recognizably local-first even if the LLM forgets the inventory step.
    if normalized_steps and normalized_steps[0].get("tool_hint") not in {"get_library_summary", "list_papers"}:
        inventory_tool = "get_library_summary" if "get_library_summary" in allowed_tools else "list_papers"
        normalized_steps.insert(0, _normalize_plan_step(
            {
                "step_id": "dr-0",
                "description": "Inspect the local literature library before executing the research plan.",
                "tool_hint": inventory_tool,
                "search_query": query,
            },
            0,
            source="guardrail",
            allowed_tools=allowed_tools,
        ))

    research_plan = {
        "goal": goal,
        "rationale": rationale,
        "plan_steps": normalized_steps,
        "status": "PLANNED",
        "source": source,
    }
    latency = int((time.time() - start) * 1000)
    _record_deep_step(
        state,
        "DEEP_PLAN",
        summary=f"Generated DeepResearch plan with {len(normalized_steps)} step(s).",
        payload={"researchPlan": research_plan},
        latency_ms=latency,
    )
    return {
        "research_plan": research_plan,
        "react_steps": state["react_steps"],
    }


def deep_execute_node(state: ReActState) -> dict:
    """DeepResearch execute node — consumes pending plan_steps and calls tools."""
    prof = get_profile(state.get("profile", "deep_research_react")) or get_profile("deep_research_react")
    allowed_tools = set(prof.allowed_tools if prof else DEEP_RESEARCH_EXECUTABLE_TOOLS)
    max_tool_calls = prof.max_tool_calls if prof else 20
    plan = state.get("research_plan") or {}
    steps = list(plan.get("plan_steps") or [])
    wf_id = state.get("workflow_id", "")
    user_id = state.get("user_id", "")
    group_id = state.get("research_group_id", "")
    agent_name = state.get("profile", "deep_research_react")
    wm = state.get("working_memory", {})
    executed: list[dict[str, Any]] = []
    warnings = list(state.get("warnings", []))
    tc = int(state.get("tool_call_count", 0))
    batch_limit = 5

    start_all = time.time()
    for step in steps:
        if len(executed) >= batch_limit or tc >= max_tool_calls:
            break
        if step.get("status") not in {"PENDING", "RETRY"}:
            continue

        tool_name = str(step.get("tool_hint") or "retrieve_evidence").strip()
        if tool_name not in allowed_tools:
            step["status"] = "SKIPPED"
            step["status_reason"] = f"Tool not allowed for {agent_name}: {tool_name}"
            warnings.append(step["status_reason"])
            continue

        tool_args = _build_deep_tool_args(tool_name, step, state, wm)
        step_id = f"deep_{step.get('step_id')}_{uuid.uuid4().hex[:6]}"
        start = time.time()
        try:
            result = call_tool(
                tool_name,
                wf_id,
                user_id,
                group_id,
                agent_name,
                tool_args,
                conversation_id=state.get("conversation_id"),
                step_id=step_id,
                route_decision=state.get("route_decision"),
                context_budget=state.get("context_budget"),
            )
            latency = int((time.time() - start) * 1000)
            success = result.get("success", False)
            data = result.get("data")
            error_msg = result.get("errorMessage", "")
            error_code = result.get("errorCode", "")
        except Exception as exc:
            latency = int((time.time() - start) * 1000)
            success = False
            data = None
            error_msg = str(exc)
            error_code = "CALL_ERROR"

        result_count = _deep_result_count(tool_name, data)
        data_preview = preview(data, 700)
        obs = {
            "observation_id": f"obs_{uuid.uuid4().hex[:8]}",
            "success": success,
            "tool_name": tool_name,
            "summary": f"DeepResearch step {step.get('step_id')} used {tool_name}: {'SUCCESS' if success else 'FAILED'} ({result_count} result(s), {latency}ms)",
            "data_preview": data_preview,
            "data": data,
            "error": error_msg,
            "error_code": error_code,
            "latency_ms": latency,
            "plan_step_id": step.get("step_id"),
        }
        _record_observation(state, obs)
        _update_working_memory(wm, tool_name, success, data)
        tc += 1

        step["attempts"] = int(step.get("attempts") or 0) + 1
        step["observation_id"] = obs["observation_id"]
        step["tool_args"] = tool_args
        step["result_count"] = result_count
        step["latency_ms"] = latency
        if success and result_count > 0:
            step["status"] = "DONE"
        elif success:
            step["status"] = "EMPTY"
        else:
            step["status"] = "FAILED"
            step["status_reason"] = error_msg or error_code
            warnings.append(f"DeepResearch step {step.get('step_id')} failed: {error_msg or error_code}")

        _record_step(
            state,
            {
                "stepId": step_id,
                "workflowId": wf_id,
                "agentProfile": agent_name,
                "stepIndex": state.get("step_index", 0),
                "phase": "DEEP_EXECUTE",
                "reasoningSummary": step.get("description", ""),
                "action": {"actionType": "TOOL", "actionName": tool_name, "arguments": tool_args},
                "observation": obs,
                "status": "SUCCESS" if success else "FAILED",
                "latencyMs": latency,
            },
        )
        executed.append({"step_id": step.get("step_id"), "tool": tool_name, "status": step.get("status")})

        # If query_rewrite produces a better query, append a local retrieval step so rewrite has visible effect.
        if tool_name == "query_rewrite" and success and isinstance(data, dict):
            rewritten = data.get("rewrittenQuery") or data.get("rewritten_query")
            if rewritten:
                steps.append(_normalize_plan_step(
                    {
                        "step_id": f"{step.get('step_id')}-retrieve",
                        "description": f"Retrieve evidence using rewritten query: {rewritten}",
                        "tool_hint": "retrieve_evidence",
                        "search_query": rewritten,
                    },
                    len(steps) + 1,
                    source="query_rewrite",
                    allowed_tools=allowed_tools,
                ))

    plan["plan_steps"] = steps
    plan["status"] = "EXECUTED" if not any(s.get("status") in {"PENDING", "RETRY"} for s in steps) else "PARTIAL_EXECUTED"
    _record_deep_step(
        state,
        "DEEP_EXECUTE_SUMMARY",
        summary=f"Executed {len(executed)} DeepResearch plan step(s).",
        payload={"executed": executed, "planStatus": plan.get("status")},
        latency_ms=int((time.time() - start_all) * 1000),
    )
    return {
        "research_plan": plan,
        "observations": state["observations"],
        "working_memory": wm,
        "tool_call_count": tc,
        "warnings": warnings,
        "react_steps": state["react_steps"],
        "deep_executed": executed,
    }


def deep_evaluate_node(state: ReActState) -> dict:
    """DeepResearch evaluate node — judges plan coverage and whether replan is needed."""
    plan = state.get("research_plan") or {}
    steps = list(plan.get("plan_steps") or [])
    wm = state.get("working_memory", {})
    done = [s for s in steps if s.get("status") == "DONE"]
    empty_or_failed = [s for s in steps if s.get("status") in {"EMPTY", "FAILED", "SKIPPED"}]
    pending = [s for s in steps if s.get("status") in {"PENDING", "RETRY"}]
    local_evidence_count = len(wm.get("evidence_spans", []))
    external_count = len(wm.get("custom_data", {}).get("external_results", []))

    context = {
        "query": state.get("query", ""),
        "researchPlan": plan,
        "localEvidenceCount": local_evidence_count,
        "externalResultCount": external_count,
        "verificationResults": wm.get("verification_results", [])[-8:],
        "recentObservations": [
            {"tool": obs.get("tool_name"), "success": obs.get("success"), "summary": obs.get("summary")}
            for obs in state.get("observations", [])[-10:]
        ],
        "replanCount": state.get("deep_replan_count", 0),
        "maxReplan": state.get("max_deep_replan", 2),
        "freshExternalEvidenceRequested": _needs_external_search(state.get("query", "")),
    }
    system_msg = """你是 DeepResearch Agent 的 deep_evaluate 节点。
请评估 research_plan 的覆盖度和证据质量，判断是否需要继续执行、重规划或综合。

决策规则：
- 如果仍有 PENDING 步骤且预算未耗尽，decision 应为 "execute_more"。
- 如果主要研究维度已有本地证据或外部补证，decision 可为 "pass"。
- 如果关键维度证据为空或失败，decision 应为 "replan" 并列出 gaps。
- 如果没有任何可用证据且多次重规划无效，decision 可为 "fail"。

严格输出 JSON：
If fresh/latest/SOTA evidence is requested and local evidence cannot establish freshness,
set external_required=true and describe the bounded gap. Otherwise keep it false.
{"coverage_score":0.75,"quality_score":0.7,"decision":"execute_more|pass|replan|fail","external_required":false,"gaps":[{"step_id":"dr-2","reason":"...","suggested_query":"...","suggested_tool":"retrieve_evidence"}],"summary":"..."}"""

    start = time.time()
    try:
        result = chat_json(system_msg, _safe_json(context))
        evaluation = {
            "coverage_score": float(result.get("coverage_score", 0.0)),
            "quality_score": float(result.get("quality_score", 0.0)),
            "decision": str(result.get("decision", "")).lower(),
            "external_required": bool(result.get("external_required", False)),
            "gaps": result.get("gaps") if isinstance(result.get("gaps"), list) else [],
            "summary": _safe_text(result.get("summary") or "", 1500),
        }
    except Exception as exc:
        total = max(len(steps), 1)
        coverage = len(done) / total
        quality = 0.7 if local_evidence_count or external_count else 0.2
        decision = "execute_more" if pending else ("pass" if local_evidence_count or external_count else "replan")
        evaluation = {
            "coverage_score": round(coverage, 2),
            "quality_score": quality,
            "decision": decision,
            "external_required": _needs_external_search(state.get("query", "")) and external_count == 0,
            "gaps": [{"step_id": s.get("step_id"), "reason": s.get("status_reason") or s.get("description", "")} for s in empty_or_failed[:5]],
            "summary": f"Fallback evaluation because LLM evaluation failed: {exc}",
        }

    if pending and evaluation.get("decision") not in {"replan", "fail"}:
        evaluation["decision"] = "execute_more"
    if evaluation.get("decision") not in {"execute_more", "pass", "replan", "fail"}:
        evaluation["decision"] = "pass" if (local_evidence_count or external_count) else "replan"

    if state.get("deep_replan_count", 0) >= state.get("max_deep_replan", 2) and evaluation["decision"] == "replan":
        evaluation["decision"] = "pass" if (local_evidence_count or external_count) else "fail"
        evaluation["summary"] = (evaluation.get("summary") or "") + " Replan budget exhausted."

    latency = int((time.time() - start) * 1000)
    _record_deep_step(
        state,
        "DEEP_EVALUATE",
        summary=f"Coverage={evaluation.get('coverage_score')} quality={evaluation.get('quality_score')} decision={evaluation.get('decision')}",
        payload={"researchEvaluation": evaluation},
        latency_ms=latency,
    )
    return {
        "research_evaluation": evaluation,
        "react_steps": state["react_steps"],
    }


def deep_replan_node(state: ReActState) -> dict:
    """DeepResearch replan node — appends new PENDING plan_steps for uncovered gaps."""
    prof = get_profile(state.get("profile", "deep_research_react")) or get_profile("deep_research_react")
    allowed_tools = set(prof.allowed_tools if prof else DEEP_RESEARCH_EXECUTABLE_TOOLS)
    plan = state.get("research_plan") or {}
    steps = list(plan.get("plan_steps") or [])
    evaluation = state.get("research_evaluation") or {}
    replan_count = int(state.get("deep_replan_count", 0)) + 1
    context = {
        "query": state.get("query", ""),
        "researchPlan": plan,
        "evaluation": evaluation,
        "allowedTools": sorted(allowed_tools),
        "replanCount": replan_count,
        "externalSearchAllowed": False,
    }
    system_msg = """你是 DeepResearch Agent 的 deep_replan 节点。
请根据 evaluation.gaps 追加新的补充检索步骤。不要重复已完成步骤。
只追加当前 Agent 白名单中的本地检索步骤。外部检索由 evaluate 节点单独 handoff。

严格输出 JSON：
{"new_steps":[{"step_id":"dr-r1","description":"...","tool_hint":"retrieve_evidence","search_query":"...","expected_evidence":"...","requires_external_search":false}],"rationale":"..."}"""

    start = time.time()
    try:
        result = chat_json(system_msg, _safe_json(context))
        raw_new_steps = result.get("new_steps") or result.get("plan_steps") or []
        rationale = _safe_text(result.get("rationale") or "", 1200)
    except Exception as exc:
        raw_new_steps = []
        rationale = f"Fallback replan because LLM replan failed: {exc}"

    if not isinstance(raw_new_steps, list) or not raw_new_steps:
        gaps = evaluation.get("gaps") if isinstance(evaluation.get("gaps"), list) else []
        raw_new_steps = []
        for idx, gap in enumerate(gaps[:3], start=1):
            suggested_query = gap.get("suggested_query") if isinstance(gap, dict) else ""
            reason = gap.get("reason") if isinstance(gap, dict) else str(gap)
            tool = "retrieve_evidence"
            raw_new_steps.append({
                "step_id": f"dr-r{replan_count}-{idx}",
                "description": f"补充检索未覆盖维度：{reason}",
                "tool_hint": tool,
                "search_query": suggested_query or f"{state.get('query', '')} {reason}",
                "requires_external_search": tool in {"search_papers", "web_search"},
            })
        if not raw_new_steps:
            raw_new_steps = [{
                "step_id": f"dr-r{replan_count}-1",
                "description": "Use an alternative query to cover missing DeepResearch evidence.",
                "tool_hint": "retrieve_evidence",
                "search_query": state.get("query", ""),
            }]

    existing_ids = {str(step.get("step_id")) for step in steps}
    new_steps = []
    for idx, raw_step in enumerate(raw_new_steps[:4], start=1):
        normalized = _normalize_plan_step(raw_step, len(steps) + idx, source=f"replan-{replan_count}", allowed_tools=allowed_tools)
        if normalized["step_id"] in existing_ids:
            normalized["step_id"] = f"{normalized['step_id']}-r{replan_count}"
        existing_ids.add(normalized["step_id"])
        new_steps.append(normalized)

    steps.extend(new_steps)
    plan["plan_steps"] = steps
    plan["status"] = "REPLANNED"
    plan["last_replan_rationale"] = rationale
    latency = int((time.time() - start) * 1000)
    _record_deep_step(
        state,
        "DEEP_REPLAN",
        summary=f"Added {len(new_steps)} DeepResearch replan step(s).",
        payload={"newSteps": new_steps, "rationale": rationale, "replanCount": replan_count},
        latency_ms=latency,
    )
    return {
        "research_plan": plan,
        "deep_replan_count": replan_count,
        "react_steps": state["react_steps"],
    }


def deep_synthesize_node(state: ReActState) -> dict:
    """DeepResearch synthesize node — creates final report from plan, evidence, and evaluation."""
    wm = state.get("working_memory", {})
    start = time.time()
    answer = _synthesize_deep_research_answer(state, wm)
    latency = int((time.time() - start) * 1000)
    _record_deep_step(
        state,
        "DEEP_SYNTHESIZE",
        summary="Generated final DeepResearch report from plan, evidence, external supplements, and evaluation.",
        payload={
            "researchPlan": state.get("research_plan"),
            "researchEvaluation": state.get("research_evaluation"),
            "answerPreview": preview(answer, 800),
        },
        latency_ms=latency,
    )
    return {
        "final_answer": answer,
        "status": "COMPLETED",
        "should_continue": False,
        "react_steps": state["react_steps"],
    }


def _synthesize_deep_research_answer(state: ReActState, wm: dict) -> str:
    """Build the final DeepResearch report from collected observations.

    DeepResearch is now a ReAct Agent. Its final answer should not be the last
    tool summary; it should synthesize local evidence, external supplements,
    memory context, and verification results into a coherent research report.
    """
    query = state.get("query", "")
    custom_data = wm.get("custom_data", {}) if isinstance(wm.get("custom_data"), dict) else {}
    payload = {
        "query": query,
        "researchPlan": state.get("research_plan") or {},
        "researchEvaluation": state.get("research_evaluation") or {},
        "deepReplanCount": state.get("deep_replan_count", 0),
        "deepHandoffHistory": state.get("deep_handoff_history", []),
        "localEvidence": [
            {
                "title": span.get("title"),
                "section": span.get("section"),
                "pageNumber": span.get("pageNumber"),
                "chunkIndex": span.get("chunkIndex"),
                "score": span.get("relevanceScore"),
                "text": preview(span.get("text", ""), 500),
            }
            for span in wm.get("evidence_spans", [])[:10]
            if isinstance(span, dict)
        ],
        "verificationResults": wm.get("verification_results", [])[:10],
        "librarySummary": custom_data.get("library_summary"),
        "libraryPaperTitles": custom_data.get("library_paper_titles", [])[:10],
        "externalResults": custom_data.get("external_results", [])[:10],
        "memoryRecall": custom_data.get("memory_recall"),
        "recentObservations": [
            {
                "tool": obs.get("tool_name"),
                "success": obs.get("success"),
                "summary": obs.get("summary"),
                "preview": preview(obs.get("data_preview", ""), 500),
            }
            for obs in state.get("observations", [])[-8:]
        ],
        "warnings": state.get("warnings", []),
    }

    system_msg = """你是 DeepResearch Agent 的最终报告生成器。
请基于 ReAct 过程中收集到的本地文献证据、外部补充结果、质量判断和警告，生成结构化研究报告。
要求：
1. 必须体现 research_plan 的执行结果、coverage/quality 评估、replan 和 handoff 情况。
2. 优先使用本地文献证据，并明确引用 Evidence 编号。
3. 外部检索结果只能作为补充信息，必须与本地证据区分。
4. 如果证据不足，明确列出缺口和下一步建议。
5. 输出 Markdown。
严格输出 JSON：{"answer": "..."}"""

    try:
        result = chat_json(system_msg, json.dumps(payload, ensure_ascii=False)[:12000])
        answer = result.get("answer", "") if isinstance(result, dict) else ""
        if answer:
            return answer.strip()
    except Exception:
        pass

    lines = ["## DeepResearch Report", "", f"Topic: {query}", ""]
    plan = payload.get("researchPlan") or {}
    evaluation = payload.get("researchEvaluation") or {}
    if plan.get("plan_steps"):
        lines.append("### Research Plan")
        for step in plan.get("plan_steps", [])[:8]:
            lines.append(
                f"- [{step.get('status', 'UNKNOWN')}] {step.get('step_id')}: "
                f"{step.get('description', '')} ({step.get('tool_hint', '')})"
            )
        lines.append("")
    if evaluation:
        lines.append("### Coverage Evaluation")
        lines.append(
            f"- Decision: {evaluation.get('decision', 'unknown')}; "
            f"coverage={evaluation.get('coverage_score', '?')}; "
            f"quality={evaluation.get('quality_score', '?')}; "
            f"replans={payload.get('deepReplanCount', 0)}"
        )
        if evaluation.get("summary"):
            lines.append(f"- Summary: {evaluation.get('summary')}")
        lines.append("")
    handoffs = payload.get("deepHandoffHistory") or []
    if handoffs:
        lines.append("### Specialist Handoffs")
        for item in handoffs[:5]:
            if isinstance(item, dict):
                lines.append(f"- {item.get('target')}: {item.get('status')} ({item.get('reason', '')})")
        lines.append("")
    evidence = payload["localEvidence"]
    if evidence:
        lines.append("### Local Literature Evidence")
        for idx, item in enumerate(evidence[:6], start=1):
            source = " / ".join(str(v) for v in [item.get("title"), item.get("section"), item.get("pageNumber")] if v)
            lines.append(f"{idx}. {item.get('text', '')} [Evidence {idx}]")
            if source:
                lines.append(f"   Source: {source}")
    external = payload["externalResults"]
    if external:
        lines.append("")
        lines.append("### External Supplements")
        for idx, item in enumerate(external[:5], start=1):
            if isinstance(item, dict):
                title = item.get("title") or item.get("name") or item.get("url") or f"Result {idx}"
                abstract = item.get("abstract") or item.get("snippet") or item.get("text") or ""
                lines.append(f"- {title}: {preview(str(abstract), 300)}")
            else:
                lines.append(f"- {preview(str(item), 300)}")
    if not evidence and not external:
        lines.append("No sufficient local or external evidence was collected. Please upload relevant papers or narrow the research topic.")
    if payload["warnings"]:
        lines.append("")
        lines.append("### Warnings")
        for warning in payload["warnings"][-5:]:
            lines.append(f"- {warning}")
    return "\n".join(lines)


def monitor_node(state: ReActState) -> dict:
    """质量监控节点 — 检查执行状态，决定是否继续。

    每次工具调用或委托执行后，图都会流转到此节点。
    监控规则（规则引擎，不调用 LLM）：
    1. 预算检查：工具调用次数是否超过上限
    2. 步数检查：是否接近最大步数
    3. 重复检查：最近两次观察是否完全相同（陷入循环）
    4. 失败检查：最近一次工具调用是否失败

    返回值：should_continue, warnings, step_index, react_steps
    """
    prof = get_profile(state.get("profile", "deep_research_react"))
    max_steps = prof.max_steps if prof else 5
    max_tool_calls = prof.max_tool_calls if prof else 6
    step_idx = state.get("step_index", 0)
    tc = state.get("tool_call_count", 0)
    observations = state.get("observations", [])

    warnings = list(state.get("warnings", []))
    should_continue = True
    can_finish = False

    # Rule 1: Budget check
    if tc >= max_tool_calls:
        warnings.append(f"Tool call budget exhausted ({tc}/{max_tool_calls})")
        should_continue = False

    # Rule 2: Step limit
    if step_idx >= max_steps - 1:
        can_finish = True
        warnings.append(f"Approaching step limit ({step_idx + 1}/{max_steps})")

    # Rule 3: Repeated observation
    if len(observations) >= 2:
        last = observations[-1]
        prev = observations[-2]
        if (last.get("tool_name") == prev.get("tool_name")
                and last.get("data_preview") == prev.get("data_preview")):
            warnings.append("Repeated observation detected, stopping")
            should_continue = False

    # Rule 4: Latest observation failure
    if observations and not observations[-1].get("success"):
        warnings.append(f"Latest tool failed: {observations[-1].get('error', 'unknown')}")

    # Record monitor step
    step_id = f"step_{uuid.uuid4().hex[:8]}"
    step = {
        "stepId": step_id,
        "workflowId": state.get("workflow_id", ""),
        "agentProfile": state.get("profile", ""),
        "stepIndex": step_idx,
        "phase": "MONITOR",
        "monitorResult": {
            "shouldContinue": should_continue,
            "qualityStatus": "PASS" if should_continue else "WARNING",
            "warnings": warnings[-3:],
            "canFinish": can_finish,
        },
        "status": "SUCCESS",
    }
    _record_step(state, step)

    return {
        "should_continue": should_continue,
        "warnings": warnings,
        "step_index": step_idx + 1,
        "react_steps": state["react_steps"],
    }


def finish_node(state: ReActState) -> dict:
    """完成节点 — 构建最终回答并结束 ReAct 循环。

    当 reason_node 的决策是 "FINISH"、"ASK_USER"，或 monitor_node 决定停止时，
    图流转到此节点。

    最终回答的来源（按优先级）：
    1. state["final_answer"]（如果已被设置）
    2. working_memory["draft_answer"]（工作记忆中的草稿回答）
    3. 最近一次工具调用的摘要和数据预览
    4. 兜底文本："No useful results obtained..."

    返回值：final_answer, status="COMPLETED", should_continue=False
    """
    wm = state.get("working_memory", {})
    final_answer = state.get("final_answer", "")

    if state.get("profile") == "deep_research_react" and not final_answer:
        final_answer = _synthesize_deep_research_answer(state, wm)

    if not final_answer:
        final_answer = wm.get("draft_answer", "")

    if not final_answer:
        observations = state.get("observations", [])
        if observations:
            last = observations[-1]
            final_answer = last.get("summary", "") + " " + last.get("data_preview", "")

    if not final_answer:
        final_answer = "No useful results obtained. Please check your research group binding and try again."

    return {
        "final_answer": final_answer.strip(),
        "status": "COMPLETED",
        "should_continue": False,
    }


# ---------------------------------------------------------------------------
# 路由函数（Routing）
# ---------------------------------------------------------------------------
# 路由函数根据当前状态决定下一个执行的节点。
# LangGraph 的条件边（conditional_edges）使用路由函数实现动态跳转。

def start_node(state: ReActState) -> dict:
    """Graph entry node used to split ordinary ReAct and DeepResearch PER."""
    return {}


def route_from_start(state: ReActState) -> str:
    return "deep_plan" if _is_deep_research(state) else "reason"


def route_after_reason(state: ReActState) -> str:
    """推理后路由 — 根据 LLM 的决策选择下一个节点。

    路由逻辑：
    - actionType="TOOL" → "tool"（调用工具）
    - actionType="HANDOFF" → "handoff"（委托其他 Agent）
    - actionType="FINISH" → "finish"（完成任务）
    - actionType="ASK_USER" → "finish"（询问用户，状态设为 ASK_USER）
    - 其他 → "finish"（兜底）

    安全检查：
    - 如果 Agent 不允许 TOOL/HANDOFF 动作，强制跳转到 finish
    - 如果工具调用次数超过上限，强制跳转到 finish
    """
    action = state.get("last_action", {})
    action_type = action.get("actionType", "FINISH").upper()

    if action_type == "TOOL":
        prof = get_profile(state.get("profile", "deep_research_react"))
        tool_name = action.get("actionName", "")
        if prof and not prof.is_action_allowed("TOOL"):
            return "finish"
        if state.get("tool_call_count", 0) >= (prof.max_tool_calls if prof else 6):
            return "finish"
        return "tool"
    elif action_type == "FINISH":
        return "finish"
    elif action_type == "ASK_USER":
        state["status"] = "ASK_USER"
        return "finish"
    elif action_type == "HANDOFF":
        prof = get_profile(state.get("profile", "deep_research_react"))
        if prof and not prof.is_action_allowed("HANDOFF"):
            return "finish"
        return "handoff"
    else:
        return "finish"


def route_after_monitor(state: ReActState) -> str:
    """监控后路由 — 根据监控结果决定下一步。

    路由逻辑：
    - status 为 COMPLETED/FAILED/ASK_USER → END（结束图执行）
    - should_continue=False → "finish"（强制完成）
    - 其他 → "reason"（继续下一轮推理）
    """
    if state.get("status") in ("COMPLETED", "FAILED", "ASK_USER"):
        return END
    if not state.get("should_continue", True):
        return "finish"
    return "reason"


def route_after_deep_evaluate(state: ReActState) -> str:
    """Route DeepResearch after explicit evaluation."""
    evaluation = state.get("research_evaluation") or {}
    decision = str(evaluation.get("decision") or "").lower()
    plan = state.get("research_plan") or {}
    pending = [
        step for step in plan.get("plan_steps", [])
        if step.get("status") in {"PENDING", "RETRY"}
    ]
    prof = get_profile(state.get("profile", "deep_research_react"))
    max_tool_calls = prof.max_tool_calls if prof else 20
    if pending and state.get("tool_call_count", 0) < max_tool_calls:
        return "deep_execute"
    if _should_deep_diagnose_library(state):
        return "deep_diagnose_library"
    if _should_deep_handoff_external(state):
        return "deep_handoff_external"
    if decision == "replan" and state.get("deep_replan_count", 0) < state.get("max_deep_replan", 2):
        return "deep_replan"
    return "deep_synthesize"


# ---------------------------------------------------------------------------
# 图构建
# ---------------------------------------------------------------------------

def build_graph():
    """构建完整的 ReAct 图。

    图结构：
        reason（入口）→ 根据动作类型路由到：
            ├── tool → monitor → reason（循环）
            ├── handoff → monitor → reason（循环）
            └── finish → END

    使用 checkpointer 持久化检查点，支持中断恢复。
    """
    g = StateGraph(ReActState)

    g.add_node("start", start_node)
    g.add_node("reason", reason_node)
    g.add_node("tool", tool_node)
    g.add_node("handoff", handoff_node)
    g.add_node("monitor", monitor_node)
    g.add_node("finish", finish_node)
    g.add_node("deep_plan", deep_plan_node)
    g.add_node("deep_execute", deep_execute_node)
    g.add_node("deep_evaluate", deep_evaluate_node)
    g.add_node("deep_diagnose_library", deep_diagnose_library_node)
    g.add_node("deep_handoff_external", deep_handoff_external_node)
    g.add_node("deep_replan", deep_replan_node)
    g.add_node("deep_synthesize", deep_synthesize_node)

    g.set_entry_point("start")

    g.add_conditional_edges("start", route_from_start, {
        "reason": "reason",
        "deep_plan": "deep_plan",
    })

    g.add_conditional_edges("reason", route_after_reason, {
        "tool": "tool",
        "handoff": "handoff",
        "finish": "finish",
    })
    g.add_edge("tool", "monitor")
    g.add_edge("handoff", "monitor")
    g.add_conditional_edges("monitor", route_after_monitor, {
        "reason": "reason",
        "finish": "finish",
        END: END,
    })
    g.add_edge("finish", END)

    # DeepResearch uses an explicit Plan-Execute-Evaluate-Replan-Synthesize branch
    # instead of the ordinary free-form ReAct loop.
    g.add_edge("deep_plan", "deep_execute")
    g.add_edge("deep_execute", "deep_evaluate")
    g.add_conditional_edges("deep_evaluate", route_after_deep_evaluate, {
        "deep_execute": "deep_execute",
        "deep_diagnose_library": "deep_diagnose_library",
        "deep_handoff_external": "deep_handoff_external",
        "deep_replan": "deep_replan",
        "deep_synthesize": "deep_synthesize",
    })
    g.add_edge("deep_diagnose_library", "deep_evaluate")
    g.add_edge("deep_handoff_external", "deep_evaluate")
    g.add_edge("deep_replan", "deep_execute")
    g.add_edge("deep_synthesize", "finish")

    checkpointer = get_checkpointer()
    return g.compile(checkpointer=checkpointer)


# Singleton graph instance
_graph_app = None


def get_graph():
    global _graph_app
    if _graph_app is None:
        _graph_app = build_graph()
    return _graph_app
