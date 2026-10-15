"""
models.py — 数据模型定义

本文件定义了系统中所有数据传输对象（DTO）的结构。
使用 Pydantic 库进行数据校验和序列化（自动校验字段类型、提供默认值）。

这些模型用于：
1. 接收 Java 侧发来的请求（WorkflowRunRequestV1）
2. 构造返回给 Java 侧的事件（WorkflowEventV1）
3. 封装最终执行结果（WorkflowResultV1）
4. 处理控制命令（WorkflowControlV1）
"""

from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime


class HybridRouteDecision(BaseModel):
    """路由决策模型 — Java 侧的智能路由模块根据用户输入决定由哪个引擎处理。

    字段说明：
    - intent: 用户意图（如 "paper_qa", "claim_verification"）
    - engine: 执行引擎（"workflow" 走 Workflow 图 / "react" 走 ReAct 图）
    - taskType: 任务类型（如 "claim_verification", "paper_qa", "related_work"）
    - profile: Agent profile id, for example "deep_research_react"
    - confidence: 路由置信度（0-1，越高表示路由越确定）
    - reason: 路由理由（用于日志和调试）
    - signals: 路由信号列表（如 ["keyword_match", "llm_classify"]）
    """
    intent: Optional[str] = None
    engine: Optional[str] = None
    taskType: Optional[str] = None
    profile: Optional[str] = None
    confidence: float = 0.0
    reason: Optional[str] = None
    signals: List[str] = Field(default_factory=list)


class WorkflowRunRequestV1(BaseModel):
    """工作流运行请求 — Java 侧通过 gRPC 发送此请求来启动一个工作流。

    这是 Python 侧接收到的第一个请求，包含执行工作流所需的全部信息。
    Java 侧的 WorkflowOrchestrator 负责构造此请求。

    字段说明：
    - schemaVersion: 协议版本（当前为 "v1"，用于前后兼容）
    - workflowId: 工作流唯一 ID（由 Java 侧生成，如 "wf_1716268800_a1b2c3d4"）
    - engine: 执行引擎（"workflow" / "react"）
    - profile: Agent 配置（仅 react 引擎使用）
    - userId: 用户 ID
    - researchGroupId: 研究组 ID（用于多租户文档隔离）
    - conversationId: 会话 ID（用于对话上下文关联）
    - message: 用户的原始输入消息
    - routeDecision: 路由决策（由 Java 侧的 HybridRouter 生成）
    - memoryRecallRef: 记忆召回引用（暂未使用）
    - contextBudget: 上下文预算配置（含 maxPromptChars, promptContext 等）
    - metadata: 附加元数据
    """
    schemaVersion: str = "v1"
    workflowId: str
    engine: str
    profile: str
    userId: str
    researchGroupId: str
    conversationId: Optional[str] = None
    message: str
    routeDecision: Optional[HybridRouteDecision] = None
    memoryRecallRef: Optional[Dict[str, Any]] = None
    contextBudget: Optional[Dict[str, Any]] = None
    metadata: Optional[Dict[str, Any]] = None


class WorkflowEventV1(BaseModel):
    """工作流事件 — Python 侧通过 gRPC 流式返回此事件给 Java 侧。

    每个事件代表工作流执行过程中的一个步骤（如工具调用、Agent 决策等）。
    Java 侧收到事件后会通过 SSE（Server-Sent Events）推送到前端。

    字段说明：
    - schemaVersion: 协议版本
    - workflowId: 工作流 ID
    - eventId: 事件唯一 ID（如 "evt_a1b2c3d4"）
    - sequence: 事件序列号（单调递增，前端用于排序）
    - eventType: 事件类型（如 "WORKFLOW_STARTED", "TOOL_DONE", "WORKFLOW_DONE"）
    - phase: 阶段标识（如 "workflow", "agent", "tool", "monitor"）
    - agentName: Agent 名称（如 "EvidenceRetrievalAgent"）
    - toolName: 工具名称（如 "retrieve_evidence"）
    - status: 状态（"RUNNING" / "SUCCESS" / "WARNING" / "FAILED"）
    - summary: 事件摘要文本（前端展示）
    - payload: 附加数据（如工具调用结果、计划步骤等）
    - createdAt: 事件创建时间
    """
    schemaVersion: str = "v1"
    workflowId: str
    eventId: Optional[str] = None
    sequence: int = 0
    eventType: str
    phase: str
    agentName: Optional[str] = None
    toolName: Optional[str] = None
    status: str
    summary: str
    payload: Optional[Dict[str, Any]] = None
    createdAt: Optional[str] = None


class WorkflowResultV1(BaseModel):
    """工作流最终结果 — 包含工作流执行完成后的全部数据。

    这是前端展示最终答案的数据来源。Java 侧收到 WORKFLOW_DONE 事件后，
    会将此结构保存到数据库，并通过 SSE 推送到前端。

    字段说明：
    - status: 最终状态（"COMPLETED" / "FAILED" / "INTERRUPTED"）
    - finalAnswer: 最终回答（Markdown 格式，前端 Answer tab 展示）
    - reactSteps: ReAct 步骤列表（仅 react 引擎产生）
    - traces: 执行追踪列表（前端 Trace tab 展示）
    - messages: Agent 间通信消息（handoff 场景）
    - toolObservations: 工具调用记录（前端 Diagnostics tab 展示）
    - documentRefs: 文档引用列表（前端 Sources 面板展示）
    - retrievalDiagnostics: 检索诊断信息（如命中数、建议等）
    - claimEvidenceTable: 声明-证据对照表（前端 Evidence tab 展示）
    - qualityReport: 质量监控报告
    - warnings: 警告信息列表
    - stats: 统计信息（steps, toolCalls, latencyMs）
    - metadata: 结果元数据（runtime, version 等）
    """
    schemaVersion: str = "v1"
    workflowId: str
    status: str
    finalAnswer: Optional[str] = None
    errorMessage: Optional[str] = None
    reactSteps: Optional[List[Dict[str, Any]]] = None
    traces: Optional[List[Dict[str, Any]]] = None
    messages: Optional[List[Dict[str, Any]]] = None
    toolObservations: Optional[List[Dict[str, Any]]] = None
    documentRefs: Optional[List[Dict[str, Any]]] = None
    retrievalDiagnostics: Optional[List[Dict[str, Any]]] = None
    claimEvidenceTable: Optional[Any] = None
    qualityReport: Optional[Any] = None
    warnings: Optional[List[str]] = None
    stats: Optional[Dict[str, Any]] = None
    metadata: Optional[Dict[str, Any]] = None


class WorkflowControlV1(BaseModel):
    """工作流控制命令 — Java 侧通过 gRPC Control RPC 发送此命令。

    用于控制正在执行的工作流，目前支持两种命令：
    - INTERRUPT: 中断工作流（用户取消或超时）
    - RESUME: 恢复被暂停的工作流（深度研究模式下用户确认后）

    字段说明：
    - workflowId: 要控制的工作流 ID
    - command: 控制命令（"INTERRUPT" / "RESUME"）
    - payload: 命令附加数据（如中断原因 message/reason）
    - createdAt: 命令创建时间
    """
    schemaVersion: str = "v1"
    workflowId: str
    command: str
    payload: Optional[Dict[str, Any]] = None
    createdAt: Optional[str] = None
