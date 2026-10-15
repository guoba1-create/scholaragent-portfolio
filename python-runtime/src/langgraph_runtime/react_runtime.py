"""
react_runtime.py — ReAct 引擎执行器

本模块负责驱动 ReAct 图的执行，并将每个节点的输出转换为 gRPC 事件流，
实时发送给 Java 侧（再通过 SSE 推送到前端）。

与 workflow_runtime.py 的区别：
- react_runtime: 驱动 ReAct 图（LLM 驱动的推理-行动循环）
- workflow_runtime: 驱动 Workflow 图（预定义的管线流程）

核心职责：
1. 调用 graph.stream() 执行 ReAct 图，逐节点消费输出
2. 将每个节点的输出转换为 WorkflowEventMsg（通过 _yield_node_events）
3. 处理中断请求（Java 侧发送 INTERRUPT 命令时终止执行）
4. 生成终端事件（WORKFLOW_DONE / WORKFLOW_FAILED）
5. 持久化最终状态到 RuntimeStateStore（用于恢复执行）

事件流生命周期：
    WORKFLOW_STARTED → [reason → tool → monitor → reason → ...] → WORKFLOW_DONE
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
from .graph import _new_state, get_graph
from .llm_client import begin_llm_budget, end_llm_budget
from .runtime_state_store import get_state_store
from .runtime_support import build_tool_observation, build_trace, preview


def execute_react_stream(
    *,
    workflow_id: str,
    profile_id: str,
    initial_state: dict | None,
    config_obj: dict,
    route_decision: dict,
    context_budget: dict,
    resume_requested: bool,
    resume_mode: str,
) -> Generator:
    """驱动 ReAct 图执行，逐节点生成 gRPC 事件流。

    这是 ReAct 引擎的核心执行函数。Java 侧通过 gRPC Server-Streaming 调用此函数，
    每 yield 一个事件，Java 侧就收到一个 WorkflowEventMsg 并通过 SSE 推送到前端。

    参数说明：
    - workflow_id: 工作流唯一 ID
    - profile_id: Agent profile id, such as "deep_research_react"
    - initial_state: 初始状态（由 _new_state() 创建），checkpoint 恢复模式下为 None
    - config_obj: LangGraph 配置（含 thread_id，用于 checkpointer 持久化）
    - route_decision: Java 侧的路由决策
    - context_budget: 上下文预算配置
    - resume_requested: 是否是恢复执行
    - resume_mode: 恢复模式（"checkpoint" 从断点恢复 / "restart" 重新执行）

    yield 值：WorkflowEventMsg protobuf 消息
    """
    print(f"[react_runtime] === Processing workflow: {workflow_id} ===")
    state_preview = initial_state or {}
    print(f"[react_runtime] profile={profile_id}, query={preview(state_preview.get('query', ''), 100)}")

    start_time = time.time()
    llm_context_audits: list[dict] = []
    llm_budget_token = begin_llm_budget(
        workflow_id, context_budget, llm_context_audits, default_max_tokens=24576
    )
    graph = get_graph()
    seq = 0              # 事件序列号
    final_answer = ""    # 最终回答
    status = "COMPLETED" # 最终状态
    warnings: list[str] = []
    observations: list[dict] = []
    react_steps: list[dict] = []
    messages: list[dict] = []

    # ── 发送工作流启动事件 ──
    seq += 1
    yield _make_event_msg(
        workflow_id,
        "WORKFLOW_STARTED",
        "workflow",
        "RUNNING",
        "Resuming Python LangGraph runtime from durable state"
        if resume_requested
        else f"Python LangGraph runtime started | profile={profile_id}",
        sequence=seq,
    )

    try:
        # ── 决定图的输入 ──
        # checkpoint 恢复模式：stream_input=None（LangGraph 从 checkpointer 读取上次状态）
        # 其他模式：stream_input=initial_state（从头开始执行）
        stream_input = None if (resume_requested and resume_mode == "checkpoint") else initial_state

        # ── 驱动图执行：逐节点消费输出 ──
        for event in graph.stream(stream_input, config=config_obj, stream_mode="updates"):
            for node_name, node_output in event.items():
                # 将节点输出转换为 gRPC 事件并 yield
                for evt in _yield_node_events(workflow_id, seq, profile_id, node_name, node_output):
                    seq = evt.sequence
                    yield evt

                # 根据节点名称更新本地状态
                if node_name == "tool":
                    observations = node_output.get("observations", observations)
                elif node_name in {"deep_execute", "deep_diagnose_library"}:
                    observations = node_output.get("observations", observations)
                    warnings = node_output.get("warnings", warnings)
                elif node_name == "handoff":
                    observations = node_output.get("observations", observations)
                    messages = node_output.get("messages", messages)
                elif node_name == "monitor":
                    warnings = node_output.get("warnings", warnings)
                elif node_name == "deep_synthesize":
                    final_answer = node_output.get("final_answer", final_answer)
                    status = node_output.get("status", status)
                elif node_name == "finish":
                    final_answer = node_output.get("final_answer", final_answer)
                    status = node_output.get("status", status)

                # ── 检查中断请求 ──
                if interrupt_requested(workflow_id):
                    interrupted_payload = consume_interrupt(workflow_id) or {}
                    status = "INTERRUPTED"
                    final_answer = str(
                        interrupted_payload.get("message")
                        or interrupted_payload.get("reason")
                        or "Workflow interrupted by control command."
                    )
                    warnings = list(warnings)
                    warnings.append(final_answer)
                    break
            if status == "INTERRUPTED":
                break

        # ── 图执行完毕，获取最终状态 ──
        graph_state = graph.get_state(config_obj)
        final_state_values = graph_state.values if graph_state and graph_state.values else dict(initial_state or {})
        checkpoint_config = graph_state.config if graph_state and getattr(graph_state, "config", None) else config_obj

        react_steps = final_state_values.get("react_steps", react_steps)
        messages = final_state_values.get("messages", messages)
        warnings = list(final_state_values.get("warnings", warnings))
        observations = final_state_values.get("observations", observations)

        if status != "INTERRUPTED":
            if not final_answer:
                final_answer = final_state_values.get("final_answer", "")
            if final_state_values.get("status") == "ASK_USER":
                status = "ASK_USER"
            elif final_state_values.get("status") == "FAILED":
                status = "FAILED"
        else:
            final_state_values["status"] = "INTERRUPTED"
            final_state_values["final_answer"] = final_answer
            final_state_values["warnings"] = warnings

    except Exception as exc:
        print(f"[react_runtime] Error: {exc}")
        status = "FAILED"
        final_answer = f"Runtime error: {exc}"
        warnings = list(warnings)
        warnings.append(str(exc))
        final_state_values = dict(initial_state or {})
        final_state_values["status"] = "FAILED"
        final_state_values["final_answer"] = final_answer
        final_state_values["warnings"] = warnings
        checkpoint_config = config_obj

    total_latency = int((time.time() - start_time) * 1000)

    # ── 构建工具观察记录和追踪记录 ──
    tool_observations = [
        build_tool_observation(
            workflow_id=workflow_id,
            step_id=obs.get("observation_id", ""),
            tool_name=obs.get("tool_name", ""),
            tool_result={
                "success": obs.get("success", False),
                "data": obs.get("data_preview", ""),
                "errorMessage": obs.get("error", ""),
                "latencyMs": obs.get("latency_ms", 0),
            },
            summary=obs.get("summary", ""),
        )
        for obs in observations
    ]
    traces = []
    for index, step in enumerate(react_steps, start=1):
        traces.append(
            build_trace(
                workflow_id=workflow_id,
                step_index=index,
                step_type="TOOL"
                if step.get("phase") == "OBSERVE"
                else ("MONITOR" if step.get("phase") == "MONITOR" else "AGENT"),
                agent_name=step.get("agentProfile"),
                tool_name=(step.get("action") or {}).get("actionName"),
                display_name=step.get("agentProfile") or (step.get("action") or {}).get("actionName"),
                phase=str(step.get("phase", "")).lower(),
                input_data=(step.get("action") or {}).get("arguments") or step.get("reasoningSummary"),
                output_data=step.get("observation") or step.get("monitorResult") or step.get("reasoningSummary"),
                status=step.get("status", "SUCCESS"),
                latency_ms=int(step.get("latencyMs") or 0),
                summary=step.get("reasoningSummary")
                or preview(step.get("observation") or step.get("monitorResult")),
            )
        )

    # ── 构建结果元数据 ──
    result_metadata = {
        "runtime": "langgraph",
        "version": "phase3-react",
        "profile": profile_id,
        "resumeRequested": resume_requested,
        "resumeMode": resume_mode,
        "checkpointConfig": checkpoint_config.get("configurable", {}),
        "llmContextAudits": llm_context_audits,
    }
    if profile_id == "deep_research_react":
        result_metadata["deepResearch"] = {
            "researchPlan": final_state_values.get("research_plan", {}),
            "researchEvaluation": final_state_values.get("research_evaluation", {}),
            "replanCount": final_state_values.get("deep_replan_count", 0),
            "maxReplan": final_state_values.get("max_deep_replan", 2),
        }

    # ── 发送终端事件 ──
    seq += 1
    yield _make_terminal_event(
        workflow_id,
        status,
        final_answer,
        react_steps=[
            {
                "stepId": step.get("stepId", ""),
                "workflowId": workflow_id,
                "agentProfile": step.get("agentProfile", profile_id),
                "stepIndex": step.get("stepIndex", 0),
                "phase": step.get("phase", ""),
                "reasoningSummary": step.get("reasoningSummary", ""),
                "action": step.get("action"),
                "observation": step.get("observation"),
                "monitorResult": step.get("monitorResult"),
                "status": step.get("status", "SUCCESS"),
                "latencyMs": step.get("latencyMs", 0),
            }
            for step in react_steps
        ],
        messages=messages,
        warnings=warnings,
        tool_observations=tool_observations,
        traces=traces,
        stats={
            "steps": len(react_steps),
            "toolCalls": sum(1 for step in react_steps if step.get("phase") == "OBSERVE"),
            "latencyMs": total_latency,
        },
        metadata=result_metadata,
        sequence=seq,
    )

    # ── 持久化最终状态 ──
    _persist_runtime_state(
        workflow_id=workflow_id,
        engine="react",
        profile_id=profile_id,
        state=final_state_values,
        route_decision=route_decision,
        context_budget=context_budget,
        status=status,
        metadata=result_metadata,
    )

    end_llm_budget(llm_budget_token)

    print(
        f"[react_runtime] === Workflow {workflow_id} completed: {status}, "
        f"{len(react_steps)} steps, {total_latency}ms ==="
    )


def _persist_runtime_state(
    *,
    workflow_id: str,
    engine: str,
    profile_id: str,
    state: dict,
    route_decision: dict,
    context_budget: dict,
    status: str,
    metadata: dict,
):
    """将 ReAct 引擎的最终状态持久化到 RuntimeStateStore。

    持久化的数据用于：
    1. resume 恢复：Java 侧发送 RESUME 请求时，从此处读取状态恢复执行
    2. 状态查询：Java 侧查询工作流状态时返回
    """
    store = get_state_store()
    store.save(
        workflow_id,
        {
            "engine": engine,
            "profile": profile_id,
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
    profile_id: str,
    node_name: str,
    node_output: dict,
) -> Generator:
    """将单个 ReAct 图节点的输出转换为一个或多个 gRPC 事件消息。

    事件映射规则：
    - reason → AGENT_STARTED + AGENT_DONE（推理开始/完成）
    - tool → TOOL_STARTED + TOOL_DONE（工具调用开始/完成）
    - handoff → HANDOFF_STARTED + HANDOFF_DONE（委托开始/完成）
    - monitor → MONITOR_WARNING（质量警告）
    """
    if node_name == "reason":
        action = node_output.get("last_action", {})
        action_type = action.get("actionType", "FINISH")
        reasoning = action.get("reasoningSummary", "")
        sequence += 1
        yield _make_event_msg(
            workflow_id, "AGENT_STARTED", "reason", "RUNNING",
            f"Reasoning: {reasoning[:200]}",
            agent_name=profile_id, sequence=sequence,
        )
        sequence += 1
        yield _make_event_msg(
            workflow_id, "AGENT_DONE", "reason", "SUCCESS",
            f"Decision: {action_type} -> {action.get('actionName', '')}",
            agent_name=profile_id, sequence=sequence,
        )
        return

    if node_name == "tool":
        observations = node_output.get("observations", [])
        if observations:
            last_obs = observations[-1]
            tool_name = last_obs.get("tool_name", "")
            obs_status = "SUCCESS" if last_obs.get("success", False) else "FAILED"
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_STARTED", "tool", "RUNNING",
                f"Calling {tool_name}",
                agent_name=profile_id, tool_name=tool_name, sequence=sequence,
            )
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_DONE", "tool", obs_status,
                last_obs.get("summary", ""),
                agent_name=profile_id,
                tool_name=tool_name,
                payload={"latencyMs": last_obs.get("latency_ms", 0), "outputPreview": last_obs.get("data_preview", "")[:300]},
                sequence=sequence,
            )
        return

    if node_name == "handoff":
        target = node_output.get("handoff_target", "")
        handoff_status = node_output.get("handoff_status", "COMPLETED")
        observations = node_output.get("observations", [])
        summary = observations[-1].get("summary", "Handoff completed") if observations else "Handoff completed"
        sequence += 1
        yield _make_event_msg(
            workflow_id, "HANDOFF_STARTED", "handoff", "RUNNING",
            f"Delegating task to {target}",
            agent_name=profile_id, tool_name=target,
            payload={"receiver": target}, sequence=sequence,
        )
        sequence += 1
        yield _make_event_msg(
            workflow_id, "HANDOFF_DONE", "handoff",
            "SUCCESS" if handoff_status not in {"FAILED"} else "FAILED",
            summary,
            agent_name=profile_id, tool_name=target,
            payload={"receiver": target, "messages": node_output.get("messages", [])[-2:]},
            sequence=sequence,
        )
        return

    if node_name == "deep_plan":
        plan = node_output.get("research_plan", {}) or {}
        steps = plan.get("plan_steps") or []
        sequence += 1
        yield _make_event_msg(
            workflow_id, "AGENT_STARTED", "deep_plan", "RUNNING",
            "DeepResearch planning started",
            agent_name=profile_id, sequence=sequence,
        )
        sequence += 1
        yield _make_event_msg(
            workflow_id, "AGENT_DONE", "deep_plan", "SUCCESS",
            f"DeepResearch plan generated with {len(steps)} step(s)",
            agent_name=profile_id,
            payload={"researchPlan": plan},
            sequence=sequence,
        )
        return

    if node_name == "deep_execute":
        observations = node_output.get("observations", [])
        # This node can execute several plan steps; emit compact tool events for the recent batch.
        executed_ids = {
            str(item.get("step_id"))
            for item in node_output.get("deep_executed", [])
            if isinstance(item, dict)
        }
        recent = [
            obs for obs in observations
            if not executed_ids or str(obs.get("plan_step_id")) in executed_ids
        ][-5:]
        for obs in recent:
            tool_name = obs.get("tool_name", "")
            obs_status = "SUCCESS" if obs.get("success", False) else "FAILED"
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_STARTED", "deep_execute", "RUNNING",
                f"DeepResearch executing {tool_name}",
                agent_name=profile_id,
                tool_name=tool_name,
                payload={"planStepId": obs.get("plan_step_id")},
                sequence=sequence,
            )
            sequence += 1
            yield _make_event_msg(
                workflow_id, "TOOL_DONE", "deep_execute", obs_status,
                obs.get("summary", ""),
                agent_name=profile_id,
                tool_name=tool_name,
                payload={
                    "planStepId": obs.get("plan_step_id"),
                    "latencyMs": obs.get("latency_ms", 0),
                    "outputPreview": obs.get("data_preview", "")[:300],
                },
                sequence=sequence,
            )
        return

    if node_name == "deep_evaluate":
        evaluation = node_output.get("research_evaluation", {}) or {}
        status = "WARNING" if evaluation.get("decision") in {"replan", "fail"} else "SUCCESS"
        sequence += 1
        yield _make_event_msg(
            workflow_id, "MONITOR_WARNING" if status == "WARNING" else "AGENT_DONE",
            "deep_evaluate", status,
            f"DeepResearch evaluation: {evaluation.get('decision')} "
            f"(coverage={evaluation.get('coverage_score')}, quality={evaluation.get('quality_score')})",
            agent_name=profile_id,
            payload={"researchEvaluation": evaluation},
            sequence=sequence,
        )
        return

    if node_name == "deep_replan":
        plan = node_output.get("research_plan", {}) or {}
        steps = plan.get("plan_steps") or []
        sequence += 1
        yield _make_event_msg(
            workflow_id, "AGENT_DONE", "deep_replan", "SUCCESS",
            f"DeepResearch replanned; total plan steps={len(steps)}",
            agent_name=profile_id,
            payload={"researchPlan": plan, "replanCount": node_output.get("deep_replan_count", 0)},
            sequence=sequence,
        )
        return

    if node_name == "deep_synthesize":
        sequence += 1
        yield _make_event_msg(
            workflow_id, "AGENT_DONE", "deep_synthesize", "SUCCESS",
            "DeepResearch final report synthesized",
            agent_name=profile_id,
            payload={"answerPreview": preview(node_output.get("final_answer", ""), 300)},
            sequence=sequence,
        )
        return

    if node_name == "monitor":
        for warning in node_output.get("warnings", []):
            sequence += 1
            yield _make_event_msg(
                workflow_id, "MONITOR_WARNING", "monitor", "WARNING",
                warning,
                agent_name=profile_id, sequence=sequence,
            )
        return
