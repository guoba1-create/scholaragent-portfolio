package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.cache.LlmResponseCacheService;
import com.team.rag.entity.CragResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CRAG 相关性判断服务
 *
 * <p>
 * Corrective RAG 的核心组件，用于判断检索到的内容是否与用户问题相关。
 * 根据判断结果决定后续处理流程：
 * <ul>
 * <li>SUFFICIENT: 正常走 RAG 生成流程</li>
 * <li>PARTIAL: 走 RAG 生成，但标注信息可能不完整</li>
 * <li>IRRELEVANT: 不走 RAG 生成，直接返回超出知识范围提示</li>
 * </ul>
 */
/**
 * CRAG 相关性判断服务。
 *
 * <p>职责：判断检索到的内容是否与用户问题相关，
 * 是 Corrective RAG（纠错式检索增强生成）的核心组件。</p>
 *
 * <h3>设计思想：</h3>
 * <p>传统 RAG 的问题是：如果检索到的内容与用户问题无关，
 * LLM 可能会基于这些无关内容生成错误的回答（幻觉）。
 * CRAG 通过增加一个"相关性判断"层来解决这个问题：</p>
 * <ul>
 *   <li>SUFFICIENT：检索内容足够回答问题 → 正常生成</li>
 *   <li>PARTIAL：检索内容部分相关 → 带前缀生成（标注信息可能不完整）</li>
 *   <li>IRRELEVANT：检索内容无关 → 返回"超出知识范围"提示</li>
 * </ul>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * LiteratureDialogueService（文献对话服务）
 *     └── CragJudgeService（本类：相关性判断）
 *         └── CragJudgeAssistant（LLM 判断代理）
 * </pre>
 */
@Service
public class CragJudgeService {

    private static final Logger log = LoggerFactory.getLogger(CragJudgeService.class);

    /** 提示词模板名称：CRAG 判断 */
    private static final String TEMPLATE_NAME = "crag-judge";

    /** 提示词模板名称：CRAG 响应 */
    private static final String CRAG_RESPONSES_NAME = "crag-responses";

    /** 聊天模型（用于相关性判断） */
    private final ChatLanguageModel chatModel;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** CRAG 判断助手（LangChain4j 动态代理） */
    private final CragJudgeAssistant judgeAssistant;

    /** 提示词模板服务 */
    private final PromptTemplateService promptTemplateService;

    /** LLM 响应缓存服务 */
    private final LlmResponseCacheService cacheService;

    public CragJudgeService(ChatLanguageModel chatModel,
                            ObjectMapper objectMapper,
                            PromptTemplateService promptTemplateService,
                            LlmResponseCacheService cacheService) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.promptTemplateService = promptTemplateService;
        this.cacheService = cacheService;
        // 使用 AiServices 动态构建 CragJudgeAssistant 代理
        this.judgeAssistant = AiServices.builder(CragJudgeAssistant.class)
                .chatLanguageModel(chatModel)
                .build();
    }

    /**
     * 判断检索内容的相关性（Layer 4: CRAG 判断）。
     *
     * <p>判断流程：</p>
     * <ol>
     *   <li>检查缓存（避免重复调用 LLM）</li>
     *   <li>调用 CragJudgeAssistant 进行判断</li>
     *   <li>解析 LLM 返回的 JSON 结果</li>
     *   <li>异常时默认返回 PARTIAL（安全降级）</li>
     * </ol>
     *
     * @param query 用户问题
     * @param retrievedContent 检索到的内容片段
     * @return CragResult 判断结果（SUFFICIENT/PARTIAL/IRRELEVANT）
     */
    public CragResult judge(String query, String retrievedContent) {
        log.info("开始 CRAG 相关性判断 | query长度={}, content长度={}", query.length(), retrievedContent.length());

        try {
            // 缓存 Key：query + content 前 200 字符（避免缓存 Key 过长）
            String cacheKey = query + "||" + retrievedContent.substring(0, Math.min(retrievedContent.length(), 200));
            String response = cacheService.getOrCompute("crag", cacheKey,
                    () -> judgeAssistant.judge(query, retrievedContent));
            log.debug("CRAG 判断原始响应: {}", response);

            // 解析 LLM 返回的 JSON 结果
            CragResult result = parseJudgment(response);
            log.info("CRAG 判断完成 | judgment={}, reason={}", result.getJudgment(), result.getReason());

            return result;
        } catch (Exception e) {
            // 异常时默认返回 PARTIAL（安全降级，不会阻断对话流程）
            log.error("CRAG 判断异常，默认返回 PARTIAL | error={}", e.getMessage());
            return new CragResult(CragResult.PARTIAL, "判断过程异常，默认返回部分相关");
        }
    }

    /**
     * 解析 LLM 返回的判断结果。
     *
     * <p>解析策略：</p>
     * <ol>
     *   <li>提取 JSON 内容（去除 Markdown 代码块标记）</li>
     *   <li>JSON 反序列化</li>
     *   <li>校验 judgment 字段的合法性</li>
     *   <li>JSON 解析失败时，从文本中提取关键词（降级方案）</li>
     * </ol>
     *
     * @param response LLM 返回的原始响应
     * @return CragResult 解析后的判断结果
     */
    private CragResult parseJudgment(String response) {
        if (response == null || response.isBlank()) {
            log.warn("LLM 返回空响应，默认为 PARTIAL");
            return new CragResult(CragResult.PARTIAL, "LLM 返回空响应");
        }

        // 提取 JSON 内容（去除 Markdown 代码块标记）
        String jsonContent = extractJsonContent(response);

        try {
            // JSON 反序列化
            CragResult result = objectMapper.readValue(jsonContent, CragResult.class);
            if (result.getJudgment() == null) {
                log.warn("JSON 解析成功但 judgment 字段为空，默认为 PARTIAL");
                return new CragResult(CragResult.PARTIAL, "judgment 字段为空");
            }

            // 校验 judgment 字段的合法性
            String judgment = result.getJudgment().toUpperCase();
            if (!judgment.equals(CragResult.SUFFICIENT) &&
                !judgment.equals(CragResult.PARTIAL) &&
                !judgment.equals(CragResult.IRRELEVANT)) {
                log.warn("无法识别的 judgment 类型: {}, 默认为 PARTIAL", judgment);
                return new CragResult(CragResult.PARTIAL, result.getReason());
            }

            result.setJudgment(judgment);
            return result;
        } catch (Exception e) {
            // JSON 解析失败，从文本中提取关键词（降级方案）
            log.warn("JSON 解析失败，尝试从文本中提取 | error={}", e.getMessage());
            return extractFromText(response);
        }
    }

    /**
     * 从响应中提取 JSON 内容。
     *
     * <p>处理 LLM 返回的各种格式：</p>
     * <ul>
     *   <li>```json ... ``` （Markdown 代码块）</li>
     *   <li>``` ... ``` （无语言标记的代码块）</li>
     *   <li>{ ... } （纯 JSON）</li>
     * </ul>
     */
    private String extractJsonContent(String response) {
        String trimmed = response.trim();

        // 处理 ```json ... ``` 格式
        if (trimmed.startsWith("```json")) {
            int startIndex = 7;
            int endIndex = trimmed.lastIndexOf("```");
            if (endIndex > startIndex) {
                return trimmed.substring(startIndex, endIndex).trim();
            }
        }

        // 处理 ``` ... ``` 格式（无语言标记）
        if (trimmed.startsWith("```")) {
            int startIndex = 3;
            int endIndex = trimmed.lastIndexOf("```");
            if (endIndex > startIndex) {
                return trimmed.substring(startIndex, endIndex).trim();
            }
        }

        // 处理纯 JSON 格式
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }

        return trimmed;
    }

    /**
     * 从文本中提取判断结果（降级方案）。
     *
     * <p>当 JSON 解析失败时，通过关键词匹配提取判断结果。
     * 这是一种"尽力而为"的策略，确保即使 LLM 返回格式不规范，
     * 也能得到一个可用的判断结果。</p>
     */
    private CragResult extractFromText(String response) {
        String upper = response.toUpperCase();

        if (upper.contains("SUFFICIENT")) {
            return new CragResult(CragResult.SUFFICIENT, "从文本中提取");
        } else if (upper.contains("IRRELEVANT")) {
            return new CragResult(CragResult.IRRELEVANT, "从文本中提取");
        } else if (upper.contains("PARTIAL")) {
            return new CragResult(CragResult.PARTIAL, "从文本中提取");
        }

        log.warn("无法从文本中提取判断结果，默认为 PARTIAL");
        return new CragResult(CragResult.PARTIAL, "无法识别，默认返回");
    }

    /**
     * 获取无关内容的回复（从配置模板中读取）。
     */
    public String getIrrelevantResponse() {
        return promptTemplateService.getResponse(CRAG_RESPONSES_NAME, "irrelevant");
    }

    /**
     * 获取部分相关的回复前缀（从配置模板中读取）。
     */
    public String getPartialPrefix() {
        return promptTemplateService.getResponse(CRAG_RESPONSES_NAME, "partial-prefix");
    }
}
