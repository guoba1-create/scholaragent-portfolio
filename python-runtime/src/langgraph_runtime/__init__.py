"""
langgraph_runtime — Python Agent 运行时

本包是学术研究 Agent 的 Python 运行时，负责执行 AI Agent 的推理和工具调用。

整体架构：
    Java 侧（Spring Boot）                     Python 侧（本包）
    ┌─────────────────────┐                   ┌─────────────────────┐
    │  WorkflowRunService │ ──gRPC──→         │  grpc_server.py     │
    │  ExecutionDecision  │                   │    ↓                │
    │  MCP Server          │ ←─MCP──          │  react_runtime.py   │
    │  SSE → Frontend      │                   │  workflow_runtime.py│
    └─────────────────────┘                   │    ↓                │
                                              │  graph.py /         │
                                              │  workflow_graph.py  │
                                              │    ↓                │
                                              │  llm_client.py      │
                                              │  mcp_tool_client.py │
                                              └─────────────────────┘

两种执行引擎：
1. DeepResearch Agent 引擎（graph.py + react_runtime.py）
   - DeepResearch Coordinator 执行 Plan-Execute-Evaluate-Replan
   - 仅在外部证据缺口时 handoff 给 External Scholar Scout
   - 通用节点：reason → tool → monitor → reason/finish
   - DeepResearch 分支：deep_plan → deep_execute → deep_evaluate
     → deep_handoff_external?/deep_replan? → deep_synthesize

2. Evidence Workflow 引擎（workflow_graph.py + workflow_runtime.py）
   - 根据 taskType 选择固定的核验、比较、相关工作或引用支持模板
   - query_prepare 只准备检索表达，不动态改变业务拓扑
   - 执行流程：route → query_prepare → retrieve → evaluate
     → replan?（最多一次）→ synthesize → monitor → finish

简单文献问答由 Java Quick QA 执行，不进入 Python Agent 循环。

核心模块：
- config.py: 全局配置（API 地址、密钥、端口）
- models.py: 数据模型（请求、事件、结果的 Pydantic 模型）
- profiles.py: Agent 配置文件（角色、能力、资源限制）
- prompts.py: 提示词构造（系统提示词、上下文组装）
- llm_client.py: LLM 调用客户端（DashScope API）
- mcp_tool_client.py: MCP 协议工具调用客户端
- runtime_support.py: 通用工具函数（ID 生成、追踪构建等）
- checkpoint_store.py: LangGraph 检查点持久化
- runtime_state_store.py: 工作流运行时状态持久化
- grpc_server.py: gRPC 服务端（接收 Java 侧请求）
- main.py: 程序入口
"""
