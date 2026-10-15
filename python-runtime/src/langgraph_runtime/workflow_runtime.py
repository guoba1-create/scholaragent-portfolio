"""
workflow_runtime.py — Workflow 图执行引擎

本模块负责驱动 LangGraph 图的执行，并将每个节点的输出转换为 gRPC 事件流，
实时发送给 Java 侧（再通过 SSE 推送到前端）。

核心职责：
1. 调用 graph.stream() 执行图，逐节点消费输出
2. 将每个节点的输出转换为 WorkflowEventMsg（通过 _yield_node_events）
3. 处理中断请求（Java 侧发送 INTERRUPT 命令时，设置 status="INTERRUPTED" 并终止）
4. 处理控制面中断/恢复请求；DeepResearch 确认由专用 Agent 图负责
5. 生成终端事件（WORKFLOW_DONE / WORKFLOW_FAILED / AWAITING_CONFIRMATION）
6. 持久化最终状态到 RuntimeStateStore（用于恢复执行）

事件流生命周期：
    WORKFLOW_STARTED → [各节点事件] → WORKFLOW_DONE / WORKFLOW_FAILED / AWAITING_CONFIRMATION
"""

from __future__ import annotations

import time
from typing import Generator

from .grpc_server import (
    _make_event_msg,
    _make_terminal_event,
    consume_interrupt,
    interrupt_requested,
)
from .runtime_state_store import get_state_store
from .llm_client import begin_llm_budget, end_llm_budget
from .runtime_support import preview
from .workflow_graph import get_workflow_graph, new_state


def execute_workflow_stream(
    *,
    workflow_id: str,
    task_type: str,
    initial_state: dict | None,
    config_obj: dict,
    route_decision: dict,
    context_budget: dict,
    resume_requested: bool,
    resume_mode: str,
) -> Generator:
    """驱动 LangGraph 图执行，逐节点生成 gRPC 事件流。

    这是 Workflow 引擎的核心执行函数。Java 侧通过 gRPC Server-Streaming 调用此函数，
    每 yield 一个事件，Java 侧就收到一个 WorkflowEventMsg 并通过 SSE 推送到前端。

    参数说明：
    - workflow_id: 工作流唯一 ID
    - task_type: 任务类型（claim_verification / paper_qa / ...）
    - initial_state: 初始状态（由 new_state() 创建），resume + checkpoint 模式下为 None
    - config_obj: LangGraph 配置（含 thread_id，用于 checkpointer 持久化）
    - route_decision: Java 侧的路由决策（含 executionMode）
    - context_budget: 上下文预算配置
    - resume_requested: 是否是恢复执行（True 时从 checkpointer 恢复图状态）
    - resume_mode: 恢复模式（"checkpoint" 从断点恢复 / "restart" 重新执行）

    yield 值：WorkflowEventMsg protobuf 消息（逐个发送到 Java 侧）

    执行流程：
    1. 发送 WORKFLOW_STARTED 事件
    2. 调用 graph.stream() 逐节点执行
    3. 对每个节点输出，调用 _yield_node_events 转换为事件并 yield
    4. 检查中断请求（interrupt_requested）和可恢复状态
    5. 异常时设置 status="FAILED"
    6. 发送终端事件（WORKFLOW_DONE / WORKFLOW_FAILED / AWAITING_CONFIRMATION）
    7. 持久化最终状态到 RuntimeStateStore
    """
    from .proto import agent_workflow_pb2

    print(f"[workflow_runtime] === Processing workflow: {workflow_id} ===")
    state_preview = initial_state or {}
    print(f"[workflow_runtime] taskType={task_type}, query={preview(state_preview.get('query', ''), 120)}")

    start_time = time.time()
    llm_context_audits: list[dict] = []
    llm_budget_token = begin_llm_budget(
        workflow_id, context_budget, llm_context_audits, default_max_tokens=16384
    )
    seq = 0  # 事件序列号（单调递增，用于前端排序）
    sent_observation_count = 0  # 已发送的 tool_observation 数量（避免重复发送）
    sent_warning_count = 0      # 已发送的 warning 数量（避免重复发送）

    # ── 发送工作流启动事件 ──
    seq += 1
    yield _make_event_msg(
        workflow_id,
        "WORKFLOW_STARTED",
        "workflow",
        "RUNNING",
        "Resuming Python workflow runtime from durable state"
        if resume_requested
        else f"Python workflow runtime started | taskType={task_type}",
        sequence=seq,
    )

    graph = get_workflow_graph()
    final_answer = ""
    final_state_values: dict = {}
    status = "COMPLETED"

    try:
        # ── 决定图的输入 ──
        # checkpoint 恢复模式：stream_input=None（LangGraph 从 checkpointer 读取上次状态）
        # 其他模式：stream_input=initial_state（从头开始执行）
        stream_input = None if (resume_requested and resume_mode == "checkpoint") else initial_state

        # ── 驱动图执行：逐节点消费输出 ──
        # stream_mode="updates" 表示每个节点完成后立即返回该节点的输出
        for event in graph.stream(stream_input, config=config_obj, stream_mode="updates"):
            for node_name, node_output in event.items():
                # ── 将节点输出转换为 gRPC 事件并 yield ──
                for evt in _yield_node_events(
                    workflow_id, seq, task_type, node_name, node_output,
                    sent_observation_count, sent_warning_count,
                ):
                    seq = evt.sequence
                    yield evt

                # ── 更新已发送计数器（避免重复发送同一 observation/warning）──
                if "tool_observations" in node_output:
                    sent_observation_count = len(node_output.get("tool_observations", []))
                if "warnings" in node_output:
                    sent_warning_count = len(node_output.get("warnings", []))

                # ── 检测终止节点 ──
                if node_name == "finish":
                    final_answer = node_output.get("final_answer", "")
                    status = node_output.get("status", "COMPLETED")
                # ── 检查中断请求（Java 侧通过 gRPC Control RPC 发送 INTERRUPT 命令）──
                if interrupt_requested(workflow_id):
                    interrupt_payload = consume_interrupt(workflow_id) or {}
                    status = "INTERRUPTED"
                    final_answer = str(
                        interrupt_payload.get("message")
                        or interrupt_payload.get("reason")
                        or "Workflow interrupted by control command."
                    )
                    break
            if status == "INTERRUPTED":
                break

        # ── 图执行完毕，获取最终状态 ──
        graph_state = graph.get_state(config_obj)
        final_state_values = graph_state.values if graph_state and graph_state.values else dict(initial_state or {})
        checkpoint_config = graph_state.config if graph_state and getattr(graph_state, "config", None) else config_obj
        if status == "INTERRUPTED":
            final_state_values["status"] = "INTERRUPTED"
            final_state_values["final_answer"] = final_answer
        else:
            if not final_answer:
                final_answer = final_state_values.get("final_answer", "")
            status = final_state_values.get("status", status)
    except Exception as exc:
        print(f"[workflow_runtime] Error: {exc}")
        status = "FAILED"
        final_answer = f"Workflow runtime error: {exc}"
        final_state_values = dict(initial_state or {})
        final_state_values["warnings"] = [str(exc)]
        final_state_values["status"] = "FAILED"
        final_state_values["final_answer"] = final_answer
        checkpoint_config = config_obj

    total_latency = int((time.time() - start_time) * 1000)

    # ── 构建结果元数据（用于 Java 侧的 snapshot 和日志）──
    result_metadata = {
        "runtime": "langgraph-workflow",
        "version": "phase3-workflow",
        "taskType": task_type,
        "resumeRequested": resume_requested,
        "resumeMode": resume_mode,
        "checkpointConfig": (checkpoint_config if isinstance(checkpoint_config, dict) else config_obj).get("configurable", {}),
        "llmContextAudits": llm_context_audits,
    }

    # ── 发送终端事件（包含最终回答、traces、evidence 等完整数据）──
    seq += 1
    yield _make_terminal_event(
        workflow_id,
        status,
        final_answer,
        warnings=final_state_values.get("warnings", []),
        traces=final_state_values.get("traces", []),
        tool_observations=final_state_values.get("tool_observations", []),
        document_refs=final_state_values.get("document_refs", []),
        retrieval_diagnostics=(
            [final_state_values.get("retrieval_diagnostics")]
            if final_state_values.get("retrieval_diagnostics")
            else None
        ),
        claim_evidence_table=final_state_values.get("claim_evidence_table"),
        quality_report=final_state_values.get("quality_report"),
        stats={
            "steps": len(final_state_values.get("traces", [])),
            "toolCalls": len(final_state_values.get("tool_observations", [])),
            "latencyMs": total_latency,
        },
        metadata=result_metadata,
        sequence=seq,
    )

    # ── 持久化最终状态（用于 resume 恢复和状态查询）──
    _persist_runtime_state(
        workflow_id=workflow_id,
        task_type=task_type,
        state=final_state_values,
        route_decision=route_decision,
        context_budget=context_budget,
        status=status,
        metadata=result_metadata,
    )

    end_llm_budget(llm_budget_token)

    print(
        f"[workflow_runtime] === Workflow {workflow_id} completed: {status}, "
        f"{len(final_state_values.get('traces', []))} traces, {total_latency}ms ==="
    )


def _persist_runtime_state(
    *,
    workflow_id: str,
    task_type: str,
    state: dict,
    route_decision: dict,
    context_budget: dict,
    status: str,
    metadata: dict,
):
    """将工作流最终状态持久化到 RuntimeStateStore。

    持久化的数据用于两个场景：
    1. resume 恢复：Java 侧发送 RESUME 请求时，Python 侧从此处读取状态恢复执行
    2. 状态查询：Java 侧查询工作流状态时返回

    存储内容包含：引擎类型、任务类型、用户/研究组 ID、查询、路由决策、上下文预算、
    最终状态、元数据。
    """
    get_state_store().save(
        workflow_id,
        {
            "engine": "workflow",
            "taskType": task_type,
            "userId": state.get("user_id"),
            "researchGroupId": state.get("research_group_id"),
            "conversationId": state.get("conversation_id"),
            "query": state.get("query"),
            "routeDecision": route_decision,
            "contextBudget": context_budget,
            "status": status,
            "state": state,
            "metadata": metadata,
        },
    )


def _yield_node_events(
    workflow_id: str,
    sequence: int,
    task_type: str,
    node_name: str,
    node_output: dict,
    sent_observation_count: int,
    sent_warning_count: int,
) -> Generator:
    """将单个图节点的输出转换为一个或多个 gRPC 事件消息。

    这是事件映射的核心函数。每个图节点执行完毕后，LangGraph 返回 {node_name: node_output}，
    此函数根据 node_name 将 node_output 转换为前端可理解的事件。

    参数说明：
    - workflow_id: 工作流 ID（写入每个事件）
    - sequence: 当前事件序列号（每个事件 +1）
    - task_type: 任务类型（用于日志和路由信息）
    - node_name: 图节点名称（如 "plan", "claim_retrieve", "evaluate" 等）
    - node_output: 节点的输出字典（节点函数的返回值）
    - sent_observation_count: 已发送的 observation 数量（只发送增量部分）
    - sent_warning_count: 已发送的 warning 数量（只发送增量部分）

    yield 值：WorkflowEventMsg protobuf 消息

    事件映射规则：
    - route → ROUTER_DONE
    - query_prepare / claim_decompose / citation_extract → NODE_DONE
    - 各 retrieve 节点 → NODE_STARTED + (TOOL_STARTED + TOOL_DONE)* + NODE_DONE
    - claim_critic → NODE_STARTED + (judge_evidence_quality TOOL events) + NODE_DONE
    - 各 synthesize 节点 → NODE_DONE（含 final_answer 摘要）
    - monitor → (MONITOR_WARNING)* + NODE_DONE
    - evaluate → EVALUATION_DONE
    - replan → REPLAN_DONE
    - unsupported → NODE_DONE (WARNING)
    """
    if node_name == "route":
        sequence += 1
        yield _make_event_msg(
            workflow_id, "ROUTER_DONE", "router", "SUCCESS",
            f"Workflow routed to {task_type}",
            agent_name="route", sequence=sequence,
        )
        return

    if node_name == "query_prepare":
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "workflow_node", "SUCCESS",
            "Prepared the retrieval query and fixed workflow inputs.",
            agent_name="query_prepare",
            payload={
                "prepared_query": node_output.get("prepared_query"),
                "search_queries": node_output.get("search_queries", []),
            },
            sequence=sequence,
        )
        return

    if node_name in {"claim_decompose", "citation_extract"}:
        sequence += 1
        claims = node_output.get("claims", [])
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "workflow_node", "SUCCESS",
            f"Extracted {len(claims)} claim(s).",
            agent_name=node_name, sequence=sequence,
        )
        return

    if node_name in {"claim_retrieve", "paper_retrieve", "related_work_retrieve",
                     "paper_compare_retrieve", "citation_retrieve"}:
        actor_name = node_name
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_STARTED", "workflow_node", "RUNNING",
            "Retrieving evidence from the literature library.",
            agent_name=actor_name, sequence=sequence,
        )
        observations = node_output.get("tool_observations", [])
        for observation in observations[sent_observation_count:]:
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_STARTED", "tool", "RUNNING",
                f"Calling {observation.get('toolName')}",
                agent_name=actor_name, tool_name=observation.get("toolName"), sequence=sequence,
            )
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_DONE", "tool", observation.get("status", "SUCCESS"),
                observation.get("summary", ""),
                agent_name=actor_name,
                tool_name=observation.get("toolName"),
                payload={
                    "latencyMs": observation.get("latencyMs", 0),
                    "outputPreview": preview(observation.get("compressedText", ""), 300),
                    "observation": {
                        "observationId": observation.get("observationId"),
                        "toolName": observation.get("toolName"),
                        "compressedObservation": observation.get("compressedText"),
                        "citations": observation.get("citations", []),
                    },
                },
                sequence=sequence,
            )
        sequence += 1
        evidence_count = len(node_output.get("evidence_spans", []))
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "workflow_node", "SUCCESS",
            f"Evidence retrieval completed with {evidence_count} evidence span(s).",
            agent_name=actor_name, sequence=sequence,
        )
        return

    if node_name == "claim_critic":
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_STARTED", "workflow_node", "RUNNING",
            "Evaluating claim-evidence support status.",
            agent_name="claim_critic", sequence=sequence,
        )
        observations = node_output.get("tool_observations", [])
        for observation in observations[sent_observation_count:]:
            if observation.get("toolName") != "judge_evidence_quality":
                continue
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_STARTED", "tool", "RUNNING",
                "Judging evidence quality.",
                agent_name="claim_critic", tool_name="judge_evidence_quality", sequence=sequence,
            )
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_DONE", "tool", observation.get("status", "SUCCESS"),
                observation.get("summary", ""),
                agent_name="claim_critic",
                tool_name="judge_evidence_quality",
                payload={
                    "latencyMs": observation.get("latencyMs", 0),
                    "outputPreview": preview(observation.get("compressedText", ""), 300),
                },
                sequence=sequence,
            )
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "workflow_node", "SUCCESS",
            f"Claim critic produced {len(node_output.get('verification_results', []))} verification result(s).",
            agent_name="claim_critic", sequence=sequence,
        )
        return

    if node_name in {"claim_synthesize", "paper_synthesize", "related_work_synthesize",
                     "paper_compare_synthesize", "citation_synthesize"}:
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "answer", "SUCCESS",
            preview(node_output.get("final_answer", ""), 220),
            agent_name=node_name, sequence=sequence,
        )
        return

    if node_name == "monitor":
        warnings = node_output.get("quality_report", {}).get("warnings", [])
        for warning in warnings[sent_warning_count:]:
            sequence += 1
            yield _make_event_msg(
                workflow_id, "MONITOR_WARNING", "monitor", warning.get("severity", "WARNING"),
                warning.get("message", ""),
                agent_name="runtime_monitor",
                payload={"type": warning.get("type", "workflow_warning")},
                sequence=sequence,
            )
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "monitor", "SUCCESS",
            f"Workflow monitor completed with {len(warnings)} warning(s).",
            agent_name="runtime_monitor", sequence=sequence,
        )
        return

    if node_name == "unsupported":
        sequence += 1
        yield _make_event_msg(
            workflow_id, "NODE_DONE", "workflow_node", "WARNING",
            "Unsupported workflow type, returned a coming-next answer.",
            agent_name="unsupported", sequence=sequence,
        )
        return

    if node_name == "evaluate":
        evaluation = node_output.get("evaluation", {})
        decision = evaluation.get("decision", "pass")
        sequence += 1
        yield _make_event_msg(
            workflow_id, "EVALUATION_DONE", "evaluate",
            "SUCCESS" if decision == "pass" else "WARNING",
            f"Coverage: {evaluation.get('coverage_score', 0):.0%}, Decision: {decision}",
            agent_name="evaluate",
            payload={"evaluation": evaluation},
            sequence=sequence,
        )
        return

    if node_name == "replan":
        replan_count = node_output.get("replan_count", 0)
        sequence += 1
        yield _make_event_msg(
            workflow_id, "REPLAN_DONE", "replan", "SUCCESS",
            f"Replanned (attempt {replan_count}).",
            agent_name="replan",
            payload={"replan_count": replan_count, "plan_steps": node_output.get("plan_steps", [])},
            sequence=sequence,
        )
        return
