package com.team.rag.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * CRAG 相关性判断助手接口。
 *
 * <p>职责：通过 LangChain4j 的 AiServices 机制，将此接口与 LLM "缝合"在一起，
 * 实现检索内容的相关性判断。</p>
 *
 * <h3>设计思想：</h3>
 * <p>这是 Corrective RAG（纠错式检索增强生成）的核心组件。
 * 传统 RAG 的问题是：如果检索到的内容与用户问题无关，
 * LLM 可能会基于这些无关内容生成错误的回答（幻觉）。
 * CRAG 通过增加一个"相关性判断"层来解决这个问题。</p>
 *
 * <h3>三种判断结果：</h3>
 * <ul>
 *   <li>SUFFICIENT：检索内容中包含足够的信息来回答问题</li>
 *   <li>PARTIAL：检索内容中包含部分相关信息，但不足以完整回答</li>
 *   <li>IRRELEVANT：检索内容与用户问题基本无关</li>
 * </ul>
 *
 * <h3>注意：</h3>
 * <p>此接口的实现由 AiServices 在运行时动态代理生成，
 * 不需要手动编写实现类。具体的组装逻辑在 {@link CragJudgeService} 中完成。</p>
 */
public interface CragJudgeAssistant {

    /**
     * 判断检索内容的相关性。
     *
     * <p>@SystemMessage 定义了判断助手的角色和判断标准。
     * @UserMessage 定义了用户问题和检索内容的格式。</p>
     *
     * <p>返回格式为 JSON 字符串，包含 judgment（判断结果）和 reason（判断理由）。</p>
     *
     * @param query 用户问题
     * @param retrievedContent 检索到的内容片段
     * @return 判断结果 JSON（如 {"judgment":"SUFFICIENT","reason":"..."}）
     */
    @SystemMessage("""
            你是一个相关性判断助手。你的任务是判断检索到的内容是否能够回答用户的问题。

            【判断标准】
            1. SUFFICIENT: 检索内容中包含足够的信息来回答问题
               - 检索内容直接包含用户问题的答案
               - 检索内容提供了充分的背景信息来推导答案

            2. PARTIAL: 检索内容中包含部分相关信息，但不足以完整回答
               - 检索内容与问题有一定相关性，但缺少关键信息
               - 检索内容只能回答问题的一部分

            3. IRRELEVANT: 检索内容与用户问题基本无关
               - 检索内容与问题完全不相关
               - 检索内容无法提供任何有用的信息

            【输出格式】
            请严格按照以下 JSON 格式输出，不要包含任何 Markdown 标记：
            {
              "judgment": "SUFFICIENT" | "PARTIAL" | "IRRELEVANT",
              "reason": "简要说明判断理由"
            }
            """)
    @UserMessage("""
            用户的问题是：{{query}}

            以下是从知识库中检索到的内容片段：
            {{retrievedContent}}

            请判断：以上检索到的内容是否包含了能够回答用户问题的信息？
            """)
    String judge(@V("query") String query, @V("retrievedContent") String retrievedContent);
}
