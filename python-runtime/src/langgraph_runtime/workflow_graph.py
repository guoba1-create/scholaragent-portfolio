"""
workflow_graph.py — LangGraph 工作流图定义

本模块定义 Evidence Workflow 的固定业务图结构。

整体架构：
    Java 侧（Spring Boot）通过 gRPC 将任务分发到 Python 侧，
    Python 侧根据 taskType/WorkflowTemplate 选择固定业务模板：
    - 观点验证、Related Work、论文对比、引用支持均先准备检索表达，
      再执行任务专属节点、证据评价和最多一次补充检索。
    - deep_research 不进入本图；它由专用 DeepResearch Agent 图执行。

图结构概览：
    route（入口路由）──→ query_prepare（检索表达准备）──→ 各业务固定节点
    retrieve ──→ evaluate ──┬→ synthesize ──→ monitor ──→ finish ──→ END
                            └→ replan ──→ retrieve（循环，最多 max_replan 次）

所有节点共享 WorkflowState 状态字典，节点函数接收 state、返回 dict（自动合并到 state 中）。
"""

from __future__ import annotations

from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from .checkpoint_store import get_checkpointer
from .runtime_support import (
    build_tool_observation,
    build_trace,
    evidence_to_document_ref,
    now_iso,
    preview,
    short_id,
)
from .mcp_tool_client import call_tool
from .llm_client import chat_json
from .prompts import EVALUATE_SYSTEM_PROMPT, REPLAN_SYSTEM_PROMPT, SYNTHESIZE_SYSTEM_PROMPT


class WorkflowState(TypedDict, total=False):
    """LangGraph 工作流的状态定义。

    所有图节点共享此状态。节点函数返回 dict，LangGraph 自动将返回值合并到 state 中。
    total=False 表示所有字段都是可选的（首次创建 state 时只需提供部分字段）。

    字段分组：
    - 标识字段：workflow_id, user_id, research_group_id, conversation_id
    - 输入字段：query（用户问题）, task_type（任务类型）, route_decision（路由决策）
    - 上下文字段：context_budget, prompt_context
    - 检索结果字段：claims, evidence_by_claim, evidence_spans, document_refs
    - 评估字段：verification_results, claim_evidence_table
    - 可观测性字段：tool_observations, traces, warnings, retrieval_diagnostics, quality_report
    - 输出字段：final_answer, status, step_index
    - 受控补证字段：plan_steps, evaluation, replan_count, max_replan
    """

    # ── 标识字段 ──
    workflow_id: str              # 工作流唯一 ID（由 Java 侧生成，如 "wf_1716268800_a1b2c3d4"）
    user_id: str                  # 用户 ID
    research_group_id: str        # 研究组 ID（用于多租户文档隔离）
    conversation_id: str          # 会话 ID（用于对话上下文关联）

    # ── 输入字段 ──
    query: str                    # 用户的原始问题
    task_type: str                # 任务类型（claim_verification / paper_qa / related_work / paper_compare / citation_recommendation / deep_research）
    route_decision: dict[str, Any]  # Java 侧的路由决策（含 intent, strategy, taskType, confidence 等）
    context_budget: dict[str, Any]  # 上下文预算配置（含 maxPromptChars, promptContext 等）

    # ── 上下文字段 ──
    prompt_context: str           # 经过预算裁剪后的上下文文本（用于 LLM 提示）

    # ── 检索结果字段 ──
    claims: list[str]             # 从用户问题中提取的原子声明列表（观点验证场景）
    evidence_by_claim: list[dict[str, Any]]  # 每个声明对应的检索结果（含 claim, spans, evidenceText）
    evidence_spans: list[dict[str, Any]]     # 所有证据片段的扁平列表（来自 retrieve_evidence 工具）
    document_refs: list[dict[str, Any]]      # 文档引用列表（用于前端 Sources 面板展示）

    # ── 评估字段 ──
    verification_results: list[dict[str, Any]]  # 声明验证结果（含 supportStatus, reason, confidence）
    claim_evidence_table: list[dict[str, Any]]   # 声明-证据对照表（用于前端 Evidence tab 展示）

    # ── 可观测性字段 ──
    tool_observations: list[dict[str, Any]]  # 工具调用记录（含 toolName, compressedText, latencyMs）
    traces: list[dict[str, Any]]             # 执行追踪记录（用于前端 Trace tab 展示）
    warnings: list[str]                      # 警告信息列表
    retrieval_diagnostics: dict[str, Any]    # 检索诊断信息（含 query, retrievalHits, suggestions）
    quality_report: dict[str, Any]           # 质量监控报告（由 monitor_node 生成）

    # ── 输出字段 ──
    final_answer: str             # 最终回答（Markdown 格式）
    status: str                   # 工作流状态（RUNNING / COMPLETED / FAILED / INTERRUPTED / WAITING_USER）
    step_index: int               # 当前步骤编号（用于 trace 排序）

    # ── Plan-Execute-Replan（PER）状态字段 ──
    plan_steps: list[dict[str, Any]]  # 兼容字段：保存 query_prepare/replan 生成的检索步骤
    evaluation: dict[str, Any]        # 评估结果（含 coverage_score, quality_score, gaps, decision）
    replan_count: int                 # 已重规划次数（每次 replan_node 执行时 +1）
    max_replan: int                   # 最大补充检索次数（默认 1，超过后带限制说明进入综合）


def new_state(
    *,
    workflow_id: str,
    user_id: str,
    research_group_id: str,
    conversation_id: str | None,
    query: str,
    task_type: str,
    route_decision: dict[str, Any] | None,
    context_budget: dict[str, Any] | None,
) -> WorkflowState:
    """创建全新的 WorkflowState。

    在以下场景被调用：
    1. gRPC 首次收到 RunWorkflow 请求时（grpc_server.py → _run_workflow）
    2. 恢复执行时重建状态（grpc_server.py → _resume_workflow_stream）

    参数说明：
    - workflow_id: 工作流唯一标识（Java 侧生成）
    - user_id / research_group_id: 用户和研究组标识（用于多租户隔离）
    - conversation_id: 会话 ID（None 时回退到 workflow_id）
    - query: 用户的原始问题
    - task_type: 任务类型（决定走哪条管线）
    - route_decision: Java 侧的路由决策对象
    - context_budget: 上下文预算配置

    返回值：
        初始化完成的 WorkflowState 字典，所有列表字段为空，状态为 RUNNING。
    """
    budget = context_budget or {}
    rd = route_decision or {}
    return {
        # ── 标识 ──
        "workflow_id": workflow_id,
        "user_id": user_id,
        "research_group_id": research_group_id,
        "conversation_id": conversation_id or workflow_id,
        # ── 输入 ──
        "query": query,
        "prepared_query": query,
        "query_prepare": {},
        "task_type": task_type,
        "route_decision": rd,
        "context_budget": budget,
        "prompt_context": str(budget.get("promptContext") or ""),
        # ── 检索结果（初始为空，由各 retrieve 节点填充）──
        "claims": [],
        "evidence_by_claim": [],
        "evidence_spans": [],
        "verification_results": [],
        "claim_evidence_table": [],
        "document_refs": [],
        # ── 可观测性（初始为空，由各节点追加）──
        "tool_observations": [],
        "traces": [],
        "warnings": [],
        "retrieval_diagnostics": {},
        "quality_report": {},
        # ── 输出 ──
        "final_answer": "",
        "status": "RUNNING",
        "step_index": 0,
        # ── Plan-Execute-Replan 状态 ──
        "plan_steps": [],          # 由 query_prepare/replan 填充检索步骤
        "evaluation": {},          # 由 evaluate_node 填充
        "replan_count": 0,         # replan_node 每次执行 +1
        "max_replan": 1,           # One bounded supplementary retrieval for evidence workflows.
    }


def _append_trace(state: WorkflowState, **kwargs: Any) -> list[dict[str, Any]]:
    """向 state 中追加一条执行追踪记录，并递增 step_index。

    每个节点执行完毕后都会调用此函数，生成一条 trace。
    trace 会被流式发送到 Java 侧，最终展示在前端的 Trace tab 中。

    返回值：更新后的 traces 列表（调用方需要将此返回值放入节点的输出 dict 中）。
    """
    traces = list(state.get("traces", []))
    traces.append(build_trace(workflow_id=state["workflow_id"], step_index=state.get("step_index", 0) + 1, **kwargs))
    state["step_index"] = state.get("step_index", 0) + 1
    return traces


def _append_warning(state: WorkflowState, warning: str):
    """向 state 中追加一条警告信息（自动去重）。"""
    warnings = list(state.get("warnings", []))
    if warning not in warnings:
        warnings.append(warning)
    state["warnings"] = warnings


def _split_claims(text: str) -> list[str]:
    """将用户输入的文本按句子边界拆分为原子声明列表。

    用于观点验证（claim_verification）和引用推荐（citation_recommendation）场景。
    例如："STRNN 提出了时空转移建模方法。它在 NYC 数据集上做了实验" →
          ["STRNN 提出了时空转移建模方法", "它在 NYC 数据集上做了实验"]

    拆分规则：
    - 中文句号、分号、问号、感叹号作为分隔符
    - 中文连接词（"并且"、"同时"、"以及"）作为分隔符
    - 英文句号、分号作为分隔符
    - 最多返回 5 条声明（避免过多拆分导致检索成本过高）
    """
    if not text:
        return []
    normalized = (
        text.replace("；", "。")
        .replace(";", ".")
        .replace("，并且", "。")
        .replace("，同时", "。")
        .replace("，以及", "。")
        .replace("\n", "。")
        .replace("?", "。")
        .replace("？", "。")
        .replace("!", "。")
        .replace("！", "。")
        .replace(" and ", ". ")
        .replace("。", ".")
    )
    claims = [segment.strip(" .\n\t") for segment in normalized.split(".") if segment.strip(" .\n\t")]
    return claims[:5] if claims else [text.strip()]


def _extract_spans(tool_data: Any) -> list[dict[str, Any]]:
    """从 retrieve_evidence 工具的返回数据中提取 evidenceSpans 列表。

    工具返回格式可能是：
    - 直接包含 evidenceSpans 字段：{"evidenceSpans": [...]}
    - 嵌套在 result 字段中：{"result": {"evidenceSpans": [...]}}

    返回值：evidence span 字典列表，每个 span 含 text, title, pageNumber, chunkIndex, relevanceScore 等字段。
    """
    if not isinstance(tool_data, dict):
        return []
    direct = tool_data.get("evidenceSpans") or []
    if direct:
        return [span for span in direct if isinstance(span, dict)]
    nested = tool_data.get("result")
    if isinstance(nested, dict):
        spans = nested.get("evidenceSpans") or []
        return [span for span in spans if isinstance(span, dict)]
    return []


def _format_evidence_text(spans: list[dict[str, Any]]) -> str:
    """将 evidence spans 格式化为可读文本（用于传给 LLM 做质量判断）。

    格式：[序号] 文本摘要 (source=标题, section=章节, page=页码, chunk=分块, score=相关度)
    最多取前 5 条，每条截断到 280 字符。
    """
    if not spans:
        return ""
    lines = []
    for index, span in enumerate(spans[:5], start=1):
        lines.append(
            f"[{index}] {preview(span.get('text', ''), 280)} "
            f"(source={span.get('title') or 'Unknown'}, section={span.get('section') or 'unknown'}, "
            f"page={span.get('pageNumber')}, chunk={span.get('chunkIndex')}, score={span.get('relevanceScore')})"
        )
    return "\n".join(lines)


_PLAN_STEP_TERMINAL_STATUSES = {"DONE", "EMPTY", "FAILED", "SKIPPED"}


def _plan_step_value(step: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = step.get(key)
        if value is not None and str(value).strip():
            return value
    return None


def _plan_step_query(step: dict[str, Any]) -> str:
    value = _plan_step_value(step, "search_query", "searchQuery", "query")
    return str(value or "").strip()


def _plan_step_goal(step: dict[str, Any], fallback: str) -> str:
    value = _plan_step_value(step, "description", "goal", "reason", "title")
    return str(value or fallback or "").strip()


def _plan_step_id(step: dict[str, Any], index: int) -> Any:
    return step.get("step_id") or step.get("stepId") or index + 1


def _normalize_plan_steps(
    steps: list[Any],
    fallback_query: str,
    source: str,
    start_index: int = 0,
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(steps or []):
        step = dict(raw) if isinstance(raw, dict) else {"description": str(raw)}
        if not _plan_step_query(step):
            step["search_query"] = _plan_step_goal(step, fallback_query) or fallback_query
        if not step.get("tool_hint") and not step.get("toolHint"):
            step["tool_hint"] = "retrieve_evidence"
        if not step.get("step_id") and not step.get("stepId"):
            step["step_id"] = start_index + len(normalized) + 1
        step.setdefault("status", "PENDING")
        step.setdefault("source", source)
        normalized.append(step)
    return normalized


def _pending_retrieval_targets(state: WorkflowState, fallback_queries: list[str]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], bool]:
    """Return retrieval targets; prepared/replanned steps drive retrieval when present."""
    plan_steps = [dict(step) for step in state.get("plan_steps", []) if isinstance(step, dict)]
    plan_driven = bool(plan_steps)

    if plan_driven:
        targets: list[dict[str, Any]] = []
        for index, step in enumerate(plan_steps):
            status = str(step.get("status") or "PENDING").upper()
            if status in _PLAN_STEP_TERMINAL_STATUSES:
                continue

            tool_hint = str(step.get("tool_hint") or step.get("toolHint") or "retrieve_evidence").strip()
            if tool_hint and tool_hint not in {"retrieve_evidence", "retrieve-evidence"}:
                step["status"] = "SKIPPED"
                step["statusReason"] = f"Unsupported workflow plan tool: {tool_hint}"
                plan_steps[index] = step
                continue

            query = _plan_step_query(step)
            if not query:
                step["status"] = "SKIPPED"
                step["statusReason"] = "Plan step did not include a search query."
                plan_steps[index] = step
                continue

            targets.append({
                "kind": "plan_step",
                "index": index,
                "query": query,
                "claim": _plan_step_goal(step, query),
                "step": step,
                "planStepId": _plan_step_id(step, index),
            })
        return plan_steps, targets, True

    targets = [
        {"kind": "fallback", "index": None, "query": query, "claim": query, "step": None, "planStepId": None}
        for query in fallback_queries
        if query
    ]
    return plan_steps, targets, False


def _mark_plan_step_result(
    plan_steps: list[dict[str, Any]],
    target: dict[str, Any],
    *,
    query: str,
    tool_result: dict[str, Any],
    spans: list[dict[str, Any]],
) -> None:
    if target.get("kind") != "plan_step":
        return
    index = target.get("index")
    if not isinstance(index, int) or index < 0 or index >= len(plan_steps):
        return
    step = dict(plan_steps[index])
    if not tool_result.get("success"):
        status = "FAILED"
    else:
        status = "DONE" if spans else "EMPTY"
    step["status"] = status
    step["executed_query"] = query
    step["executedAt"] = now_iso()
    step["resultCount"] = len(spans)
    step["resultRefs"] = [
        {
            "documentId": span.get("documentId"),
            "pageNumber": span.get("pageNumber"),
            "chunkIndex": span.get("chunkIndex"),
            "score": span.get("relevanceScore"),
        }
        for span in spans[:5]
    ]
    if tool_result.get("errorMessage"):
        step["statusReason"] = tool_result.get("errorMessage")
    plan_steps[index] = step


# ---------------------------------------------------------------------------
# Fixed evidence-workflow nodes with one bounded evidence-repair loop
# ---------------------------------------------------------------------------

import json as _json


def query_prepare_node(state: WorkflowState) -> dict[str, Any]:
    """Rewrite the evidence query without creating a duplicate task plan.

    Workflow topology and tool calls remain fixed in code. This node only
    improves the wording used by the first retrieval round.
    """
    query = str(state.get("query") or "").strip()
    task_type = str(state.get("task_type") or "evidence_analysis")
    system_msg = """You prepare an academic evidence-retrieval query.
Preserve the user's meaning, named papers, models, datasets and time constraints.
Resolve vague pronouns only when context supports it. Do not create a task plan
or choose tools. Return strict JSON:
{"prepared_query":"...","search_terms":["..."],"clarification":""}"""
    try:
        result = chat_json(system_msg, f"task_type={task_type}\nquery={query}")
        prepared = str(result.get("prepared_query") or query).strip()
        terms = result.get("search_terms") if isinstance(result.get("search_terms"), list) else []
        clarification = str(result.get("clarification") or "").strip()
    except Exception as exc:
        prepared = query
        terms = []
        clarification = f"Query preparation fell back to the original text: {exc}"

    prepared = prepared or query
    payload = {
        "originalQuery": query,
        "preparedQuery": prepared,
        "searchTerms": [str(item) for item in terms[:10]],
        "clarification": clarification,
    }
    traces = _append_trace(
        state,
        step_type="NODE",
        display_name="Evidence Query Preparation",
        phase="query_prepare",
        input_data=query,
        output_data=payload,
        status="SUCCESS",
        summary="Prepared the evidence query without changing workflow topology.",
    )
    return {"prepared_query": prepared, "query_prepare": payload, "traces": traces}


def evaluate_node(state: WorkflowState) -> dict[str, Any]:
    """PER 第二步：评估当前证据对执行计划的覆盖度。

    调用 LLM 评估已检索的 evidence_spans 是否充分覆盖了 plan_steps 中的所有步骤。
    LLM 返回评估结果，包含：
    - coverage_score (0-1)：已覆盖步骤的比例
    - quality_score (0-1)：证据质量评分
    - gaps：未覆盖的步骤列表（含 step_id 和 reason）
    - decision："pass"（进入综合）| "replan"（需要补充检索）| "fail"（无法完成）

    安全机制：
    - 如果 LLM 调用失败，强制 decision="pass"（避免无限循环）
    - 如果 replan_count >= max_replan（默认 1），停止补充检索并带限制说明进入综合
    - 即使 LLM 返回 "replan"，超过重规划上限后也会强制进入综合阶段

    返回值：{"evaluation": {...}, "traces": [...]}
    """
    plan_steps = state.get("plan_steps", [])
    evidence_spans = state.get("evidence_spans", [])
    query = state.get("query", "")

    user_msg = (
        f"研究计划: {_json.dumps(plan_steps, ensure_ascii=False)}\n"
        f"已检索证据数量: {len(evidence_spans)}\n"
        f"证据摘要: {[e.get('text', '')[:200] for e in evidence_spans[:5]]}"
    )

    try:
        evaluation = chat_json(EVALUATE_SYSTEM_PROMPT, user_msg)
        if "decision" not in evaluation:
            raise ValueError("missing decision")
    except Exception as exc:
        has_evidence = bool(evidence_spans)
        can_replan = state.get("replan_count", 0) < state.get("max_replan", 1)
        evaluation = {
            "coverage_score": 0.6 if has_evidence else 0.0,
            "quality_score": 0.5 if has_evidence else 0.0,
            "gaps": [] if has_evidence else [{
                "step_id": "supplement-1",
                "reason": "No usable local evidence was retrieved.",
                "suggested_query": state.get("prepared_query") or query,
            }],
            "decision": "pass" if has_evidence else ("replan" if can_replan else "fail"),
            "summary": f"Deterministic evaluation fallback: {exc}",
        }

    # 强制约束：超过最大重规划次数后，强制 pass 进入综合阶段
    replan_count = state.get("replan_count", 0)
    max_replan = state.get("max_replan", 1)
    if evaluation.get("decision") == "replan" and replan_count >= max_replan:
        evaluation["decision"] = "pass"
        evaluation["gaps"] = []

    traces = _append_trace(
        state,
        step_type="NODE", display_name="Quality Evaluator",
        phase="evaluate", input_data={"plan_step_count": len(plan_steps)},
        output_data=evaluation, status="SUCCESS",
        summary=f"Coverage: {evaluation.get('coverage_score', 0):.0%}, Decision: {evaluation.get('decision', 'pass')}",
    )
    return {"evaluation": evaluation, "traces": traces}


def replan_node(state: WorkflowState) -> dict[str, Any]:
    """PER 第三步：根据评估结果中的 gaps 生成补充检索步骤。

    当 evaluate_node 的 decision="replan" 时，图流转到此节点。
    此节点调用 LLM 为每个 gap 生成补充计划步骤，然后：
    1. 将新步骤追加到 plan_steps 列表末尾
    2. 清空 evidence_spans（为补充检索腾出空间）
    3. 递增 replan_count（用于 evaluate_node 的上限检查）

    执行后，图会流转回对应的 retrieve 节点（由 _replan_target 路由函数决定），
    形成 plan → retrieve → evaluate → replan → retrieve 的循环。

    返回值：{"plan_steps": [...], "replan_count": N, "evidence_spans": [], "traces": [...]}
    """
    evaluation = state.get("evaluation", {})
    gaps = evaluation.get("gaps", [])
    plan_steps = list(state.get("plan_steps", []))

    user_msg = (
        f"当前计划: {_json.dumps(plan_steps, ensure_ascii=False)}\n"
        f"需要补充的步骤: {_json.dumps(gaps, ensure_ascii=False)}"
    )

    try:
        result = chat_json(REPLAN_SYSTEM_PROMPT, user_msg)
        new_steps = result.get("new_steps", [])
        if not isinstance(new_steps, list):
            new_steps = []
    except Exception:
        new_steps = []

    fallback_query = state.get("prepared_query") or state.get("query", "")
    new_steps = _normalize_plan_steps(new_steps, fallback_query, "replan", start_index=len(plan_steps))
    if not new_steps:
        new_steps = _normalize_plan_steps([{
            "step_id": f"supplement-{state.get('replan_count', 0) + 1}",
            "description": "Retry the uncovered evidence target with a focused query.",
            "tool_hint": "retrieve_evidence",
            "search_query": fallback_query,
        }], fallback_query, "replan-fallback", start_index=len(plan_steps))
    plan_steps.extend(new_steps)

    traces = _append_trace(
        state,
        step_type="NODE", display_name="Task Replanner",
        phase="replan", input_data={"gaps": gaps}, output_data=new_steps,
        status="SUCCESS", summary=f"Added {len(new_steps)} supplementary step(s).",
    )
    return {
        "plan_steps": plan_steps,
        "replan_count": state.get("replan_count", 0) + 1,
        "traces": traces,
    }


def _llm_synthesize(query: str, spans: list[dict[str, Any]], fallback_fn) -> str:
    """尝试使用 LLM 生成综合回答，失败时回退到模板拼接。

    这是所有 synthesize 节点的公共逻辑：
    1. 将 evidence spans 格式化为文本（取前 8 条，每条 500 字符）
    2. 调用 LLM 生成结构化综合回答
    3. 如果 LLM 调用失败或返回空回答，使用 fallback_fn 模板兜底

    参数：
    - query: 用户原始问题
    - spans: evidence spans 列表
    - fallback_fn: 兜底模板函数（每个 synthesize 节点提供自己的模板）

    返回值：综合回答文本（Markdown 格式）
    """
    if not spans:
        return fallback_fn(query, spans)
    evidence_text = "\n".join(
        f"[Evidence {i+1}] {s.get('text', '')[:500]}" for i, s in enumerate(spans[:8])
    )
    user_msg = f"问题: {query}\n\n证据:\n{evidence_text}"
    try:
        result = chat_json(SYNTHESIZE_SYSTEM_PROMPT, user_msg)
        answer = result.get("answer", "")
        if answer:
            return answer
    except Exception:
        pass
    return fallback_fn(query, spans)


def route_node(state: WorkflowState) -> dict[str, Any]:
    """路由入口节点 — 记录工作流的路由信息（纯追踪，不执行实际逻辑）。"""
    traces = _append_trace(
        state,
        step_type="ROUTER",
        display_name="Workflow Router",
        phase="router",
        input_data={"taskType": state.get("task_type"), "query": state.get("query")},
        output_data={"routeDecision": state.get("route_decision"), "taskType": state.get("task_type")},
        status="SUCCESS",
        summary=f"Workflow task routed to {state.get('task_type')}.",
    )
    return {"traces": traces}


def claim_decompose_node(state: WorkflowState) -> dict[str, Any]:
    """声明拆解节点 — 将用户的复杂声明拆解为多个原子声明。

    例如："STRNN 提出了时空转移建模方法，并且在 NYC 数据集上做了实验"
    → ["STRNN 提出了时空转移建模方法", "在 NYC 数据集上做了实验"]
    """
    query = state.get("prepared_query") or state.get("query", "")
    claims = _split_claims(query)
    traces = _append_trace(
        state,
        step_type="NODE",
        display_name="Claim Decomposer",
        phase="agent",
        input_data=query,
        output_data=claims,
        status="SUCCESS",
        summary=f"Decomposed into {len(claims)} claim(s).",
    )
    return {"claims": claims or [query], "traces": traces}


def claim_retrieve_node(state: WorkflowState) -> dict[str, Any]:
    """声明检索节点 — 为每个原子声明检索相关证据。

    遍历所有声明，对每个声明调用 retrieve_evidence 工具检索证据。
    将检索结果存入 evidence_by_claim 和 evidence_spans。
    """
    workflow_id = state["workflow_id"]
    user_id = state["user_id"]
    group_id = state["research_group_id"]
    conversation_id = state["conversation_id"]
    claims = state.get("claims", []) or [state.get("query", "")]
    plan_steps, retrieval_targets, plan_driven = _pending_retrieval_targets(state, claims)

    traces = list(state.get("traces", []))
    tool_observations = list(state.get("tool_observations", []))
    evidence_by_claim: list[dict[str, Any]] = list(state.get("evidence_by_claim", []))
    evidence_spans: list[dict[str, Any]] = list(state.get("evidence_spans", []))
    document_refs = list(state.get("document_refs", []))
    warnings = list(state.get("warnings", []))
    total_hits = 0

    for target in retrieval_targets:
        claim = target["claim"]
        query = target["query"]
        step_id = short_id("retrieve")
        tool_result = call_tool(
            "retrieve_evidence",
            workflow_id,
            user_id,
            group_id,
            "claim_retrieve",
            {"query": query, "claim": claim, "topK": 5},
            conversation_id=conversation_id,
            step_id=step_id,
            route_decision=state.get("route_decision"),
            context_budget=state.get("context_budget"),
        )
        spans = _extract_spans(tool_result.get("data"))
        _mark_plan_step_result(plan_steps, target, query=query, tool_result=tool_result, spans=spans)
        total_hits += len(spans)
        evidence_text = _format_evidence_text(spans)
        evidence_by_claim.append(
            {
                "claim": claim,
                "query": query,
                "planStepId": target.get("planStepId"),
                "toolResult": tool_result,
                "spans": spans,
                "evidenceText": evidence_text,
            }
        )
        evidence_spans.extend(spans)
        document_refs.extend([evidence_to_document_ref(span, group_id) for span in spans if span.get("documentId")])

        summary = f"Retrieved {len(spans)} evidence span(s) for claim."
        if not spans:
            warning = f"No evidence retrieved for claim: {preview(claim, 120)}"
            if warning not in warnings:
                warnings.append(warning)
            summary = "No evidence spans retrieved for claim."

        tool_observations.append(
            build_tool_observation(
                workflow_id=workflow_id,
                step_id=step_id,
                tool_name="retrieve_evidence",
                tool_result=tool_result,
                summary=summary,
                evidence=spans[:5],
                citations=[evidence_to_document_ref(span, group_id) for span in spans[:5]],
            )
        )

        state["step_index"] = state.get("step_index", 0) + 1
        traces.append(
            build_trace(
                workflow_id=workflow_id,
                step_index=state["step_index"],
                step_type="TOOL",
                tool_name="retrieve_evidence",
                display_name="Evidence Retrieval",
                phase="tool",
                input_data={"claim": claim, "query": query, "planStepId": target.get("planStepId")},
                output_data=summary,
                status="SUCCESS" if tool_result.get("success") else "FAILED",
                latency_ms=int(tool_result.get("latencyMs") or 0),
                error_message=tool_result.get("errorMessage"),
                summary=summary,
                metadata={"claim": claim, "query": query, "planStepId": target.get("planStepId")},
            )
        )

    retrieval_diagnostics = {
        "query": state.get("query"),
        "claimCount": len(claims),
        "planDriven": plan_driven,
        "retrievalTargetCount": len(retrieval_targets),
        "retrievalHits": total_hits,
        "warnings": warnings,
        "suggestions": (
            []
            if total_hits > 0
            else ["Upload relevant papers or confirm that the workflow is bound to the correct researchGroupId."]
        ),
    }
    return {
        "evidence_by_claim": evidence_by_claim,
        "evidence_spans": evidence_spans,
        "document_refs": document_refs,
        "tool_observations": tool_observations,
        "traces": traces,
        "warnings": warnings,
        "retrieval_diagnostics": retrieval_diagnostics,
        "plan_steps": plan_steps,
    }


def claim_critic_node(state: WorkflowState) -> dict[str, Any]:
    """声明评审节点 — 评估每个声明的证据支持状态。

    对每个声明调用 judge_evidence_quality 工具，判断证据是否支持该声明。
    生成 verification_results（验证结果）和 claim_evidence_table（声明-证据对照表）。
    """
    workflow_id = state["workflow_id"]
    user_id = state["user_id"]
    group_id = state["research_group_id"]
    conversation_id = state["conversation_id"]
    traces = list(state.get("traces", []))
    tool_observations = list(state.get("tool_observations", []))
    warnings = list(state.get("warnings", []))
    verification_results: list[dict[str, Any]] = []
    claim_evidence_table: list[dict[str, Any]] = []

    for bundle in state.get("evidence_by_claim", []):
        claim = bundle.get("claim", "")
        spans = bundle.get("spans", [])
        evidence_text = bundle.get("evidenceText", "")
        step_id = short_id("critic")

        if not evidence_text:
            verification = {
                "claim": claim,
                "supportStatus": "IRRELEVANT",
                "reason": "No evidence was retrieved from the current literature library.",
                "confidence": 0.0,
            }
            warning = f"Claim has no retrieved evidence: {preview(claim, 120)}"
            if warning not in warnings:
                warnings.append(warning)
            tool_result = {"success": False, "errorMessage": verification["reason"], "latencyMs": 0}
        else:
            tool_result = call_tool(
                "judge_evidence_quality",
                workflow_id,
                user_id,
                group_id,
                "claim_critic",
                {"claim": claim, "evidence": evidence_text},
                conversation_id=conversation_id,
                step_id=step_id,
                route_decision=state.get("route_decision"),
                context_budget=state.get("context_budget"),
            )
            quality = tool_result.get("data") if isinstance(tool_result.get("data"), dict) else {}
            verification = {
                "claim": claim,
                "supportStatus": quality.get("judgment", "IRRELEVANT"),
                "reason": quality.get("reason", ""),
                "confidence": quality.get("confidence", 0.0),
            }

        best_span = spans[0] if spans else {}
        verification_results.append(verification)
        claim_evidence_table.append(
            {
                "claim": claim,
                "status": verification["supportStatus"],
                "supportStatus": verification["supportStatus"],
                "reason": verification["reason"],
                "confidence": verification["confidence"],
                "evidence": preview(best_span.get("text", ""), 400),
                "documentId": best_span.get("documentId"),
                "pageNumber": best_span.get("pageNumber"),
                "chunkIndex": best_span.get("chunkIndex"),
                "relevanceScore": best_span.get("relevanceScore"),
                "source": evidence_to_document_ref(best_span, group_id) if best_span else None,
            }
        )
        tool_observations.append(
            build_tool_observation(
                workflow_id=workflow_id,
                step_id=step_id,
                tool_name="judge_evidence_quality",
                tool_result=tool_result,
                summary=f"{verification['supportStatus']}: {preview(verification['reason'], 220)}",
                evidence=spans[:3],
                citations=[evidence_to_document_ref(span, group_id) for span in spans[:3]],
            )
        )

        state["step_index"] = state.get("step_index", 0) + 1
        traces.append(
            build_trace(
                workflow_id=workflow_id,
                step_index=state["step_index"],
                step_type="TOOL",
                tool_name="judge_evidence_quality",
                display_name="Claim Critic",
                phase="agent",
                input_data={"claim": claim, "evidenceCount": len(spans)},
                output_data=verification,
                status="SUCCESS" if spans and verification["supportStatus"] != "IRRELEVANT" else "WARNING",
                summary=f"{verification['supportStatus']} for claim.",
            )
        )

    return {
        "verification_results": verification_results,
        "claim_evidence_table": claim_evidence_table,
        "tool_observations": tool_observations,
        "traces": traces,
        "warnings": warnings,
    }


def claim_synthesize_node(state: WorkflowState) -> dict[str, Any]:
    """声明综合节点 — 根据验证结果生成最终的观点验证回答。

    将 claim_evidence_table 传给 LLM，生成结构化的验证报告。
    如果 LLM 调用失败，使用模板拼接兜底。
    """
    rows = state.get("claim_evidence_table", [])
    query = state.get("query", "")
    spans = state.get("evidence_spans", [])

    def _fallback(q, s):
        lines = ["## Claim Verification Result", "", f"Original claim: {q}", ""]
        if not rows:
            lines.append("No verifiable evidence was produced.")
        else:
            for idx, row in enumerate(rows, start=1):
                lines.append(f"{idx}. {row.get('claim', '')}")
                lines.append(f"   - Status: {row.get('supportStatus', row.get('status', 'UNKNOWN'))}")
                lines.append(f"   - Reason: {row.get('reason', 'No rationale')}")
                if row.get("evidence"):
                    lines.append(f"   - Evidence: {row.get('evidence')}")
        return "\n".join(lines)

    # For claim verification, pass claim_evidence_table to LLM
    if rows:
        evidence_text = "\n".join(
            f"[Claim {i+1}] {r.get('claim', '')} → {r.get('supportStatus', 'UNKNOWN')}: {r.get('reason', '')}"
            for i, r in enumerate(rows)
        )
        user_msg = f"原始主张: {query}\n\n验证结果:\n{evidence_text}"
        try:
            result = chat_json(SYNTHESIZE_SYSTEM_PROMPT, user_msg)
            final_answer = result.get("answer", "")
            if not final_answer:
                raise ValueError("empty answer")
        except Exception:
            final_answer = _fallback(query, spans)
    else:
        final_answer = _fallback(query, spans)

    traces = _append_trace(
        state, step_type="NODE",
        display_name="Workflow Synthesis", phase="synthesize",
        input_data=rows, output_data=final_answer,
        status="SUCCESS", summary="Synthesized claim verification answer.",
    )
    return {"final_answer": final_answer, "traces": traces}


def paper_retrieve_node(state: WorkflowState) -> dict[str, Any]:
    """文献问答检索节点 — 根据用户问题检索相关证据。"""
    workflow_id = state["workflow_id"]
    user_id = state["user_id"]
    group_id = state["research_group_id"]
    conversation_id = state["conversation_id"]
    query = state.get("query", "")
    step_id = short_id("paperqa")
    tool_result = call_tool(
        "retrieve_evidence",
        workflow_id,
        user_id,
        group_id,
        "paper_retrieve",
        {"query": query, "claim": query, "topK": 5},
        conversation_id=conversation_id,
        step_id=step_id,
        route_decision=state.get("route_decision"),
        context_budget=state.get("context_budget"),
    )
    spans = _extract_spans(tool_result.get("data"))
    summary = f"Retrieved {len(spans)} evidence span(s) for paper QA."
    warnings = list(state.get("warnings", []))
    if not spans:
        summary = "No evidence spans retrieved for paper QA."
        warning = "Paper QA did not retrieve usable evidence."
        if warning not in warnings:
            warnings.append(warning)

    group_refs = [evidence_to_document_ref(span, group_id) for span in spans if span.get("documentId")]
    tool_observations = list(state.get("tool_observations", []))
    tool_observations.append(
        build_tool_observation(
            workflow_id=workflow_id,
            step_id=step_id,
            tool_name="retrieve_evidence",
            tool_result=tool_result,
            summary=summary,
            evidence=spans[:5],
            citations=[evidence_to_document_ref(span, group_id) for span in spans[:5]],
        )
    )

    traces = _append_trace(
        state,
        step_type="TOOL",
        tool_name="retrieve_evidence",
        display_name="Paper QA Retrieval",
        phase="tool",
        input_data={"query": query},
        output_data=summary,
        status="SUCCESS" if tool_result.get("success") else "FAILED",
        latency_ms=int(tool_result.get("latencyMs") or 0),
        error_message=tool_result.get("errorMessage"),
        summary=summary,
    )
    retrieval_diagnostics = {
        "query": query,
        "retrievalHits": len(spans),
        "warnings": warnings,
        "suggestions": (
            []
            if spans
            else ["Upload relevant papers or narrow the question scope before retrying Paper QA."]
        ),
    }
    return {
        "evidence_spans": spans,
        "document_refs": group_refs,
        "tool_observations": tool_observations,
        "traces": traces,
        "warnings": warnings,
        "retrieval_diagnostics": retrieval_diagnostics,
    }


def paper_synthesize_node(state: WorkflowState) -> dict[str, Any]:
    """文献问答综合节点 — 根据检索到的证据生成最终回答。"""
    spans = state.get("evidence_spans", [])
    query = state.get("query", "")

    def _fallback(q, s):
        lines = ["## Literature QA Result", "", f"Question: {q}", ""]
        if s:
            lines.append("Based on the retrieved evidence:")
            for idx, span in enumerate(s[:5], start=1):
                lines.append(f"{idx}. {preview(span.get('text', ''), 320)} [Evidence {idx}]")
                lines.append(f"   Source: {span.get('title') or 'Unknown'} / {span.get('section') or 'unknown'}"
                    + (f" / page {span.get('pageNumber')}" if span.get("pageNumber") is not None else "")
                    + f" / chunk {span.get('chunkIndex')}")
        else:
            lines.append("No sufficient evidence was retrieved from the current literature library.")
        return "\n".join(lines)

    final_answer = _llm_synthesize(query, spans, _fallback)
    traces = _append_trace(
        state, step_type="NODE",
        display_name="Paper QA Synthesis", phase="synthesize",
        input_data={"evidenceCount": len(spans)}, output_data=final_answer,
        status="SUCCESS" if spans else "WARNING", summary="Synthesized paper QA answer.",
    )
    return {"final_answer": final_answer, "traces": traces}


# ---------------------------------------------------------------------------
# Related Work pipeline
# ---------------------------------------------------------------------------

def related_work_retrieve_node(state: WorkflowState) -> dict[str, Any]:
    """文献综述检索节点 — 检索与研究主题相关的文献证据。"""
    workflow_id = state["workflow_id"]
    user_id = state["user_id"]
    group_id = state["research_group_id"]
    conversation_id = state["conversation_id"]
    query = state.get("prepared_query") or state.get("query", "")
    plan_steps, retrieval_targets, plan_driven = _pending_retrieval_targets(state, [query])
    evidence_spans = list(state.get("evidence_spans", []))
    group_refs = list(state.get("document_refs", []))
    warnings = list(state.get("warnings", []))
    tool_observations = list(state.get("tool_observations", []))
    traces = list(state.get("traces", []))
    total_hits = 0

    for target in retrieval_targets:
        target_query = target["query"]
        step_id = short_id("relwork")
        tool_result = call_tool(
            "retrieve_evidence",
            workflow_id, user_id, group_id,
            "related_work_retrieve",
            {"query": target_query, "claim": target["claim"], "topK": 8},
            conversation_id=conversation_id,
            step_id=step_id,
            route_decision=state.get("route_decision"),
            context_budget=state.get("context_budget"),
        )
        spans = _extract_spans(tool_result.get("data"))
        _mark_plan_step_result(plan_steps, target, query=target_query, tool_result=tool_result, spans=spans)
        total_hits += len(spans)
        evidence_spans.extend(spans)
        group_refs.extend([evidence_to_document_ref(s, group_id) for s in spans if s.get("documentId")])

        summary = f"Retrieved {len(spans)} evidence span(s) for related work."
        if not spans:
            summary = "No evidence spans retrieved for related work."
            warning = f"Related Work did not retrieve usable evidence for: {preview(target_query, 120)}"
            if warning not in warnings:
                warnings.append(warning)

        tool_observations.append(build_tool_observation(
            workflow_id=workflow_id, step_id=step_id,
            tool_name="retrieve_evidence", tool_result=tool_result,
            summary=summary, evidence=spans[:8],
            citations=[evidence_to_document_ref(s, group_id) for s in spans[:8]],
        ))
        state["step_index"] = state.get("step_index", 0) + 1
        traces.append(build_trace(
            workflow_id=workflow_id, step_index=state["step_index"],
            step_type="TOOL",
            tool_name="retrieve_evidence", display_name="Related Work Retrieval",
            phase="tool", input_data={"query": target_query, "planStepId": target.get("planStepId")},
            output_data=summary,
            status="SUCCESS" if tool_result.get("success") else "FAILED",
            latency_ms=int(tool_result.get("latencyMs") or 0),
            error_message=tool_result.get("errorMessage"), summary=summary,
            metadata={"query": target_query, "planStepId": target.get("planStepId")},
        ))

    retrieval_diagnostics = {
        "query": query,
        "planDriven": plan_driven,
        "retrievalTargetCount": len(retrieval_targets),
        "retrievalHits": total_hits,
        "warnings": warnings,
    }
    return {"evidence_spans": evidence_spans, "document_refs": group_refs,
            "tool_observations": tool_observations, "traces": traces, "warnings": warnings,
            "retrieval_diagnostics": retrieval_diagnostics, "plan_steps": plan_steps}


def related_work_synthesize_node(state: WorkflowState) -> dict[str, Any]:
    """文献综述综合节点 — 根据检索到的证据生成 Related Work 综述。"""
    spans = state.get("evidence_spans", [])
    query = state.get("query", "")

    def _fallback(q, s):
        lines = ["## Related Work Survey", "", f"Topic: {q}", ""]
        if s:
            lines.append("Based on the retrieved literature:")
            lines.append("### Key Papers and Contributions")
            for idx, span in enumerate(s[:8], start=1):
                lines.append(f"{idx}. {preview(span.get('text', ''), 320)} [Evidence {idx}]")
                lines.append(f"   Source: {span.get('title') or 'Unknown'} / {span.get('section') or 'unknown'}")
        else:
            lines.append("No sufficient evidence was retrieved from the current literature library.")
        return "\n".join(lines)

    final_answer = _llm_synthesize(query, spans, _fallback)
    traces = _append_trace(state,
        step_type="NODE",
        display_name="Related Work Synthesis", phase="synthesize",
        input_data={"evidenceCount": len(spans)}, output_data=final_answer,
        status="SUCCESS" if spans else "WARNING", summary="Synthesized related work survey.",
    )
    return {"final_answer": final_answer, "traces": traces}


# ---------------------------------------------------------------------------
# Paper Compare pipeline
# ---------------------------------------------------------------------------

def paper_compare_retrieve_node(state: WorkflowState) -> dict[str, Any]:
    """论文对比检索节点 — 检索多篇论文的相关证据用于对比分析。"""
    workflow_id = state["workflow_id"]
    user_id = state["user_id"]
    group_id = state["research_group_id"]
    conversation_id = state["conversation_id"]
    query = state.get("prepared_query") or state.get("query", "")
    plan_steps, retrieval_targets, plan_driven = _pending_retrieval_targets(state, [query])
    evidence_spans = list(state.get("evidence_spans", []))
    group_refs = list(state.get("document_refs", []))
    warnings = list(state.get("warnings", []))
    tool_observations = list(state.get("tool_observations", []))
    traces = list(state.get("traces", []))
    total_hits = 0

    for target in retrieval_targets:
        target_query = target["query"]
        step_id = short_id("compare")
        tool_result = call_tool(
            "retrieve_evidence",
            workflow_id, user_id, group_id,
            "paper_compare_retrieve",
            {"query": target_query, "claim": target["claim"], "topK": 10},
            conversation_id=conversation_id,
            step_id=step_id,
            route_decision=state.get("route_decision"),
            context_budget=state.get("context_budget"),
        )
        spans = _extract_spans(tool_result.get("data"))
        _mark_plan_step_result(plan_steps, target, query=target_query, tool_result=tool_result, spans=spans)
        total_hits += len(spans)
        evidence_spans.extend(spans)
        group_refs.extend([evidence_to_document_ref(s, group_id) for s in spans if s.get("documentId")])

        summary = f"Retrieved {len(spans)} evidence span(s) for paper comparison."
        if not spans:
            summary = "No evidence spans retrieved for paper comparison."
            warning = f"Paper Compare did not retrieve usable evidence for: {preview(target_query, 120)}"
            if warning not in warnings:
                warnings.append(warning)

        tool_observations.append(build_tool_observation(
            workflow_id=workflow_id, step_id=step_id,
            tool_name="retrieve_evidence", tool_result=tool_result,
            summary=summary, evidence=spans[:10],
            citations=[evidence_to_document_ref(s, group_id) for s in spans[:10]],
        ))
        state["step_index"] = state.get("step_index", 0) + 1
        traces.append(build_trace(
            workflow_id=workflow_id, step_index=state["step_index"],
            step_type="TOOL",
            tool_name="retrieve_evidence", display_name="Paper Compare Retrieval",
            phase="tool", input_data={"query": target_query, "planStepId": target.get("planStepId")},
            output_data=summary,
            status="SUCCESS" if tool_result.get("success") else "FAILED",
            latency_ms=int(tool_result.get("latencyMs") or 0),
            error_message=tool_result.get("errorMessage"), summary=summary,
            metadata={"query": target_query, "planStepId": target.get("planStepId")},
        ))

    retrieval_diagnostics = {
        "query": query,
        "planDriven": plan_driven,
        "retrievalTargetCount": len(retrieval_targets),
        "retrievalHits": total_hits,
        "warnings": warnings,
    }
    return {"evidence_spans": evidence_spans, "document_refs": group_refs,
            "tool_observations": tool_observations, "traces": traces, "warnings": warnings,
            "retrieval_diagnostics": retrieval_diagnostics, "plan_steps": plan_steps}


def paper_compare_synthesize_node(state: WorkflowState) -> dict[str, Any]:
    """论文对比综合节点 — 根据检索到的证据生成结构化对比分析。"""
    spans = state.get("evidence_spans", [])
    query = state.get("query", "")

    def _fallback(q, s):
        lines = ["## Paper Comparison", "", f"Question: {q}", ""]
        if s:
            lines.append("Based on the retrieved evidence, here is a structured comparison:")
            docs: dict[str, list] = {}
            for span in s:
                title = span.get("title") or "Unknown"
                docs.setdefault(title, []).append(span)
            for doc_title, doc_spans in list(docs.items())[:5]:
                lines.append(f"### {doc_title}")
                for sp in doc_spans[:3]:
                    lines.append(f"- [{sp.get('section') or 'unknown'}] {preview(sp.get('text', ''), 280)}")
        else:
            lines.append("No sufficient evidence was retrieved for comparison.")
        return "\n".join(lines)

    final_answer = _llm_synthesize(query, spans, _fallback)
    traces = _append_trace(state,
        step_type="NODE",
        display_name="Paper Compare Synthesis", phase="synthesize",
        input_data={"evidenceCount": len(spans)}, output_data=final_answer,
        status="SUCCESS" if spans else "WARNING", summary="Synthesized paper comparison.",
    )
    return {"final_answer": final_answer, "traces": traces}


# ---------------------------------------------------------------------------
# Citation Recommendation pipeline
# ---------------------------------------------------------------------------

def citation_extract_node(state: WorkflowState) -> dict[str, Any]:
    """引用提取节点 — 从用户文本中提取需要引用支持的关键声明。"""
    query = state.get("prepared_query") or state.get("query", "")
    # Simple extraction: split by sentence boundaries
    claims = _split_claims(query)
    traces = _append_trace(state,
        step_type="NODE",
        display_name="Citation Claim Extraction", phase="agent",
        input_data=query, output_data=claims,
        status="SUCCESS", summary=f"Extracted {len(claims)} claim(s) for citation.",
    )
    return {"claims": claims or [query], "traces": traces}


def citation_retrieve_node(state: WorkflowState) -> dict[str, Any]:
    """引用检索节点 — 为每个声明检索相关的引用候选。"""
    workflow_id = state["workflow_id"]
    user_id = state["user_id"]
    group_id = state["research_group_id"]
    conversation_id = state["conversation_id"]
    claims = state.get("claims", []) or [state.get("query", "")]
    plan_steps, retrieval_targets, plan_driven = _pending_retrieval_targets(state, claims)

    traces = list(state.get("traces", []))
    tool_observations = list(state.get("tool_observations", []))
    evidence_spans: list[dict[str, Any]] = list(state.get("evidence_spans", []))
    document_refs = list(state.get("document_refs", []))
    warnings = list(state.get("warnings", []))
    total_hits = 0

    for target in retrieval_targets:
        claim = target["claim"]
        query = target["query"]
        step_id = short_id("cite")
        tool_result = call_tool(
            "retrieve_evidence",
            workflow_id, user_id, group_id,
            "citation_retrieve",
            {"query": query, "claim": claim, "topK": 3},
            conversation_id=conversation_id,
            step_id=step_id,
            route_decision=state.get("route_decision"),
            context_budget=state.get("context_budget"),
        )
        spans = _extract_spans(tool_result.get("data"))
        _mark_plan_step_result(plan_steps, target, query=query, tool_result=tool_result, spans=spans)
        total_hits += len(spans)
        evidence_spans.extend(spans)
        document_refs.extend([evidence_to_document_ref(s, group_id) for s in spans if s.get("documentId")])

        summary = f"Retrieved {len(spans)} citation candidate(s) for claim."
        if not spans:
            warning = f"No citation candidates for: {preview(query, 120)}"
            if warning not in warnings:
                warnings.append(warning)
            summary = "No citation candidates retrieved."

        tool_observations.append(build_tool_observation(
            workflow_id=workflow_id, step_id=step_id,
            tool_name="retrieve_evidence", tool_result=tool_result,
            summary=summary, evidence=spans[:3],
            citations=[evidence_to_document_ref(s, group_id) for s in spans[:3]],
        ))
        state["step_index"] = state.get("step_index", 0) + 1
        traces.append(build_trace(
            workflow_id=workflow_id, step_index=state["step_index"],
            step_type="TOOL",
            tool_name="retrieve_evidence", display_name="Citation Retrieval",
            phase="tool", input_data={"claim": claim, "query": query, "planStepId": target.get("planStepId")},
            output_data=summary,
            status="SUCCESS" if tool_result.get("success") else "FAILED",
            latency_ms=int(tool_result.get("latencyMs") or 0),
            error_message=tool_result.get("errorMessage"), summary=summary,
            metadata={"claim": claim, "query": query, "planStepId": target.get("planStepId")},
        ))

    retrieval_diagnostics = {
        "query": state.get("query"),
        "planDriven": plan_driven,
        "retrievalTargetCount": len(retrieval_targets),
        "retrievalHits": total_hits,
        "warnings": warnings,
    }
    return {"evidence_spans": evidence_spans, "document_refs": document_refs,
            "tool_observations": tool_observations, "traces": traces, "warnings": warnings,
            "retrieval_diagnostics": retrieval_diagnostics, "plan_steps": plan_steps}


def citation_synthesize_node(state: WorkflowState) -> dict[str, Any]:
    """引用综合节点 — 根据检索结果生成引用推荐列表。"""
    spans = state.get("evidence_spans", [])
    query = state.get("query", "")

    def _fallback(q, s):
        lines = ["## Citation Recommendations", "", f"Text: {q}", ""]
        if s:
            lines.append("Recommended citations for your text:")
            for idx, span in enumerate(s[:6], start=1):
                title = span.get("title") or "Unknown"
                score = span.get("relevanceScore", 0)
                lines.append(f"{idx}. **{title}** (relevance: {score:.2f})")
                lines.append(f"   Evidence: {preview(span.get('text', ''), 250)}")
        else:
            lines.append("No suitable citations found in the current literature library.")
        return "\n".join(lines)

    final_answer = _llm_synthesize(query, spans, _fallback)
    traces = _append_trace(state,
        step_type="NODE",
        display_name="Citation Synthesis", phase="synthesize",
        input_data={"evidenceCount": len(spans)}, output_data=final_answer,
        status="SUCCESS" if spans else "WARNING", summary="Synthesized citation recommendations.",
    )
    return {"final_answer": final_answer, "traces": traces}


def unsupported_node(state: WorkflowState) -> dict[str, Any]:
    """Return an explicit error for a task outside the workflow catalog."""
    final_answer = (
        "This task is not registered in the fixed evidence-workflow catalog. "
        "Please use Auto mode or choose a supported evidence task."
    )
    _append_warning(state, f"Unsupported workflow task type: {state.get('task_type')}")
    traces = _append_trace(
        state,
        step_type="ROUTER",
        display_name="Workflow Router",
        phase="router",
        input_data=state.get("task_type"),
        output_data=final_answer,
        status="WARNING",
        summary="Unsupported workflow task type was rejected by the workflow router.",
    )
    return {
        "final_answer": final_answer,
        "warnings": state.get("warnings", []),
        "traces": traces,
    }


def monitor_node(state: WorkflowState) -> dict[str, Any]:
    """质量监控节点 — 收集所有警告并生成质量报告。"""
    warnings = list(state.get("warnings", []))
    passed = len(warnings) == 0
    quality_report = {
        "passed": passed,
        "warnings": [
            {"type": "workflow_warning", "severity": "WARNING", "message": warning}
            for warning in warnings
        ],
        "checkedAt": now_iso(),
    }
    traces = _append_trace(
        state,
        step_type="MONITOR",
        display_name="Workflow Monitor",
        phase="monitor",
        input_data={"warningCount": len(warnings)},
        output_data=quality_report,
        status="SUCCESS" if passed else "WARNING",
        summary=f"Workflow monitor completed with {len(warnings)} warning(s).",
    )
    return {"quality_report": quality_report, "traces": traces}


def finish_node(state: WorkflowState) -> dict[str, Any]:
    """完成节点 — 设置最终状态为 COMPLETED。"""
    return {
        "final_answer": state.get("final_answer") or "Workflow completed.",
        "status": "COMPLETED",
    }


def route_from_start(state: WorkflowState) -> str:
    """图入口路由：根据 task_type 选择固定 Workflow Template。

    路由逻辑：
    - evidence tasks → query_prepare, then a fixed task-specific template
    - paper_qa → paper_retrieve
    - deep_research → unsupported（应由 ReAct DeepResearch Agent 接管）

    返回值：下一个节点的名称（"query_prepare" / "paper_retrieve" / ...）
    """
    task_type = (state.get("task_type") or "").lower()
    if task_type == "claim_verification":
        return "query_prepare"
    if task_type == "paper_qa":
        return "paper_retrieve"
    if task_type in {"related_work", "paper_compare", "citation_recommendation"}:
        return "query_prepare"
    if task_type == "deep_research":
        return "unsupported"
    return "unsupported"


def route_after_query_prepare(state: WorkflowState) -> str:
    """Select the fixed workflow template after query wording is prepared."""
    return {
        "claim_verification": "claim_decompose",
        "related_work": "related_work_retrieve",
        "paper_compare": "paper_compare_retrieve",
        "citation_recommendation": "citation_extract",
    }.get((state.get("task_type") or "").lower(), "unsupported")


def route_after_evaluate(state: WorkflowState) -> str:
    """评估后路由：evaluate_node 执行完毕后，决定下一步走向。

    路由逻辑：
    - evaluation.decision == "replan" → replan_node（补充检索）
    - evaluation.decision == "pass" 或 "fail" → 对应 task_type 的 synthesize 节点（生成最终回答）

    返回值：下一个节点的名称（"replan" / "claim_synthesize" / "related_work_synthesize" / ...）
    """
    evaluation = state.get("evaluation", {})
    if evaluation.get("decision") == "replan":
        return "replan"
    task_type = (state.get("task_type") or "").lower()
    return {
        "claim_verification": "claim_critic",
        "related_work": "related_work_synthesize",
        "paper_compare": "paper_compare_synthesize",
        "citation_recommendation": "citation_synthesize",
    }.get(task_type, "claim_synthesize")


def _replan_target(state: WorkflowState) -> str:
    """重规划后路由：replan_node 执行完毕后，决定回到哪个 retrieve 节点。

    这是 PER 循环的关键——replan 后回到对应的 retrieve 节点重新检索补充步骤的证据。
    不同 task_type 回到不同的 retrieve 节点（因为每个管线的检索逻辑不同）。

    返回值：retrieve 节点名称（"claim_retrieve" / "related_work_retrieve" / ...）
    """
    task_type = (state.get("task_type") or "").lower()
    return {
        "claim_verification": "claim_retrieve",
        "related_work": "related_work_retrieve",
        "paper_compare": "paper_compare_retrieve",
        "citation_recommendation": "citation_retrieve",
    }.get(task_type, "claim_retrieve")


def build_workflow_graph():
    """构建完整的 LangGraph 工作流图。

    图结构包含 20 个节点，通过条件边实现：
    1. 入口分流：route_from_start 根据 task_type 选择固定模板
    2. 证据修复循环：query_prepare → retrieve → evaluate → (replan → retrieve)* → synthesize
    3. 快速文献问答：retrieve → synthesize
    4. 深度研究不再进入本图；由 ReAct DeepResearch Agent 独立执行。

    所有管线最终汇聚到 monitor → finish → END。

    返回值：编译后的 LangGraph CompiledGraph（可直接调用 .stream() 执行）
    """
    graph = StateGraph(WorkflowState)

    # ── 注册所有节点（20 个）──
    graph.add_node("route", route_node)
    graph.add_node("query_prepare", query_prepare_node)
    graph.add_node("evaluate", evaluate_node)
    graph.add_node("replan", replan_node)
    graph.add_node("claim_decompose", claim_decompose_node)
    graph.add_node("claim_retrieve", claim_retrieve_node)
    graph.add_node("claim_critic", claim_critic_node)
    graph.add_node("claim_synthesize", claim_synthesize_node)
    graph.add_node("paper_retrieve", paper_retrieve_node)
    graph.add_node("paper_synthesize", paper_synthesize_node)
    graph.add_node("related_work_retrieve", related_work_retrieve_node)
    graph.add_node("related_work_synthesize", related_work_synthesize_node)
    graph.add_node("paper_compare_retrieve", paper_compare_retrieve_node)
    graph.add_node("paper_compare_synthesize", paper_compare_synthesize_node)
    graph.add_node("citation_extract", citation_extract_node)
    graph.add_node("citation_retrieve", citation_retrieve_node)
    graph.add_node("citation_synthesize", citation_synthesize_node)
    graph.add_node("unsupported", unsupported_node)
    graph.add_node("monitor", monitor_node)
    graph.add_node("finish", finish_node)

    # ── 入口路由：route 节点根据 task_type 选择固定模板 ──
    graph.set_entry_point("route")
    graph.add_conditional_edges("route", route_from_start, {
        "query_prepare": "query_prepare",
        "claim_decompose": "claim_decompose",
        "paper_retrieve": "paper_retrieve",
        "related_work_retrieve": "related_work_retrieve",
        "paper_compare_retrieve": "paper_compare_retrieve",
        "citation_extract": "citation_extract",
        "unsupported": "unsupported",
    })

    # Query preparation cannot alter topology; task type selects a fixed template.
    graph.add_conditional_edges("query_prepare", route_after_query_prepare, {
        "claim_decompose": "claim_decompose",
        "related_work_retrieve": "related_work_retrieve",
        "paper_compare_retrieve": "paper_compare_retrieve",
        "citation_extract": "citation_extract",
        "unsupported": "unsupported",
    })

    # ── Claim Verification 固定模板 ──
    # claim_decompose → claim_retrieve → evaluate → (replan → retrieve)?
    # → claim_critic → claim_synthesize
    graph.add_edge("claim_decompose", "claim_retrieve")
    graph.add_edge("claim_retrieve", "evaluate")
    graph.add_edge("claim_critic", "claim_synthesize")
    graph.add_edge("claim_synthesize", "monitor")

    # ── Paper QA 管线（文献问答，始终线性，不走 PER）──
    # paper_retrieve → paper_synthesize → monitor
    graph.add_edge("paper_retrieve", "paper_synthesize")
    graph.add_edge("paper_synthesize", "monitor")

    # ── Related Work 管线（文献综述）──
    graph.add_edge("related_work_retrieve", "evaluate")
    graph.add_edge("related_work_synthesize", "monitor")

    # ── Paper Compare 管线（论文对比）──
    graph.add_edge("paper_compare_retrieve", "evaluate")
    graph.add_edge("paper_compare_synthesize", "monitor")

    # ── Citation Recommendation 管线（引用推荐）──
    graph.add_edge("citation_extract", "citation_retrieve")
    graph.add_edge("citation_retrieve", "evaluate")
    graph.add_edge("citation_synthesize", "monitor")

    # Unsupported
    graph.add_edge("unsupported", "monitor")

    # ── PER 循环核心：evaluate → (synthesize | replan → retrieve) ──
    # evaluate 的 decision 决定走哪条路：pass → synthesize，replan → replan → retrieve（循环）
    graph.add_conditional_edges("evaluate", route_after_evaluate, {
        "replan": "replan",
        "claim_critic": "claim_critic",
        "related_work_synthesize": "related_work_synthesize",
        "paper_compare_synthesize": "paper_compare_synthesize",
        "citation_synthesize": "citation_synthesize",
    })

    # ── replan 后回到对应的 retrieve 节点（形成循环）──
    graph.add_conditional_edges("replan", _replan_target, {
        "claim_retrieve": "claim_retrieve",
        "related_work_retrieve": "related_work_retrieve",
        "paper_compare_retrieve": "paper_compare_retrieve",
        "citation_retrieve": "citation_retrieve",
    })

    # ── 终止边：所有管线汇聚到 monitor → finish → END ──
    graph.add_edge("monitor", "finish")
    graph.add_edge("finish", END)

    return graph.compile(checkpointer=get_checkpointer())


# ── 单例模式：全局只构建一次图实例，避免重复构建开销 ──
_WORKFLOW_GRAPH = None


def get_workflow_graph():
    """获取全局单例的编译后工作流图。

    使用懒加载模式：首次调用时构建图，后续调用直接返回缓存实例。
    因为 build_workflow_graph() 涉及 LangGraph 图编译和 checkpointer 初始化，
    重复构建会有不必要的开销。

    返回值：CompiledGraph 实例（可调用 .stream() / .get_state() 等方法）
    """
    global _WORKFLOW_GRAPH
    if _WORKFLOW_GRAPH is None:
        _WORKFLOW_GRAPH = build_workflow_graph()
    return _WORKFLOW_GRAPH
