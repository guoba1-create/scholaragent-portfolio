package com.team.rag.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 团队知识库 AI 助手接口 —— 声明式 AI 代理接口。
 *
 * <p>职责：通过 LangChain4j 的 AiServices 机制，将此接口与以下组件"缝合"在一起：</p>
 * <ul>
 *   <li>StreamingChatLanguageModel（如通义千问 qwen-plus 流式输出）</li>
 *   <li>ChatMemoryProvider（基于 userId 的短期记忆滑动窗口）</li>
 *   <li>ContentRetriever（团队知识库 + 个人语义记忆的混合检索器）</li>
 * </ul>
 *
 * <h3>设计思想：</h3>
 * <p>面向团队知识库场景。团队知识库中的文档是团队成员共享的，
 * 因此检索时使用 teamId 过滤，而不是 researchGroupId。</p>
 *
 * <h3>注意：</h3>
 * <p>此接口的实现由 AiServices 在运行时动态代理生成，
 * 不需要手动编写实现类。</p>
 *
 * <p>返回 {@link TokenStream} 以支持流式逐 Token 输出。</p>
 */
public interface TeamKnowledgeAssistant {

    /**
     * 与 AI 助手进行对话（流式输出）。
     *
     * <p>@SystemMessage 定义了 AI 的人设和行为规范。
     * 其中 {{profile}} 占位符将在运行时被替换为用户的结构化画像信息，
     * 使 AI 能够根据用户身份和偏好进行个性化回答。</p>
     *
     * <p>检索增强生成 (RAG) 的上下文由 ContentRetriever 自动注入，
     * LangChain4j 会将检索到的相关文档片段自动拼接到 Prompt 中，
     * 无需在此接口中手动处理。</p>
     *
     * @param userMessage 用户输入的问题或指令
     * @param profile     用户结构化画像（由 Redis 读取并格式化）
     * @return TokenStream 流式 Token 输出
     */
    @SystemMessage("你是一个专业的团队知识库 AI 助手，具备以下能力：\n"
            + "1. 基于团队知识库中的文档，为用户提供准确、专业的回答\n"
            + "2. 结合用户的个人偏好和历史记忆，提供个性化的服务\n"
            + "3. 如果知识库中没有相关信息，请诚实告知用户，不要编造答案\n"
            + "\n"
            + "【回答规范】\n"
            + "- 回答应简洁、准确、有条理\n"
            + "- 引用知识库内容时，尽量标注来源\n"
            + "- 使用用户偏好的语言和风格进行回答\n"
            + "- 对于不确定的内容，明确标注'根据现有资料'或'可能需要进一步确认'\n"
            + "\n"
            + "【用户画像信息】\n"
            + "以下是当前用户的个人信息和偏好，请据此调整回答风格：\n"
            + "{{profile}}")
    TokenStream chat(@UserMessage String userMessage, @V("profile") String profile);
}
