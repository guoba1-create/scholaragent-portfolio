"""
prompts.py — 提示词构建模块

本模块负责构造发送给 LLM（大语言模型）的提示词（prompt）。
提示词是 LLM 的"指令"，告诉 LLM 它的角色、任务和输出格式。

本模块包含：
1. TOOL_DESCRIPTIONS — 所有可用工具的描述（告诉 LLM 有哪些工具可以调用）
2. DECISION_SYSTEM_PROMPT — ReAct 决策提示词（让 LLM 决定下一步做什么）
3. EVALUATE_SYSTEM_PROMPT — 评估提示词（让 LLM 评估证据是否充分）
4. REPLAN_SYSTEM_PROMPT — 重规划提示词（让 LLM 为缺失的证据生成补充步骤）
5. SYNTHESIZE_SYSTEM_PROMPT — 综合提示词（让 LLM 根据证据生成最终回答）
6. MONITOR_SYSTEM_PROMPT — 监控提示词（让 LLM 检查执行质量）
7. build_profile_prompt() — 构造 Agent 配置文件的提示词
8. build_decision_context() — 构造决策上下文（当前状态摘要）
9. build_monitor_context() — 构造监控上下文
"""

from .profiles import AgentProfile

# ── 工具描述字典 ──
# 每个工具的描述会嵌入到 LLM 的提示词中，让 LLM 知道有哪些工具可用、如何调用。
# 工具名称必须与 Java 侧 MCPToolRegistry 中注册的名称一致。
TOOL_DESCRIPTIONS = {
    "list_papers": (
        "Description: List uploaded papers in the current researchGroupId\n"
        "Type: RETRIEVAL\n"
        "Input parameters: (none)"
    ),
    "get_library_summary": (
        "Description: Return a compact summary of the document library\n"
        "Type: RETRIEVAL\n"
        "Input parameters: (none)"
    ),
    "query_rewrite": (
        "Description: Rewrite academic queries with coreference resolution and term expansion\n"
        "Type: ANALYSIS\n"
        "Input parameters:\n"
        "  - query: The original user query (string)"
    ),
    "retrieve_evidence": (
        "Description: Retrieve and rerank evidence spans for/against a claim\n"
        "Type: RETRIEVAL\n"
        "Input parameters:\n"
        "  - query: The search query (string)"
    ),
    "judge_evidence_quality": (
        "Description: Judge if retrieved evidence supports a claim (SUFFICIENT/PARTIAL/IRRELEVANT)\n"
        "Type: ANALYSIS\n"
        "Input parameters:\n"
        "  - claim: The claim to verify (string)\n"
        "  - evidence: The evidence text (string)"
    ),
    "read_document": (
        "Description: Read document text by documentId and page range or chunk index\n"
        "Type: RETRIEVAL\n"
        "Input parameters:\n"
        "  - documentId: Document ID (string)\n"
        "  - chunkIndex: Chunk index (integer)"
    ),
    "search_papers": (
        "Description: Search external academic papers through Semantic Scholar\n"
        "Type: EXTERNAL_RETRIEVAL\n"
        "Input parameters:\n"
        "  - query: Academic search query (string)\n"
        "  - limit: Maximum result count (integer)\n"
        "  - year: Optional publication year range, e.g. 2022-2026"
    ),
    "web_search": (
        "Description: Search the open web for latest papers, benchmarks, surveys, or project pages\n"
        "Type: EXTERNAL_RETRIEVAL\n"
        "Input parameters:\n"
        "  - query: Web search query (string)\n"
        "  - limit: Maximum result count (integer)\n"
        "  - language: Optional language code, e.g. zh-CN or en"
    ),
    "memory_recall": (
        "Description: Recall user preferences, prior research context, and semantic memories\n"
        "Type: MEMORY\n"
        "Input parameters:\n"
        "  - query: Current topic or user query (string)\n"
        "  - mode: full|structured|semantic"
    ),
    "list_tools": (
        "Description: Meta-tool: list all available tool names and descriptions\n"
        "Type: CUSTOM\n"
        "Input parameters: (none)"
    ),
}

# ── ReAct 决策系统提示词 ──
# 告诉 LLM 它是一个 ReAct 决策规划器，需要根据当前状态选择下一步动作。
# 动作类型：tool（调用工具）、handoff（委托给其他 Agent）、ask_user（询问用户）、finish（完成任务）
# LLM 必须返回严格的 JSON 格式（不能包含 Markdown 标记）
DECISION_SYSTEM_PROMPT = """You are a ReAct decision planner. Choose the next action based on the current task state.

Action types:
1. tool - call a tool
2. handoff - delegate to another Agent
3. ask_user - ask the user for clarification
4. finish - complete the task

Output strict JSON only (no markdown, no explanation):
{{
  "reasoningSummary": "one sentence explaining why this action is chosen",
  "action": {{
    "actionType": "tool|handoff|ask_user|finish",
    "actionName": "tool or target name (for tool/handoff), or question text (for ask_user)",
    "arguments": {{}}
  }},
  "expectedObservation": "what information you expect to get next",
  "confidence": 0.8
}}

{profile_prompt}"""

# ---------------------------------------------------------------------------
# Plan-Execute-Replan（PER）提示词
# PER 模式：先制定计划（Plan）→ 执行检索（Execute）→ 评估质量 → 必要时重规划（Replan）
# ---------------------------------------------------------------------------

# ── 评估提示词 ──
# 让 LLM 评估已检索的证据是否充分覆盖了计划中的所有步骤
# 返回 coverage_score（覆盖率）、quality_score（质量分）、gaps（缺失步骤）、decision（决策）
EVALUATE_SYSTEM_PROMPT = """你是一个学术研究质量评估器。评估当前检索到的证据是否充分覆盖了研究计划中的所有步骤。

评估维度：
1. coverage_score (0-1): 计划步骤中已获得充分证据的比例
2. quality_score (0-1): 证据的相关性和可信度
3. gaps: 未覆盖或证据不足的步骤列表
4. decision: "pass"（进入综合）| "replan"（需要补充检索）| "fail"（无法完成）

输出严格 JSON（不要包含 Markdown 标记）：
{{"coverage_score": 0.8, "quality_score": 0.7, "gaps": [{{"step_id": 2, "reason": "..."}}], "decision": "replan"}}"""

# ── 重规划提示词 ──
# 当评估发现证据不足时，让 LLM 为缺失的步骤生成补充检索计划
# 规则：只为 gaps 中的步骤生成补充计划，尝试不同的检索策略
REPLAN_SYSTEM_PROMPT = """你是一个学术研究重规划器。根据评估结果中的 gaps，生成补充检索步骤。

规则：
1. 只为 gaps 中的步骤生成补充计划
2. 尝试使用不同的 search_query 或检索策略
3. 不要重复已完成的步骤

输出严格 JSON（不要包含 Markdown 标记）：
{{"new_steps": [{{"step_id": "2b", "description": "...", "tool_hint": "retrieve_evidence", "search_query": "..."}}]}}"""

# ── 综合提示词 ──
# 让 LLM 根据检索到的证据生成最终的综合回答
# 要求：基于证据回答、引用具体证据编号、结构清晰、给出验证结论
SYNTHESIZE_SYSTEM_PROMPT = """你是一个学术研究综合分析器。根据检索到的证据和研究计划，生成高质量的综合回答。

要求：
1. 回答必须基于提供的证据，引用具体的证据编号 [Evidence N]
2. 结构清晰，使用 Markdown 格式
3. 如果证据不足，明确指出哪些方面缺乏支持
4. 对于观点验证场景，给出明确的验证结论（支持/反对/部分支持）

输出严格 JSON（不要包含 Markdown 标记）：
{{"answer": "综合回答（Markdown 格式）", "key_findings": ["发现1", "发现2"], "confidence": 0.85}}"""

# ── 监控提示词 ──
# 让 LLM 检查当前执行状态，判断是否应该继续执行
# 判断标准：是否有必要继续、结果是否完整、是否有警告、是否超出预算
MONITOR_SYSTEM_PROMPT = """你是一个 ReAct 质量监控器。检查当前执行状态，判断是否应该继续。

【判断标准】
1. 是否还有必要继续？
2. 当前结果是否足够完整？
3. 是否有警告需要处理？
4. 是否超出预算？

【输出要求】
请严格按照以下 JSON 格式输出（不要包含 Markdown 标记）：
{{
  "shouldContinue": true,
  "qualityStatus": "pass|warning|fail",
  "warnings": ["警告1", "警告2"],
  "nextStepHint": "建议下一步做什么",
  "canFinish": false
}}

【Profile 上下文】
{profile_prompt}"""


def build_profile_prompt(profile: AgentProfile) -> str:
    """根据 AgentProfile 构造 Agent 的系统提示词。

    将 Agent 的角色描述、允许的工具、允许的动作、终止条件等信息
    格式化为 LLM 可理解的提示词文本。

    参数：
    - profile: Agent 配置文件（定义了 Agent 的角色、能力、约束）

    返回值：格式化的提示词字符串（会嵌入到 DECISION_SYSTEM_PROMPT 中）
    """
    tool_desc_lines = []
    for tool_name in sorted(profile.allowed_tools):
        desc = TOOL_DESCRIPTIONS.get(tool_name)
        if desc:
            tool_desc_lines.append(f"### {tool_name}\n{desc}")

    tool_block = "\n\n".join(tool_desc_lines) if tool_desc_lines else "(no tools)"

    return f"""Profile id: {profile.profile_id}
Profile name: {profile.name}
Role:
{profile.role_prompt}

Hard runtime rules:
- Allowed actions: {', '.join(sorted(profile.allowed_actions))}
- Allowed tool names: {', '.join(sorted(profile.allowed_tools))}
- For a library inventory question, call list_papers or get_library_summary.
- For a specific document/page/chunk read, call read_document.
- For semantic evidence retrieval, call retrieve_evidence.
- For latest/SOTA/survey/benchmark gaps, use search_papers or web_search only if those tools are allowed by the profile.
- Never invent tool names. Only call names listed in Allowed tool names.
- If a needed tool is absent, choose ask_user or finish with the limitation.

Termination conditions:
{chr(10).join('- ' + c for c in profile.termination_conditions) if profile.termination_conditions else '(none)'}

Quality gate rules:
{chr(10).join('- ' + r for r in profile.quality_gate_rules) if profile.quality_gate_rules else '(none)'}

{tool_block}"""


def build_decision_context(state: dict) -> str:
    """构造 ReAct 决策的上下文信息。

    将当前工作流状态（用户查询、运行时状态、工作记忆、最近观察等）
    格式化为 LLM 可理解的上下文文本，作为 DECISION_SYSTEM_PROMPT 的用户消息。

    参数：
    - state: ReAct 状态字典（包含 query, step_index, tool_call_count, working_memory 等）

    返回值：格式化的上下文字符串
    """
    parts = []

    # User query
    parts.append(f"[User Query]\n{state.get('query', '')}")

    prompt_context = state.get("prompt_context")
    if prompt_context:
        parts.append(f"[Memory And Context Budget]\n{prompt_context[:3000]}")

    route_decision = state.get("route_decision") or {}
    if route_decision:
        parts.append(f"[Route Decision]\nEngine={route_decision.get('engine')} / "
                     f"TaskType={route_decision.get('taskType')} / Profile={route_decision.get('profile')}")

    # Runtime status
    profile_id = state.get("profile", "deep_research_react")
    from .profiles import get_profile
    profile = get_profile(profile_id)
    max_steps = profile.max_steps if profile else 5
    max_tool_calls = profile.max_tool_calls if profile else 6
    step_idx = state.get("step_index", 0)
    tc = state.get("tool_call_count", 0)
    parts.append(
        f"[Runtime Status]\nStep: {step_idx + 1}/{max_steps}, "
        f"Tool calls: {tc}/{max_tool_calls}"
    )

    # Working memory
    wm = state.get("working_memory", {})
    claims = wm.get("atomic_claims", [])
    if claims:
        numbered = "\n".join(f"  {i+1}. {c}" for i, c in enumerate(claims))
        parts.append(f"[Atomic Claims]\n{numbered}")

    evidence = wm.get("evidence_spans", [])
    if evidence:
        parts.append(f"[Evidence Summary]\n{len(evidence)} evidence span(s) collected")

    verifications = wm.get("verification_results", [])
    if verifications:
        lines = []
        for v in verifications:
            lines.append(f"  - {v.get('claim', '?')}: {v.get('support_status', '?')}")
        parts.append(f"[Verification Results]\n" + "\n".join(lines))

    # Recent observations (last 3)
    observations = state.get("observations", [])
    recent = observations[-3:] if observations else []
    if recent:
        lines = []
        for obs in recent:
            status = "OK" if obs.get("success") else "FAIL"
            lines.append(f"  - [{status}] {obs.get('tool_name', '?')}: {obs.get('summary', '')[:200]}")
        parts.append(f"[Recent Observations]\n" + "\n".join(lines))

    # Warnings
    warnings = state.get("warnings", [])
    if warnings:
        parts.append(f"[Warnings]\n" + "\n".join(f"  - {w}" for w in warnings))

    return "\n\n".join(parts)


def build_monitor_context(state: dict) -> str:
    """构造质量监控的上下文信息。

    将当前执行状态（步骤进度、证据数量、最新观察结果等）
    格式化为 LLM 可理解的上下文文本，作为 MONITOR_SYSTEM_PROMPT 的用户消息。

    参数：
    - state: ReAct 状态字典

    返回值：格式化的上下文字符串
    """
    parts = []
    profile_id = state.get("profile", "deep_research_react")
    from .profiles import get_profile
    profile = get_profile(profile_id)
    max_steps = profile.max_steps if profile else 5
    step_idx = state.get("step_index", 0)

    budget_pct = round((step_idx + 1) / max_steps * 100)
    parts.append(f"Step: {step_idx + 1}/{max_steps} ({budget_pct}% of budget)")

    wm = state.get("working_memory", {})
    parts.append(f"Evidence spans: {len(wm.get('evidence_spans', []))}")
    parts.append(f"Verification results: {len(wm.get('verification_results', []))}")

    observations = state.get("observations", [])
    if observations:
        last = observations[-1]
        status = "SUCCESS" if last.get("success") else "FAILED"
        parts.append(f"Latest observation: [{status}] {last.get('tool_name', '?')}")
        parts.append(f"Summary: {last.get('summary', '')[:300]}")

    warnings = state.get("warnings", [])
    if warnings:
        parts.append(f"Warnings: {warnings}")

    return "\n".join(parts)
