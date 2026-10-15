"""
llm_client.py — LLM（大语言模型）调用客户端

本模块封装了与大语言模型（LLM）的通信逻辑。
使用 OpenAI 兼容的 API 格式调用阿里云 DashScope 服务。

主要功能：
1. chat() — 发送普通对话请求，返回文本回答
2. chat_json() — 发送对话请求并解析 JSON 回答（用于需要结构化输出的场景）

内置重试机制：
- 遇到限流（RateLimit）、超时（Timeout）、服务器错误（5xx）时自动重试
- 最多重试 3 次，每次等待时间翻倍（1s → 2s → 4s）
"""

import json
import logging
import re
import time
from contextvars import ContextVar, Token
from typing import Any
from openai import OpenAI, APIError, RateLimitError, APITimeoutError, APIConnectionError
from . import config

# 全局 OpenAI 客户端实例（懒加载，首次使用时创建）
_client = None
log = logging.getLogger(__name__)

# 重试配置
MAX_RETRIES = 3          # 最大重试次数
BASE_DELAY = 1.0         # 初始等待时间（秒），每次重试翻倍

# Every graph execution installs a workflow-scoped budget before entering any
# node. This keeps the hard limit in one place even when individual nodes build
# different prompts.
_runtime_budget: ContextVar[dict[str, Any] | None] = ContextVar("runtime_llm_budget", default=None)


def begin_llm_budget(workflow_id: str, context_budget: dict | None,
                     audit_sink: list[dict[str, Any]], default_max_tokens: int) -> Token:
    """Install a workflow-scoped hard prompt budget and an append-only audit sink."""
    raw = context_budget or {}
    max_tokens = _positive_int(raw.get("maxPromptTokens"), 0)
    if not max_tokens:
        max_chars = _positive_int(raw.get("maxPromptChars"), 0)
        max_tokens = max_chars // 4 if max_chars else default_max_tokens
    max_tokens = max(512, min(max_tokens, default_max_tokens))
    return _runtime_budget.set({
        "workflow_id": workflow_id,
        "max_prompt_tokens": max_tokens,
        "audits": audit_sink,
    })


def end_llm_budget(token: Token) -> None:
    """Reset the workflow-scoped budget when runtime execution ends."""
    _runtime_budget.reset(token)


def _get_client() -> OpenAI:
    """获取 OpenAI 客户端单例。

    使用懒加载模式：首次调用时创建客户端，后续调用直接返回缓存的实例。
    客户端配置来自 config.py 中的 DashScope 配置。
    """
    global _client
    if _client is None:
        _client = OpenAI(
            api_key=config.DASHSCOPE_API_KEY,
            base_url=config.DASHSCOPE_BASE_URL,
            timeout=30.0,  # 请求超时时间：30 秒
        )
    return _client


def chat(system_prompt: str, user_message: str, temperature: float = 0.1) -> str:
    """调用 LLM 进行对话，返回文本回答。

    这是最基础的 LLM 调用函数，所有需要 LLM 推理的地方最终都会调用此函数。

    参数：
    - system_prompt: 系统提示词（定义 LLM 的角色和行为规则）
    - user_message: 用户消息（具体的问题或任务描述）
    - temperature: 温度参数（0-1，越低回答越确定，越高越随机。默认 0.1，偏向确定性）

    返回值：LLM 的文本回答

    异常：如果所有重试都失败，抛出最后一次的异常
    """
    client = _get_client()
    compiled_system, compiled_user, prompt_audit = _compile_prompt(system_prompt, user_message)
    # 构造 OpenAI 格式的消息列表
    messages = [
        {"role": "system", "content": compiled_system},
        {"role": "user", "content": compiled_user},
    ]

    last_exc = None
    # 带重试的调用循环
    for attempt in range(MAX_RETRIES):
        call_started = time.time()
        try:
            resp = client.chat.completions.create(
                model=config.DASHSCOPE_MODEL,  # 使用的模型（如 qwen-plus）
                messages=messages,
                temperature=temperature,
            )
            # 返回第一个回答的文本内容
            answer = resp.choices[0].message.content
            prompt_audit.update({
                "status": "SUCCESS",
                "attempts": attempt + 1,
                "latencyMs": int((time.time() - call_started) * 1000),
                "estimatedOutputTokens": _estimate_tokens(answer),
                "model": config.DASHSCOPE_MODEL,
            })
            _append_audit(prompt_audit)
            return answer

        except RateLimitError as e:
            # 限流错误：请求太频繁，需要等待后重试
            last_exc = e
            delay = BASE_DELAY * (2 ** attempt)  # 指数退避：1s, 2s, 4s
            log.warning("LLM rate limited, retrying in %.1fs (attempt %d/%d)", delay, attempt + 1, MAX_RETRIES)

        except (APITimeoutError, APIConnectionError) as e:
            # 超时或连接错误：网络问题，需要等待后重试
            last_exc = e
            delay = BASE_DELAY * (2 ** attempt)
            log.warning("LLM timeout/connection error, retrying in %.1fs (attempt %d/%d)", delay, attempt + 1, MAX_RETRIES)

        except APIError as e:
            # API 错误：区分服务器错误（可重试）和客户端错误（不可重试）
            last_exc = e
            if e.status_code >= 500:
                # 5xx 服务器错误：可重试
                delay = BASE_DELAY * (2 ** attempt)
                log.warning("LLM server error %d, retrying in %.1fs (attempt %d/%d)", e.status_code, delay, attempt + 1, MAX_RETRIES)
            else:
                # 4xx 客户端错误（如 401 认证失败、400 参数错误）：直接抛出，不重试
                prompt_audit.update({"status": "FAILED", "attempts": attempt + 1, "error": str(e)[:300]})
                _append_audit(prompt_audit)
                raise

        # 等待后重试
        time.sleep(delay)

    # 所有重试都失败，抛出最后一次的异常
    prompt_audit.update({"status": "FAILED", "attempts": MAX_RETRIES, "error": str(last_exc)[:300]})
    _append_audit(prompt_audit)
    raise last_exc


def _compile_prompt(system_prompt: str, user_message: str) -> tuple[str, str, dict[str, Any]]:
    runtime = _runtime_budget.get() or {}
    max_tokens = int(runtime.get("max_prompt_tokens") or 8192)
    system = str(system_prompt or "")
    user = str(user_message or "")
    before = _estimate_tokens(system) + _estimate_tokens(user)
    citation_ids = sorted(set(re.findall(r"\bE\d+\b", system + "\n" + user)))

    # System policy is authoritative. It receives up to 25% of the prompt, and
    # the user/state payload receives the remaining budget.
    system_limit = max(256, min(_estimate_tokens(system), max_tokens // 4))
    compiled_system = _truncate_preserving_citations(system, system_limit)
    user_limit = max(256, max_tokens - _estimate_tokens(compiled_system) - 32)
    compiled_user = _truncate_preserving_citations(user, user_limit)
    after = _estimate_tokens(compiled_system) + _estimate_tokens(compiled_user)
    kept_ids = set(re.findall(r"\bE\d+\b", compiled_system + "\n" + compiled_user))
    missing = [item for item in citation_ids if item not in kept_ids]
    if missing:
        # The model must never see a dangling citation identity silently. A
        # compact identity ledger is appended even if the quote itself was cut.
        ledger = "\n[CITATION_LEDGER] " + ", ".join(missing)
        compiled_user = _truncate_preserving_citations(compiled_user, max(128, user_limit - _estimate_tokens(ledger))) + ledger
        after = _estimate_tokens(compiled_system) + _estimate_tokens(compiled_user)

    return compiled_system, compiled_user, {
        "workflowId": runtime.get("workflow_id"),
        "maxPromptTokens": max_tokens,
        "originalPromptTokens": before,
        "finalPromptTokens": after,
        "compressed": after < before,
        "citationCount": len(citation_ids),
        "citationsPreserved": not missing,
    }


def _truncate_preserving_citations(text: str, max_tokens: int) -> str:
    if _estimate_tokens(text) <= max_tokens:
        return text
    citation_lines = []
    for line in text.splitlines():
        if re.search(r"\bE\d+\b", line):
            citation_lines.append(line.strip()[:600])
    ledger = "\n".join(dict.fromkeys(citation_lines))
    ledger_tokens = min(_estimate_tokens(ledger), max_tokens // 3)
    if ledger and _estimate_tokens(ledger) > ledger_tokens:
        ledger = _slice_to_tokens(ledger, ledger_tokens)
    remaining = max(32, max_tokens - _estimate_tokens(ledger) - 8)
    head_tokens = max(16, int(remaining * 0.7))
    tail_tokens = max(8, remaining - head_tokens)
    head = _slice_to_tokens(text, head_tokens)
    tail = _slice_to_tokens(text[::-1], tail_tokens)[::-1]
    parts = [head, "\n[CONTEXT_COMPRESSED]\n", tail]
    if ledger:
        parts.extend(["\n[CITATION_EVIDENCE]\n", ledger])
    return "".join(parts)


def _slice_to_tokens(text: str, max_tokens: int) -> str:
    if not text or max_tokens <= 0:
        return ""
    low, high = 0, len(text)
    while low < high:
        middle = (low + high + 1) // 2
        if _estimate_tokens(text[:middle]) <= max_tokens:
            low = middle
        else:
            high = middle - 1
    return text[:low]


def _estimate_tokens(value: str) -> int:
    if not value:
        return 0
    cjk = len(re.findall(r"[\u3400-\u9fff]", value))
    return max(1, cjk + (len(value) - cjk + 3) // 4)


def _positive_int(value: Any, fallback: int) -> int:
    try:
        parsed = int(value)
        return parsed if parsed > 0 else fallback
    except (TypeError, ValueError):
        return fallback


def _append_audit(record: dict[str, Any]) -> None:
    runtime = _runtime_budget.get() or {}
    sink = runtime.get("audits")
    if isinstance(sink, list):
        sink.append(dict(record))


def chat_json(system_prompt: str, user_message: str, temperature: float = 0.1) -> dict:
    """调用 LLM 并解析返回的 JSON 数据。

    用于需要 LLM 返回结构化数据的场景（如规划、评估、综合等节点）。
    LLM 的回答可能是纯 JSON，也可能是 Markdown 包裹的 JSON，
    此函数会自动尝试多种方式提取 JSON。

    参数：同 chat()

    返回值：解析后的字典对象

    示例：
        result = chat_json("返回 JSON 格式", "请分析这个问题")
        # result = {"answer": "...", "confidence": 0.9}
    """
    raw = chat(system_prompt, user_message, temperature)
    return _extract_json(raw)


def _extract_json(text: str) -> dict:
    """从 LLM 回答中提取 JSON 数据。

    LLM 的回答格式不固定，此函数尝试以下策略按优先级提取：
    1. 直接解析整个文本为 JSON（最理想的情况）
    2. 从 ```json ... ``` 代码块中提取（Markdown 包裹的 JSON）
    3. 找到第一个 { 和最后一个 } 之间的内容解析（兜底策略）

    如果所有策略都失败，返回 {"error": "Failed to parse JSON", "raw": 原始文本}
    """
    # 策略 1：直接解析
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # 策略 2：从 Markdown 代码块中提取
    m = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(1))
        except json.JSONDecodeError:
            pass

    # 策略 3：找到最外层的 { ... } 并解析
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        try:
            return json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            pass

    # 所有策略都失败，返回错误信息
    return {"error": "Failed to parse JSON", "raw": text}
