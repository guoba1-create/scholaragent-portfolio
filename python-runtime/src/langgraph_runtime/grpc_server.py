"""
grpc_server.py — Python 侧 gRPC 服务端

本模块实现 AgentWorkflow gRPC 服务，接收 Java 侧的请求并返回执行事件流。

gRPC 服务定义（参见 proto/agent_workflow.proto）：
    service AgentWorkflow {
        rpc RunWorkflow(RunWorkflowRequest) returns (stream WorkflowEventMsg);  // Server-Streaming
        rpc Control(ControlRequest) returns (ControlResponse);                 // Unary
    }

核心职责：
1. RunWorkflow RPC：根据 engine 字段分发到 workflow 或 react 执行引擎，流式返回事件
2. Control RPC：处理中断（INTERRUPT）和恢复（RESUME）命令
3. 中断注册表：维护待处理的中断请求（内存 + 持久化双层保障）

通信流程：
    Java → gRPC RunWorkflow → Python grpc_server → workflow_runtime.execute_workflow_stream()
    → 逐节点 yield WorkflowEventMsg → Java AgentGrpcClient.onNext() → WorkflowEventBus → SSE → 前端
"""

from __future__ import annotations

import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Generator

import grpc
from google.protobuf.struct_pb2 import Struct
from google.protobuf.timestamp_pb2 import Timestamp

from . import config
from .proto import agent_workflow_pb2
from .proto import agent_workflow_pb2_grpc
from .runtime_state_store import get_state_store

# ---------------------------------------------------------------------------
# 中断注册表（Interrupt Registry）
# ---------------------------------------------------------------------------
# 当 Java 侧发送 INTERRUPT 控制命令时，中断请求先存入此注册表。
# workflow_runtime 在每个节点执行后会调用 interrupt_requested() 检查是否有待处理的中断。
# 注册表采用双层存储：内存 dict（快速）+ RuntimeStateStore（持久化，防止进程重启丢失）。

_INTERRUPT_REGISTRY: dict[str, dict[str, Any]] = {}  # 内存层：{workflow_id: interrupt_payload}
_REGISTRY_LOCK = threading.RLock()  # 读写锁（支持同一线程重入）


def interrupt_requested(workflow_id: str) -> bool:
    """检查指定工作流是否有待处理的中断请求。

    检查顺序：
    1. 内存注册表（_INTERRUPT_REGISTRY）— 快速路径
    2. 持久化存储（RuntimeStateStore）— 慢速路径（兜底，防止进程重启丢失）

    调用时机：workflow_runtime 在每个图节点执行后调用此函数。
    """
    with _REGISTRY_LOCK:
        if workflow_id in _INTERRUPT_REGISTRY:
            return True
    record = get_state_store().load(workflow_id) or {}
    pending = record.get("pendingControl") or {}
    return str(pending.get("command") or "").upper() == "INTERRUPT"


def consume_interrupt(workflow_id: str) -> dict[str, Any] | None:
    """消费（取出并删除）指定工作流的中断请求。

    调用时机：workflow_runtime 检测到 interrupt_requested() 返回 True 后调用。
    消费后，中断请求从内存注册表和持久化存储中同时删除。

    返回值：中断负载（含 message/reason），如果没有待处理的中断则返回 None。
    """
    with _REGISTRY_LOCK:
        payload = _INTERRUPT_REGISTRY.pop(workflow_id, None)
    if payload is not None:
        _clear_persisted_interrupt(workflow_id)
        return payload

    record = get_state_store().load(workflow_id) or {}
    pending = record.get("pendingControl") or {}
    if str(pending.get("command") or "").upper() != "INTERRUPT":
        return None
    _clear_persisted_interrupt(workflow_id, record)
    return pending.get("payload") or {}


def _persist_interrupt(workflow_id: str, payload: dict[str, Any]):
    """将中断请求持久化到 RuntimeStateStore（防止进程重启丢失）。"""
    store = get_state_store()
    record = store.load(workflow_id) or {"workflowId": workflow_id}
    record["pendingControl"] = {"command": "INTERRUPT", "payload": payload or {}}
    store.save(workflow_id, record)


def _clear_persisted_interrupt(workflow_id: str, record: dict[str, Any] | None = None):
    """从持久化存储中清除已消费的中断请求。"""
    store = get_state_store()
    current = record or store.load(workflow_id) or {}
    if "pendingControl" not in current:
        return
    updated = dict(current)
    updated.pop("pendingControl", None)
    store.save(workflow_id, updated)


# ---------------------------------------------------------------------------
# Protobuf 转换工具函数
# ---------------------------------------------------------------------------
# gRPC 通信使用 Protobuf 格式（比 JSON 小 3-10 倍，快 20-100 倍）。
# 以下函数负责在 Python dict 和 Protobuf Struct 之间相互转换。

def _dict_to_struct(d: dict | None) -> Struct:
    """将 Python dict 转换为 Protobuf Struct（用于嵌套在 gRPC 消息中）。"""
    s = Struct()
    if d:
        s.update(d)
    return s


def _struct_to_dict(s: Struct | None) -> dict[str, Any]:
    """将 Protobuf Struct 转换为 Python dict（用于读取 gRPC 请求中的嵌套字段）。"""
    if not s:
        return {}
    return dict(s)


def _make_event_msg(
    workflow_id: str,
    event_type: str,
    phase: str,
    status: str,
    summary: str,
    *,
    agent_name: str | None = None,
    tool_name: str | None = None,
    payload: dict | None = None,
    sequence: int = 0,
) -> agent_workflow_pb2.WorkflowEventMsg:
    """构建一个中间状态的 WorkflowEventMsg（非终端事件）。

    事件类型包括：
    - WORKFLOW_STARTED: 工作流启动
    - ROUTER_DONE: 路由决策完成
    - AGENT_STARTED / AGENT_DONE: Agent 开始/完成
    - TOOL_STARTED / TOOL_DONE: 工具调用开始/完成
    - PLAN_DONE: PER 计划生成完成
    - EVALUATION_DONE: PER 评估完成
    - REPLAN_DONE: PER 重规划完成
    - MONITOR_WARNING: 质量监控警告

    参数说明：
    - workflow_id: 工作流 ID
    - event_type: 事件类型（如 "PLAN_DONE", "TOOL_STARTED"）
    - phase: 阶段标识（如 "plan", "agent", "tool", "monitor"）
    - status: 状态（"RUNNING" / "SUCCESS" / "WARNING" / "FAILED"）
    - summary: 事件摘要文本（前端展示）
    - agent_name: Agent 名称（可选）
    - tool_name: 工具名称（可选）
    - payload: 附加数据（可选，如 plan_steps, evaluation 等）
    - sequence: 事件序列号（单调递增，前端用于排序）

    返回值：WorkflowEventMsg protobuf 消息
    """
    msg = agent_workflow_pb2.WorkflowEventMsg(
        workflow_id=workflow_id,
        event_id=f"evt_{uuid.uuid4().hex[:8]}",
        sequence=sequence,
        event_type=event_type,
        phase=phase,
        status=status,
        summary=summary or "",
    )
    if agent_name:
        msg.agent_name = agent_name
    if tool_name:
        msg.tool_name = tool_name
    if payload:
        msg.payload.CopyFrom(_dict_to_struct(payload))
    ts = Timestamp()
    ts.GetCurrentTime()
    msg.created_at.CopyFrom(ts)
    return msg


def _make_terminal_event(
    workflow_id: str,
    status: str,
    final_answer: str,
    *,
    error_message: str | None = None,
    react_steps: list[dict] | None = None,
    traces: list[dict] | None = None,
    messages: list[dict] | None = None,
    tool_observations: list[dict] | None = None,
    document_refs: list[dict] | None = None,
    retrieval_diagnostics: list[dict] | None = None,
    claim_evidence_table: dict | None = None,
    quality_report: dict | None = None,
    warnings: list[str] | None = None,
    stats: dict | None = None,
    metadata: dict | None = None,
    sequence: int = 0,
) -> agent_workflow_pb2.WorkflowEventMsg:
    """构建终端事件（工作流结束时发送的最后一个事件）。

    终端事件携带完整的执行结果数据，Java 侧收到后会：
    1. 更新 WorkflowRunSnapshot 的最终状态
    2. 触发 MemoryExtractionService（保存语义记忆）
    3. 通过 SSE 将事件推送到前端（前端收到后停止 loading 动画）

    事件类型映射：
    - status="FAILED" → event_type="WORKFLOW_FAILED"
    - status="WAITING_USER" → event_type="AWAITING_CONFIRMATION"（深度研究暂停）
    - 其他 → event_type="WORKFLOW_DONE"

    参数说明：
    - status: 最终状态（COMPLETED / FAILED / INTERRUPTED / WAITING_USER）
    - final_answer: 最终回答（Markdown 格式，前端 Answer tab 展示）
    - traces: 执行追踪列表（前端 Trace tab 展示）
    - tool_observations: 工具调用记录（前端 Diagnostics tab 展示）
    - claim_evidence_table: 声明-证据对照表（前端 Evidence tab 展示）
    - quality_report: 质量监控报告
    - warnings: 警告信息列表
    - stats: 统计信息（steps, toolCalls, latencyMs）
    - metadata: 结果元数据（runtime, version, taskType 等）
    """
    event_type = "WORKFLOW_FAILED" if status == "FAILED" else ("AWAITING_CONFIRMATION" if status == "WAITING_USER" else "WORKFLOW_DONE")
    summary_text = (final_answer or error_message or "Workflow completed")[:220]
    msg = agent_workflow_pb2.WorkflowEventMsg(
        workflow_id=workflow_id,
        event_id=f"evt_{uuid.uuid4().hex[:8]}",
        sequence=sequence,
        event_type=event_type,
        phase="workflow",
        status=status,
        summary=summary_text,
        final_answer=final_answer or "",
        error_message=error_message or "",
    )
    for step in react_steps or []:
        msg.react_steps.append(_dict_to_struct(step))
    for trace in traces or []:
        msg.traces.append(_dict_to_struct(trace))
    for m in messages or []:
        msg.messages.append(_dict_to_struct(m))
    for obs in tool_observations or []:
        msg.tool_observations.append(_dict_to_struct(obs))
    for ref in document_refs or []:
        msg.document_refs.append(_dict_to_struct(ref))
    for diag in retrieval_diagnostics or []:
        msg.retrieval_diagnostics.append(_dict_to_struct(diag))
    if warnings:
        msg.warnings.extend(warnings)
    if claim_evidence_table:
        msg.claim_evidence_table.CopyFrom(_dict_to_struct(claim_evidence_table))
    if quality_report:
        msg.quality_report.CopyFrom(_dict_to_struct(quality_report))
    if stats:
        msg.stats.CopyFrom(_dict_to_struct(stats))
    if metadata:
        msg.result_metadata.CopyFrom(_dict_to_struct(metadata))
    ts = Timestamp()
    ts.GetCurrentTime()
    msg.created_at.CopyFrom(ts)
    return msg


# ---------------------------------------------------------------------------
# gRPC Servicer
# ---------------------------------------------------------------------------


class AgentWorkflowServicer(agent_workflow_pb2_grpc.AgentWorkflowServicer):
    """gRPC 服务实现类，处理 Java 侧的 RunWorkflow 和 Control 请求。

    RunWorkflow（Server-Streaming）：
    - Java 侧发送一个 RunWorkflowRequest，Python 侧流式返回多个 WorkflowEventMsg
    - 根据 request.engine 字段分发到 workflow 或 react 执行引擎
    - 支持首次执行和恢复执行（resume）两种模式

    Control（Unary）：
    - Java 侧发送控制命令（INTERRUPT / RESUME）
    - INTERRUPT：注册中断请求（workflow_runtime 在下一个节点检查时会消费）
    - RESUME：仅确认（实际恢复由 Java 侧重新调用 RunWorkflow(resume=true) 实现）
    """

    def RunWorkflow(self, request, context):
        """Server-Streaming RPC：执行工作流，逐节点返回事件。

        执行流程：
        1. 从 request 中提取 engine、taskType、上下文预算等参数
        2. 根据 engine 分发到 _run_workflow（workflow）或 _run_react（react）
        3. 根据 request.resume 决定首次执行或恢复执行
        """
        from .profiles import CATALOG_VERSION

        metadata = _struct_to_dict(request.metadata) if request.metadata else {}
        requested_catalog = str(metadata.get("catalogVersion") or "").strip()
        if requested_catalog and requested_catalog != CATALOG_VERSION:
            yield _make_terminal_event(
                request.workflow_id,
                "FAILED",
                (
                    "Execution catalog version mismatch: "
                    f"java={requested_catalog}, python={CATALOG_VERSION}"
                ),
                sequence=1,
            )
            return

        engine = (request.engine or "").strip().lower()
        if engine == "workflow":
            yield from self._run_workflow(request, context)
        else:
            yield from self._run_react(request, context)

    def Control(self, request, context):
        """Unary RPC：处理控制命令（INTERRUPT / RESUME）。"""
        command = (request.command or "").strip().upper()
        workflow_id = request.workflow_id

        if command == "INTERRUPT":
            payload = _struct_to_dict(request.payload) if request.payload else {}
            with _REGISTRY_LOCK:
                _INTERRUPT_REGISTRY[workflow_id] = payload
            _persist_interrupt(workflow_id, payload)
            print(f"[grpc_server] Interrupt registered | workflowId={workflow_id}")
            return agent_workflow_pb2.ControlResponse(
                success=True, workflow_id=workflow_id, command=command
            )

        if command == "RESUME":
            # Resume is handled by Java calling RunWorkflow again.
            # Python just acknowledges.
            print(f"[grpc_server] Resume acknowledged | workflowId={workflow_id}")
            return agent_workflow_pb2.ControlResponse(
                success=True, workflow_id=workflow_id, command=command
            )

        return agent_workflow_pb2.ControlResponse(
            success=False,
            workflow_id=workflow_id,
            command=command,
            error=f"Unsupported command: {request.command}",
        )

    # ------------------------------------------------------------------
    # Workflow engine dispatch
    # ------------------------------------------------------------------

    def _run_workflow(self, request, context) -> Generator:
        """Workflow 引擎执行入口。

        从 gRPC 请求中提取参数，构建初始状态，然后调用 workflow_runtime.execute_workflow_stream()。

        参数提取：
        - engine: "workflow"（已由调用方判断）
        - taskType: 任务类型（如 "claim_verification"），默认 "paper_qa"
        - WorkflowTemplate: 由 taskType 选择固定业务图；旧 executionMode 字段被忽略
        - context_budget: 上下文预算配置
        - resume: 是否恢复执行
        - resume_mode: 恢复模式（"checkpoint" / "restart"）

        执行模式：
        - 首次执行（resume=False）：创建新 state，从头开始执行
        - 恢复执行（resume=True）：委托给 _resume_workflow_stream
        """
        from .workflow_runtime import execute_workflow_stream
        from .workflow_graph import new_state

        route_decision = _struct_to_dict(request.route_decision) if request.route_decision else {}
        task_type = route_decision.get("taskType") or "paper_qa"
        context_budget = _struct_to_dict(request.context_budget) if request.context_budget else {}

        resume = request.resume
        resume_mode = request.resume_mode or "checkpoint"

        if resume:
            yield from self._resume_workflow_stream(
                request.workflow_id, task_type, route_decision, context_budget, resume_mode
            )
        else:
            # ── 首次执行：创建全新状态 ──
            state = new_state(
                workflow_id=request.workflow_id,
                user_id=request.user_id or "user-001",
                research_group_id=request.research_group_id or "replace-me",
                conversation_id=request.conversation_id,
                query=request.message or "",
                task_type=task_type,
                route_decision=route_decision,
                context_budget=context_budget,
            )
            yield from execute_workflow_stream(
                workflow_id=request.workflow_id,
                task_type=task_type,
                initial_state=state,
                config_obj={"configurable": {"thread_id": request.workflow_id}},
                route_decision=route_decision,
                context_budget=context_budget,
                resume_requested=False,
                resume_mode="fresh",
            )

    def _resume_workflow_stream(
        self, workflow_id, task_type, route_decision, context_budget, resume_mode
    ) -> Generator:
        """恢复执行被中断/暂停的工作流。

        恢复逻辑：
        1. 从 RuntimeStateStore 加载之前持久化的状态记录
        2. 根据记录状态选择恢复模式：
           - INTERRUPTED（被中断）：使用 checkpointer 从断点恢复（stream_input=None）
           - 其他状态：重建 state，从头执行
        3. 从记录中恢复 checkpointConfig（LangGraph checkpointer 的 thread_id 等配置）

        无记录时返回 FAILED 终端事件。
        """
        from .workflow_runtime import execute_workflow_stream
        from .workflow_graph import new_state

        store = get_state_store()
        record = store.load(workflow_id)
        if not record:
            yield _make_terminal_event(
                workflow_id, "FAILED",
                "No saved workflow runtime state was found for resume.",
                sequence=1,
            )
            return

        record_status = str(record.get("status") or "").upper()
        metadata = record.get("metadata") or {}
        # 恢复 checkpointer 配置（含 thread_id，LangGraph 据此找到对应的 checkpoint）
        checkpoint_config = {"configurable": dict(metadata.get("checkpointConfig") or {})}
        checkpoint_config["configurable"].setdefault("thread_id", workflow_id)
        if record_status == "INTERRUPTED":
            # ── 被中断的工作流：从 checkpointer 断点恢复 ──
            # stream_input=None 告诉 LangGraph 不使用新 state，而是从上次 checkpoint 继续
            yield from execute_workflow_stream(
                workflow_id=workflow_id,
                task_type=task_type,
                initial_state=None,
                config_obj=checkpoint_config,
                route_decision=route_decision,
                context_budget=context_budget,
                resume_requested=True,
                resume_mode="checkpoint",
            )
            return

        # ── 其他状态：重建 state 重新执行 ──
        query = str(record.get("query") or "")
        state = new_state(
            workflow_id=workflow_id,
            user_id=record.get("userId") or "user-001",
            research_group_id=record.get("researchGroupId") or "replace-me",
            conversation_id=record.get("conversationId"),
            query=query,
            task_type=task_type,
            route_decision=route_decision,
            context_budget=context_budget,
        )
        yield from execute_workflow_stream(
            workflow_id=workflow_id,
            task_type=task_type,
            initial_state=state,
            config_obj=checkpoint_config,
            route_decision=route_decision,
            context_budget=context_budget,
            resume_requested=True,
            resume_mode="restart",
        )

    # ------------------------------------------------------------------
    # React engine dispatch
    # ------------------------------------------------------------------

    def _run_react(self, request, context) -> Generator:
        from .react_runtime import execute_react_stream
        from .graph import _new_state

        route_decision = _struct_to_dict(request.route_decision) if request.route_decision else {}
        context_budget = _struct_to_dict(request.context_budget) if request.context_budget else {}
        profile_id = request.profile or "deep_research_react"

        resume = request.resume
        resume_mode = request.resume_mode or "checkpoint"

        if resume:
            yield from self._resume_react_stream(
                request.workflow_id, profile_id, route_decision, context_budget, resume_mode
            )
        else:
            state = _new_state(
                request.workflow_id,
                profile_id,
                request.user_id or "user-001",
                request.research_group_id or "replace-me",
                request.message or "",
                conversation_id=request.conversation_id,
                route_decision=route_decision,
                context_budget=context_budget,
            )
            yield from execute_react_stream(
                workflow_id=request.workflow_id,
                profile_id=profile_id,
                initial_state=state,
                config_obj={"configurable": {"thread_id": request.workflow_id}},
                route_decision=route_decision,
                context_budget=context_budget,
                resume_requested=False,
                resume_mode="fresh",
            )

    def _resume_react_stream(
        self, workflow_id, profile_id, route_decision, context_budget, resume_mode
    ) -> Generator:
        from .react_runtime import execute_react_stream
        from .graph import _new_state

        store = get_state_store()
        record = store.load(workflow_id)
        if not record:
            yield _make_terminal_event(
                workflow_id, "FAILED",
                "No saved React runtime state was found for resume.",
                sequence=1,
            )
            return

        record_status = str(record.get("status") or "").upper()
        metadata = record.get("metadata") or {}
        checkpoint_config = {"configurable": dict(metadata.get("checkpointConfig") or {})}
        checkpoint_config["configurable"].setdefault("thread_id", workflow_id)
        state = dict(record.get("state") or {})

        if record_status == "INTERRUPTED":
            yield from execute_react_stream(
                workflow_id=workflow_id,
                profile_id=record.get("profile") or profile_id,
                initial_state=None,
                config_obj=checkpoint_config,
                route_decision=route_decision,
                context_budget=context_budget,
                resume_requested=True,
                resume_mode="checkpoint",
            )
            return

        if not state:
            state = _new_state(
                workflow_id,
                record.get("profile") or profile_id,
                record.get("userId") or "user-001",
                record.get("researchGroupId") or "replace-me",
                record.get("query") or "",
                conversation_id=record.get("conversationId"),
                route_decision=route_decision,
                context_budget=context_budget,
            )

        state["status"] = "RUNNING"
        state["should_continue"] = True
        state["final_answer"] = ""
        state["last_action"] = {}
        state["warnings"] = list(state.get("warnings", []))

        yield from execute_react_stream(
            workflow_id=workflow_id,
            profile_id=record.get("profile") or profile_id,
            initial_state=state,
            config_obj=checkpoint_config,
            route_decision=route_decision,
            context_budget=context_budget,
            resume_requested=True,
            resume_mode="restart",
        )


# ---------------------------------------------------------------------------
# Server startup
# ---------------------------------------------------------------------------


def serve(port: int = 50051):
    """启动 gRPC 服务器。

    使用 ThreadPoolExecutor（8 个 worker 线程）处理并发请求。
    监听所有网络接口的指定端口（默认 50051）。

    启动后，Java 侧通过 gRPC 连接到此端口发送 RunWorkflow 和 Control 请求。

    参数：
    - port: 监听端口（默认 50051，可通过 GRPC_PORT 环境变量覆盖）
    """
    server = grpc.server(ThreadPoolExecutor(max_workers=8))
    agent_workflow_pb2_grpc.add_AgentWorkflowServicer_to_server(
        AgentWorkflowServicer(), server
    )
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    print(f"[grpc_server] AgentWorkflow gRPC server listening on port {port}")
    server.wait_for_termination()
