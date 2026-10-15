package com.team.rag.agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.cache.LlmResponseCacheService;
import com.team.rag.service.PromptTemplateService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Low-level two-stage intent classifier used by {@link ExecutionDecisionService}.
 *
 * <p>It keeps the legacy engine-shaped response during API migration, but
 * product code consumes {@link ExecutionDecision}: intent, strategy,
 * workflow template and optional Agent profile are separate concepts.</p>
 *
 * <h2>职责</h2>
 * 接收用户消息，经过两级递进分析，给出兼容分类结果：
 * <ul>
 *   <li><b>intent</b>（意图）：用户想干什么？例如 paper_qa、claim_verification、reject 等。</li>
 *   <li><b>engine</b>（引擎）：用哪种执行模式？rag / react / workflow。</li>
 *   <li><b>profile</b>（配置档）：具体使用哪个 prompt 模板或 Agent 配置。</li>
 * </ul>
 *
 * <h2>两级分类与失败兜底</h2>
 * <ol>
 *   <li><b>Layer 1 — 关键词信号分析</b>（零成本、微秒级）
 *       通过正则/字符串匹配处理边界清晰、需要确定性响应的请求。
 *       这一层不调用 LLM。</li>
 *   <li><b>Layer 2 — LLM 意图分类</b>（低成本、百毫秒级）
 *       当 Layer 1 关键词全部未命中时，调用 LLM（复用 research-intent-router 模板）
 *       进行七分类意图识别，覆盖模糊但复杂的请求。</li>
 *   <li><b>兜底 — 保守默认值</b>
 *       当 LLM 也不可用或置信度不足时，使用 rag + paper_qa 作为安全默认。</li>
 * </ol>
 * 核心原则：<b>先规则，再 LLM，最后保守默认</b> —— 用最低成本覆盖最多场景。
 *
 * <h2>在系统中的位置</h2>
 * 本类只由 {@link ExecutionDecisionService} 调用，Controller 和运行时
 * 统一消费产品级 {@link ExecutionDecision}。
 *
 * @see HybridRouteDecision
 */
@Service
public class HybridRouterService {

    private static final Logger log = LoggerFactory.getLogger(HybridRouterService.class);

    private static final String LLM_ROUTE_TEMPLATE = "research-intent-router";
    private static final double LLM_CONFIDENCE_THRESHOLD = 0.7;
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final ChatLanguageModel chatModel;
    private final PromptTemplateService promptTemplateService;
    private final LlmResponseCacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HybridRouterService(ChatLanguageModel chatModel,
                               PromptTemplateService promptTemplateService,
                               LlmResponseCacheService cacheService) {
        this.chatModel = chatModel;
        this.promptTemplateService = promptTemplateService;
        this.cacheService = cacheService;
    }

    /**
     * 简化入口 —— 仅传入用户消息，引擎和配置档均使用自动推断。
     * 适用于大多数场景：上层不关心具体路由细节，只需一个"最佳猜测"结果。
     *
     * @param message 用户输入的消息文本
     * @return 路由决策结果，包含 intent、engine、profile、confidence 等信息
     */
    public HybridRouteDecision analyze(String message) {
        // 委托给完整的 decide 方法，engine 和 profile 均设为 "auto" 表示由路由自动决定
        return decide(message, "auto", "auto");
    }

    /**
     * 完整路由决策入口 —— 支持手动覆盖引擎和配置档。
     *
     * <p>决策流程：
     * <ol>
     *   <li>先通过 {@link #analyzeSignals(String)} 进行 Layer 1 关键词分析</li>
     *   <li>如果调用方明确指定了 engine（非 "auto"），则强制覆盖路由结果（人工优先）</li>
     *   <li>如果调用方明确指定了 profile（非 "auto"），则强制覆盖配置档</li>
     *   <li>如果最终 profile 为空，自动根据 engine 和 taskType 推断默认 profile</li>
     *   <li>附加元数据（请求参数、路由器版本等），便于调试和日志追踪</li>
     * </ol>
     *
     * @param message          用户输入的消息文本
     * @param requestedEngine  请求的引擎类型："auto"/"rag"/"react"/"workflow"/"claim" 等，"auto" 表示自动推断
     * @param requestedProfile requested profile, normally auto or deep_research_react
     * @return 最终的路由决策结果
     */
    public HybridRouteDecision decide(String message, String requestedEngine, String requestedProfile) {
        // Layer 1: 先通过关键词信号分析获得初步决策
        HybridRouteDecision decision = analyzeSignals(message);
        // 将用户请求的引擎和配置档名称标准化（统一大小写、别名映射等）
        String override = normalizeEngine(requestedEngine);
        String profile = normalizeProfile(requestedProfile);

        // 如果用户明确指定了引擎（非 "auto"），则强制覆盖路由结果
        // 这实现了"人工优先"原则：用户知道自己要什么时，直接听用户的
        boolean manualEngineOverride = !"auto".equals(override);
        if (manualEngineOverride) {
            decision.engine = override;
            // 手动覆盖时置信度拉满（0.99），表示这是确定性决策而非猜测
            decision.confidence = Math.max(decision.confidence, 0.99);
            decision.reason = "Manual engine override: " + override + ". " + safeReason(decision.reason);
            decision.addSignal("manual_engine_override:" + override);
        }

        // 同理，用户明确指定了 profile 时也直接覆盖
        if (!"auto".equals(profile)) {
            decision.profile = profile;
            decision.addSignal("manual_profile_override:" + profile);
        }

        // 如果经过上述处理后 profile 仍为空，根据 engine 和 taskType 推断一个合理的默认值
        // 例如 react 引擎 + deep_research 任务 → deep_research_react 配置档
        if (decision.profile == null || decision.profile.isBlank()) {
            decision.profile = defaultProfile(decision.engine, decision.taskType);
        }

        // DeepResearch is a profile-driven Agent, not a deterministic Workflow.
        // Keep it on the ReAct runtime even when a caller manually selected
        // workflow, otherwise it falls back to the fixed workflow graph.
        if (!manualEngineOverride && ("deep_research".equals(decision.taskType)
                || "research_analysis".equals(decision.taskType)
                || "research_gap".equals(decision.taskType))) {
            decision.engine = "react";
            decision.profile = "deep_research_react";
            decision.reason = "DeepResearch is handled by the dedicated ReAct agent. "
                    + safeReason(decision.reason);
            decision.addSignal("deep_research_forced_to_react_agent");
        }

        // Fixed workflow templates are selected by taskType/WorkflowTemplate.
        // executionMode remains null as a wire-compatibility field.
        decision.executionMode = null;

        // 构建元数据 Map，记录本次路由的原始请求参数和路由器版本
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestedEngine", requestedEngine == null ? "auto" : requestedEngine);
        metadata.put("requestedProfile", requestedProfile == null ? "auto" : requestedProfile);
        metadata.put("routerVersion", "hybrid-v2");
        decision.metadata = metadata;
        return decision;
    }

    /**
     * Layer 1 —— 关键词信号分析（零 LLM 成本，微秒级响应）。
     *
     * <p>这是两阶段分类中的第一层，通过简单的字符串匹配处理明确请求。
     * 全部未命中时交给 {@link #llmClassify(String)} 做 LLM 意图分类。
     * 分析优先级从高到低依次为：
     * <ol>
     *   <li>空消息 → 返回 clarification_needed，提示用户补充问题</li>
     *   <li>安全关键词（hack、攻击、恶意等）→ reject，拒绝回答</li>
     *   <li>证据/核实关键词（依据、evidence、claim 等）→ claim_verification</li>
     *   <li>复杂分析关键词（深入分析、研究空白、推理链等）→ deep_research</li>
     *   <li>综合比较关键词（综述、对比、compare 等）→ research_synthesis</li>
     *   <li>基础问答关键词（是什么、论文、literature 等）→ paper_qa</li>
     * </ol>
     * 如果所有关键词都未命中，则进入 Layer 2（LLM fallback）或使用保守默认值。
     *
     * @param message 用户输入的原始消息
     * @return 路由决策结果；如果所有规则都未命中且 LLM 不可用，返回保守的 rag 默认决策
     */
    private HybridRouteDecision analyzeSignals(String message) {
        // 防御性处理：null 消息视为空字符串，避免后续 NPE
        String text = message == null ? "" : message.trim();
        // 统一转小写，确保中英文关键词都能匹配（英文不区分大小写）
        String lower = text.toLowerCase(Locale.ROOT);

        // ---- 规则 0: 空消息检测 ----
        // 用户发了空消息，可能是误触或未输入完成，返回 clarification_needed 让上层提示用户
        if (text.isBlank()) {
            return HybridRouteDecision.of("rag", "clarification_needed",
                            null, 1.0, "Empty message requires clarification.")
                    .addSignal("empty_message")
                    .addSignal("clarification_required");
        }

        // ---- \u89c4\u5219 1: \u5b89\u5168\u5173\u952e\u8bcd\u8fc7\u6ee4\uff08\u6700\u9ad8\u4f18\u5148\u7ea7\uff09----
        // \u68c0\u6d4b\u5230\u653b\u51fb\u3001\u7ed5\u8fc7\u3001\u6076\u610f\u7b49\u5173\u952e\u8bcd\u65f6\uff0c\u76f4\u63a5 reject \u62d2\u7edd\u56de\u7b54
        // \u4f7f\u7528 workflow \u5f15\u64ce\u800c\u975e rag/react\uff0c\u56e0\u4e3a reject \u8d70\u7684\u662f\u56fa\u5b9a\u56de\u590d\u6d41\u7a0b
        if (containsAny(lower, "hack", "jailbreak",
                "\u7ed5\u8fc7", "\u653b\u51fb", "\u6076\u610f", "\u8fdd\u6cd5")) {
            return HybridRouteDecision.of("workflow", "reject",
                            null, 0.95, "Safety keyword detected.")
                    .addSignal("safety_signal");
        }

        // ---- Rule 1.5: Chitchat / social intent ----
        if (isLikelyChitchat(lower, text)) {
            if (containsAcademicSignal(lower)) {
                return HybridRouteDecision.of("rag", "paper_qa",
                                null, 0.86, "Anti-misroute: chitchat with academic keywords, reverse to paper_qa.")
                        .addSignal("chitchat_keywords")
                        .addSignal("chitchat_reversed_to_paper_qa")
                        .addSignal("academic_keywords");
            }
            return HybridRouteDecision.of("rag", "chitchat",
                            null, 0.85, "General chitchat signal detected.")
                    .addSignal("chitchat_keywords");
        }

        // ---- \u89c4\u5219 2: \u8bc1\u636e/\u6838\u5b9e\u7c7b\u5173\u952e\u8bcd \u2192 claim_verification ----
        if (containsAny(lower,
                "\u6709\u8bba\u6587\u4f9d\u636e", "\u8bba\u6587\u4f9d\u636e",
                "\u6587\u732e\u4f9d\u636e", "\u662f\u5426\u6709\u4f9d\u636e",
                "\u6709\u6ca1\u6709\u4f9d\u636e", "\u6709\u8bba\u6587\u652f\u6301",
                "\u6587\u732e\u652f\u6301", "\u662f\u5426\u6210\u7acb",
                "\u662f\u5426\u6b63\u786e", "\u662f\u5426\u53ef\u9760",
                "\u6838\u5b9e", "\u6838\u9a8c", "\u9a8c\u8bc1", "claim", "evidence", "support")) {
            return HybridRouteDecision.of("workflow", "claim_verification",
                            null, 0.92, "Claim/evidence verification signal detected.")
                    .addSignal("claim_verification_keywords");
        }

        // ---- Rule 2.5: Deep research (negation-aware) ----
        if (containsNonNegatedAny(lower,
                "\u6df1\u5ea6\u7814\u7a76", "\u7cfb\u7edf\u7efc\u8ff0", "\u5168\u9762\u8c03\u7814",
                "deep research", "\u7cfb\u7edf\u6027\u56de\u987e", "\u6df1\u5165\u8c03\u7814",
                "\u6df1\u5ea6\u8c03\u7814", "\u5168\u9762\u7814\u7a76")) {
            return HybridRouteDecision.of("react", "deep_research",
                            null, 0.93, "Deep research signal detected.")
                    .addSignal("deep_research_keywords");
        }

        // ---- Rule 2.6: Citation recommendation (negation-aware) ----
        if (containsNonNegatedAny(lower,
                "\u5f15\u7528\u63a8\u8350", "\u63a8\u8350\u5f15\u7528",
                "citation recommendation", "\u63a8\u8350\u6587\u732e",
                "\u5f15\u7528\u5efa\u8bae", "\u53c2\u8003\u6587\u732e\u63a8\u8350")) {
            return HybridRouteDecision.of("workflow", "citation_recommendation",
                            null, 0.90, "Citation recommendation signal detected.")
                    .addSignal("citation_recommendation_keywords");
        }

        // ---- Rule 2.7: Paper comparison (negation-aware) ----
        if (containsNonNegatedAny(lower,
                "\u5bf9\u6bd4", "\u6bd4\u8f83", "\u5dee\u5f02", "compare", "versus", "vs", "\u5f02\u540c")) {
            return HybridRouteDecision.of("workflow", "paper_compare",
                            null, 0.88, "Paper comparison signal detected.")
                    .addSignal("paper_compare_keywords");
        }

        // ---- Rule 2.8: Related work (negation-aware) ----
        if (containsNonNegatedAny(lower, "related work", "\u7efc\u8ff0", "\u6587\u732e\u7efc\u8ff0",
                "\u7814\u7a76\u73b0\u72b6", "\u7814\u7a76\u8fdb\u5c55")) {
            return HybridRouteDecision.of("workflow", "related_work",
                            null, 0.86, "Related work signal detected.")
                    .addSignal("related_work_keywords");
        }

        // ---- Rule 3: Open-ended research analysis -> react ----
        // \u7528\u6237\u9700\u8981\u6df1\u5ea6\u5206\u6790\u3001\u591a\u6b65\u63a8\u7406\u3001\u8d8b\u52bf\u7814\u5224\u7b49\u590d\u6742\u4efb\u52a1
        // react \u5f15\u64ce\u652f\u6301\u591a\u8f6e\u601d\u8003-\u884c\u52a8\u5faa\u73af\uff08ReAct \u6a21\u5f0f\uff09\uff0c\u9002\u5408\u9700\u8981\u8fed\u4ee3\u63a8\u7406\u7684\u573a\u666f
        if (containsNonNegatedAny(lower,
                "\u6df1\u5165\u5206\u6790", "\u6df1\u5ea6\u5206\u6790",
                "\u5168\u9762\u5206\u6790", "\u7cfb\u7edf\u5206\u6790",
                "\u7814\u7a76\u7a7a\u767d", "\u5f00\u653e\u5f0f",
                "\u89c4\u5212", "\u591a\u6b65", "\u534f\u4f5c",
                "handoff", "\u53cd\u601d", "\u63a8\u7406\u94fe",
                "\u590d\u6742", "\u6f14\u8fdb", "\u8d8b\u52bf")) {
            // profile \u7559\u7a7a\uff0c\u7531 defaultProfile() \u6839\u636e taskType \u6620\u5c04
            return HybridRouteDecision.of("react", "deep_research",
                            null, 0.88, "Complex research analysis signal detected.")
                    .addSignal("complex_react_keywords");
        }

        // ---- Rule 4: Basic retrieval QA -> rag ----
        // \u7528\u6237\u5728\u95ee"\u4ec0\u4e48\u662f""\u89e3\u91ca""\u68c0\u7d22"\u7b49\u57fa\u7840\u95ee\u9898\uff0c\u6216\u63d0\u5230\u4e86\u5177\u4f53\u6280\u672f\u540d\u8bcd
        // rag \u5f15\u64ce\u505a\u5355\u8f6e\u68c0\u7d22-\u751f\u6210\u5373\u53ef\uff0c\u4e0d\u9700\u8981\u591a\u6b65\u63a8\u7406
        if (containsAny(lower,
                "\u4ec0\u4e48\u662f", "\u662f\u4ec0\u4e48", "\u89e3\u91ca",
                "\u4ecb\u7ecd", "\u68c0\u7d22", "\u67e5\u627e",
                "\u641c\u7d22", "\u8bba\u6587", "\u6587\u732e",
                "next-poi", "transformer", "gnn", "rag", "paper", "literature")) {
            // profile \u7559\u7a7a\uff0c\u7531 defaultProfile() \u6839\u636e taskType \u6620\u5c04
            return HybridRouteDecision.of("rag", "paper_qa",
                            null, 0.82, "Retrieval QA signal detected.")
                    .addSignal("rag_keywords");
        }

        // ---- Layer 1 全部规则未命中，尝试 LLM 意图分类 ----
        HybridRouteDecision llmDecision = llmClassify(message);
        if (llmDecision != null) {
            return llmDecision;
        }

        // ---- LLM 也不可用或置信度不足，使用保守默认值 ----
        return HybridRouteDecision.of("rag", "paper_qa",
                        null, 0.62, "No strong signal; using retrieval QA as conservative default.")
                .addSignal("default_rag");
    }

    /**
     * Layer 2 —— LLM 意图分类（当关键词规则全部未命中时调用）。
     *
     * <p>复用已有的 {@code research-intent-router} prompt 模板，通过 LLM 对用户消息进行
     * 七分类意图识别（paper_qa / claim_verification / paper_compare / related_work /
     * citation_recommendation / deep_research / clarification_needed）。
     *
     * <p>结果经过 {@link LlmResponseCacheService} 缓存，相同查询不会重复调用 LLM。
     *
     * @param message 用户输入的原始消息
     * @return 路由决策结果；LLM 调用失败或置信度不足时返回 null，由调用方回退到保守默认
     */
    private HybridRouteDecision llmClassify(String message) {
        try {
            String prompt = promptTemplateService.render(LLM_ROUTE_TEMPLATE, "query", message);
            String response = cacheService.getOrCompute("route-llm", prompt,
                    () -> chatModel.generate(prompt));

            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("LLM route: no JSON found in response");
                return null;
            }

            JsonNode json = objectMapper.readTree(jsonStr);
            String taskType = json.has("intent") ? json.get("intent").asText() : "";
            double confidence = json.has("confidence") ? json.get("confidence").asDouble(0.5) : 0.5;
            String reason = json.has("reason") ? json.get("reason").asText() : "";

            if (taskType.isBlank() || confidence < LLM_CONFIDENCE_THRESHOLD) {
                log.info("LLM route: low confidence ({}) or empty taskType '{}', using default", confidence, taskType);
                return null;
            }

            HybridRouteDecision decision = mapLlmIntentToDecision(taskType, confidence, reason);
            decision.addSignal("llm_classify:" + taskType);
            log.info("LLM route: taskType={}, confidence={}, engine={}", taskType, confidence, decision.engine);
            return decision;
        } catch (Exception e) {
            log.warn("LLM route failed: {}, falling back to default", e.getMessage());
            return null;
        }
    }

    /**
     * 将 LLM 返回的意图字符串映射为完整的路由决策。
     *
     * <p>映射规则与 {@link #analyzeSignals(String)} 中的关键词规则保持一致，
     * 确保无论走关键词还是 LLM，相同意图产生相同的 engine/taskType。
     *
     * @param taskType   LLM 返回的意图类型（如 "claim_verification"）
     * @param confidence LLM 返回的置信度（0.0~1.0）
     * @param reason     LLM 返回的判断理由
     * @return 完整的路由决策结果
     */
    private HybridRouteDecision mapLlmIntentToDecision(String taskType, double confidence, String reason) {
        return switch (taskType) {
            case "claim_verification" -> HybridRouteDecision.of(
                    "workflow", "claim_verification",
                    null, confidence, "LLM: " + reason);
            case "paper_compare" -> HybridRouteDecision.of(
                    "workflow", "paper_compare",
                    null, confidence, "LLM: " + reason);
            case "related_work" -> HybridRouteDecision.of(
                    "workflow", "related_work",
                    null, confidence, "LLM: " + reason);
            case "citation_recommendation" -> HybridRouteDecision.of(
                    "workflow", "citation_recommendation",
                    null, confidence, "LLM: " + reason);
            case "deep_research" -> HybridRouteDecision.of(
                    "react", "deep_research",
                    null, confidence, "LLM: " + reason);
            case "chitchat", "general_chat" -> HybridRouteDecision.of(
                    "rag", "chitchat",
                    null, confidence, "LLM: " + reason);
            case "clarification_needed" -> HybridRouteDecision.of(
                    "rag", "paper_qa",
                    null, confidence, "LLM: " + reason);
            // paper_qa 和其他未知意图统一走 rag
            default -> HybridRouteDecision.of(
                    "rag", "paper_qa",
                    null, confidence, "LLM: " + reason);
        };
    }

    /**
     * 从 LLM 响应文本中提取 JSON 字符串。
     *
     * <p>处理两种常见格式：
     * <ul>
     *   <li>纯 JSON：直接返回</li>
     *   <li>Markdown 代码块包裹：{@code ```json {...} ```}，提取中间部分</li>
     * </ul>
     *
     * @param response LLM 原始响应文本
     * @return 提取出的 JSON 字符串；无法提取时返回 null
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String trimmed = response.trim();

        // 尝试匹配 ```json ... ``` 代码块
        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart >= 0) {
            int jsonStart = trimmed.indexOf("{", codeBlockStart);
            int codeBlockEnd = trimmed.lastIndexOf("```");
            if (jsonStart >= 0 && codeBlockEnd > jsonStart) {
                return trimmed.substring(jsonStart, codeBlockEnd).trim();
            }
        }

        // 直接匹配 {...}
        Matcher matcher = JSON_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }

    /**
     * 判断文本中是否包含任意一个关键词（大小写不敏感）。
     * 这是 Layer 1 关键词匹配的核心工具方法。
     *
     * @param text     待检测的文本（应已转为小写）
     * @param keywords 要匹配的关键词列表（支持中英文混合）
     * @return 如果文本中包含任意一个关键词返回 true，否则返回 false
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            // 防御性检查：跳过 null 和空白关键词，避免 NPE 和无意义匹配
            // 将关键词也转为小写，确保大小写不敏感匹配
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect whether user input is likely a social chat turn.
     * This rule intentionally excludes requests that also contain clear academic signals.
     */
    private boolean isLikelyChitchat(String lower, String originalText) {
        if (!containsChitchatSignal(lower)) {
            return false;
        }
        return originalText.length() <= 120;
    }

    private boolean containsChitchatSignal(String lower) {
        boolean socialSignal = containsAny(lower,
                "\u4f60\u597d", "\u60a8\u597d", "\u55e8", "\u54c8\u55bd",
                "\u65e9\u4e0a\u597d", "\u4e2d\u5348\u597d", "\u665a\u4e0a\u597d",
                "\u5728\u5417", "\u8c22\u8c22", "\u611f\u8c22", "\u665a\u5b89",
                "\u95f2\u804a",
                "hello", "hi", "hey", "good morning", "good evening",
                "how are you", "thank you", "thanks", "chat");
        boolean identitySignal = containsAny(lower,
                "\u4f60\u662f\u8c01", "\u4f60\u4f1a\u4ec0\u4e48", "\u4f60\u80fd\u505a\u4ec0\u4e48",
                "\u4ecb\u7ecd\u4e00\u4e0b\u4f60\u81ea\u5df1",
                "who are you", "what can you do", "introduce yourself");
        return socialSignal || identitySignal;
    }

    private boolean containsAcademicSignal(String lower) {
        return containsAny(lower,
                "\u8bba\u6587", "\u6587\u732e", "\u4f9d\u636e", "\u8bc1\u636e",
                "\u6838\u5b9e", "\u9a8c\u8bc1", "\u68c0\u7d22", "\u5f15\u7528",
                "\u7efc\u8ff0", "\u6df1\u5ea6\u7814\u7a76", "\u5bf9\u6bd4", "\u6bd4\u8f83",
                "\u7814\u7a76", "\u63a8\u8350",
                "next-poi", "gnn", "transformer", "rag",
                "claim", "evidence", "citation", "related work",
                "deep research", "paper", "literature");
    }

    /**
     * 判断文本中是否包含任意一个未被否定的关键词。
     *
     * <p>否定检测：如果关键词前面紧邻否定词（如"不""不要""不需要""别""无需"等），
     * 则该关键词被视为否定用法（如"不需要深度研究"中的"深度研究"不算命中）。
     * 只有当所有匹配到的关键词都被否定时，才返回 false。
     *
     * @param text     待检测的文本（应已转为小写）
     * @param keywords 要匹配的关键词列表
     * @return 存在未被否定的关键词返回 true；全部被否定或未匹配返回 false
     */
    private boolean containsNonNegatedAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String lower = keyword.toLowerCase(Locale.ROOT);
            int idx = text.indexOf(lower);
            if (idx < 0) {
                continue;
            }
            // 找到关键词，检查前后是否紧邻否定词
            if (!isNegated(text, idx, lower)) {
                return true; // 存在一个未被否定的匹配，直接返回 true
            }
        }
        return false;
    }

    /**
     * 检查位于 {@code position} 的关键词是否被否定词修饰。
     *
     * <p>同时检查关键词前面和后面：
     * <ul>
     *   <li>前缀否定："不需要深度研究""不要深度研究""别做深度研究"</li>
     *   <li>后缀否定："深度研究不需要做""深度研究不用了""deep research is not needed"</li>
     * </ul>
     *
     * @param text     全文（小写）
     * @param position 关键词在 text 中的起始位置
     * @param keyword  关键词本身（用于计算后缀检查的起始位置）
     * @return 如果关键词被否定返回 true
     */
    private boolean isNegated(String text, int position, String keyword) {
        // ---- 前缀检查：否定词在关键词前面 ----
        if (position > 0) {
            int lookback = Math.min(position, 8);
            String prefix = text.substring(position - lookback, position).stripTrailing();
            if (matchesNegation(prefix)) {
                return true;
            }
        }

        // ---- 后缀检查：否定词在关键词后面 ----
        // 例如"深度研究不需要做"、"深度研究不用了"
        int afterKeyword = position + keyword.length();
        if (afterKeyword < text.length()) {
            // 取关键词后面最多 10 个字符（覆盖"也不需要做"等模式）
            int lookahead = Math.min(text.length() - afterKeyword, 10);
            String suffix = text.substring(afterKeyword, afterKeyword + lookahead).stripLeading();
            if (matchesNegation(suffix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查文本片段是否以否定词开头或结尾。
     *
     * @param text 待检查的文本片段
     * @return 包含否定词返回 true
     */
    private boolean matchesNegation(String text) {
        // 中文否定词（从长到短匹配）
        String[] negations = {"不需要", "不用", "不要", "没有", "无需", "别", "非", "不", "没", "未", "没必要"};
        for (String neg : negations) {
            if (text.startsWith(neg) || text.endsWith(neg)) {
                return true;
            }
        }
        // 英文否定词
        String[] enNegations = {"don't", "doesn't", "didn't", "without", "is not", "not", "no"};
        for (String neg : enNegations) {
            if (text.startsWith(neg) || text.endsWith(neg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 引擎名称标准化 —— 将用户输入的各种引擎别名统一为三种标准值之一。
     *
     * <p>支持的别名映射：
     * <ul>
     *   <li>workflow: "workflow" / "claim" / "claim_verification"</li>
     *   <li>react: "react" / "agent" / "reflect" / "reflective_agent" / "normal_agent"</li>
     *   <li>rag: "rag" / "retrieval"</li>
     *   <li>其他任何值（包括 null/空）: "auto"（表示由路由自动决定）</li>
     * </ul>
     *
     * @param engine 用户传入的引擎名称（可能为 null、空字符串、或各种别名）
     * @return 标准化后的引擎名称："workflow" / "react" / "rag" / "auto"
     */
    private String normalizeEngine(String engine) {
        // null 或空值统一为 "auto"，表示由路由自动决定
        if (engine == null || engine.isBlank()) {
            return "auto";
        }
        // 去除首尾空格并转小写后，通过 switch 映射到标准值
        // 不匹配任何已知别名时返回 "auto"，而非抛异常（容错设计）
        return switch (engine.trim().toLowerCase(Locale.ROOT)) {
            case "workflow", "claim", "claim_verification" -> "workflow";
            case "react", "agent", "reflect", "reflective_agent", "normal_agent" -> "react";
            case "rag", "retrieval" -> "rag";
            default -> "auto";
        };
    }

    /**
     * 配置档名称标准化 —— 处理 null 和空值，去除首尾空格。
     * 与 {@link #normalizeEngine(String)} 不同，profile 不做别名映射，
     * 因为 profile 名称由系统内部定义，不存在用户输入别名的问题。
     *
     * @param profile 用户传入的配置档名称（可能为 null 或空字符串）
     * @return 标准化后的配置档名称；null/空值返回 "auto"
     */
    private String normalizeProfile(String profile) {
        return profile == null || profile.isBlank() ? "auto" : profile.trim();
    }

    /**
     * 根据引擎和任务类型推断默认的配置档（profile）。
     *
     * <p>映射逻辑：
     * <ul>
     *   <li>react → "deep_research_react"</li>
     *   <li>workflow → "auto"（Workflow 节点不是 ReAct Agent，不需要 profile）</li>
     *   <li>rag → "auto"</li>
     * </ul>
     *
     * @param engine   引擎名称（已标准化）
     * @param taskType 任务类型（如 "paper_qa"、"claim_verification"）
     * @return 推断出的默认 profile 名称
     */
    private String defaultProfile(String engine, String taskType) {
        // rag/workflow 引擎不需要 ReAct profile
        if ("rag".equals(engine) || "workflow".equals(engine)) {
            return "auto";
        }

        // The public architecture exposes one parent Agent for all open-ended
        // research work. Narrow external search is entered only by handoff.
        if ("react".equals(engine)) {
            return ExecutionCatalogService.DEEP_RESEARCH_PROFILE;
        }

        return "auto";
    }

    /**
     * 安全获取 reason 字符串 —— 将 null 转为空字符串，避免拼接时出现 "null" 字样。
     *
     * @param reason 原始 reason 文本（可能为 null）
     * @return 非 null 的 reason 字符串
     */
    private String safeReason(String reason) {
        return reason == null ? "" : reason;
    }

}
