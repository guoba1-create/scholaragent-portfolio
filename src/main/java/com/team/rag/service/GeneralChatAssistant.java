package com.team.rag.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 通用对话 AI 助手接口 —— 用于处理非知识库检索的通用请求。
 *
 * <p>职责：当意图识别判断用户请求为 GENERAL_CHAT 时，使用此助手进行回复。
 * 适用于翻译、代码编写、日常问候、通用数学计算、常识解释等场景。</p>
 *
 * <h3>设计思想：</h3>
 * <p>学术 RAG 系统的用户不仅会问学术问题，也会问一些通用问题（如翻译、代码）。
 * 如果每次都走 RAG 流程（检索+判断+生成），会浪费资源且增加延迟。
 * 因此通过意图识别将通用对话分流到此助手，直接调用 LLM，不检索知识库。</p>
 *
 * <h3>适用场景：</h3>
 * <ul>
 *   <li>语言翻译："帮我把这段摘要翻译成英文"</li>
 *   <li>代码编写："写一段Python的冒泡排序"</li>
 *   <li>日常问候："你好，你今天怎么样？"</li>
 *   <li>通用计算："计算 123 * 456"</li>
 *   <li>常识解释："解释一下牛顿第一定律"</li>
 * </ul>
 *
 * <h3>注意：</h3>
 * <p>此接口的实现由 AiServices 在运行时动态代理生成，
 * 不需要手动编写实现类。具体的组装逻辑在 {@link LiteratureDialogueService} 中完成。</p>
 */
public interface GeneralChatAssistant {

    /**
     * 通用对话方法。
     *
     * <p>直接使用大模型进行对话，不检索知识库。
     * 适用于不需要特定领域知识的通用请求。</p>
     *
     * <p>@SystemMessage 定义了 AI 的角色和回答原则。
     * 其中"学术导向"原则会建议用户在需要时切换到学术问答模式。</p>
     *
     * @param query 用户问题
     * @return TokenStream 流式 Token 输出
     */
    @SystemMessage("""
            你是一个友好、专业的 AI 助手，服务于科研团队。

            【服务定位】
            你当前处于"通用对话模式"，主要处理以下类型的请求：
            - 语言翻译（中英互译等）
            - 代码编写与解释
            - 日常问候与闲聊
            - 通用数学计算
            - 常识性问题解答

            【回答原则】
            1. 友好专业：保持礼貌、专业的态度
            2. 简洁明了：直接回答问题，避免冗余
            3. 诚实可靠：不确定的内容明确说明
            4. 学术导向：如果用户的问题更适合查询知识库，建议用户切换到学术问答模式
            """)
    @UserMessage("{{query}}")
    TokenStream chat(@V("query") String query);
}
