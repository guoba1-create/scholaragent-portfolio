"""
config.py — 全局配置文件

本文件集中管理所有外部服务的连接地址和密钥。
所有配置都通过环境变量（os.getenv）读取，方便在不同环境中部署。
如果环境变量未设置，会使用下面的默认值。

配置分组：
1. Java 内部工具 API — Python 调用 Java 侧工具的 HTTP 地址
2. MCP Server — 通过 MCP 协议调用工具的服务地址
3. gRPC — Python 侧 gRPC 服务器的监听端口
4. DashScope LLM — 阿里云大模型服务的 API 配置
"""

import os

# ── Java 内部工具 API 配置 ──
# Python Agent 运行时通过 HTTP 调用 Java 侧的工具接口（如检索、判质等）
# 例如：http://localhost:8080/internal/agent-tools/retrieve_evidence
AGENT_INTERNAL_TOOL_URL = os.getenv("AGENT_INTERNAL_TOOL_URL", "http://localhost:8080")

# 内部通信令牌（用于鉴权，防止未授权的请求访问工具接口）
AGENT_INTERNAL_TOKEN = os.getenv("AGENT_INTERNAL_TOKEN", "")

# ── MCP Server 配置 ──
# MCP（Model Context Protocol）是一种标准化的工具调用协议
# Java 侧实现了 MCP Server，Python 侧通过 SSE（Server-Sent Events）连接调用工具
MCP_SERVER_URL = os.getenv("MCP_SERVER_URL", "http://localhost:8080")

# ── gRPC 服务器端口 ──
# Python 侧启动 gRPC 服务器，Java 侧通过此端口发送 RunWorkflow 和 Control 请求
# 默认端口 50051（gRPC 的标准端口）
GRPC_PORT = int(os.getenv("GRPC_PORT", "50051"))

# ── DashScope 大模型服务配置 ──
# DashScope 是阿里云的大模型服务平台（兼容 OpenAI API 格式）
# 用于调用 LLM 进行推理（如规划、评估、综合等节点）
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")                    # API 密钥
DASHSCOPE_BASE_URL = os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")  # API 地址
DASHSCOPE_MODEL = os.getenv("DASHSCOPE_MODEL", "qwen-plus")              # 模型名称（默认 qwen-plus）
