package com.team.rag.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 受约束生成 AI 助手接口 —— 第三道防线。
 *
 * <p>职责：通过严格的 Prompt 约束，强制 LLM 基于检索结果生成回答，
 * 防止 LLM 幻觉（hallucination）。</p>
 *
 * <h3>设计思想：</h3>
 * <p>这是五层检索链的最后一层——"受约束生成"。
 * 即使经过了 CRAG 判断，LLM 仍然可能基于检索内容"发挥想象力"，
 * 生成不准确或编造的信息。因此需要通过 Prompt 约束来强制 LLM：</p>
 * <ul>
 *   <li>严禁幻觉：所有事实必须来自检索文献片段</li>
 *   <li>强制引用：陈述观点时必须标注文献来源编号</li>
 *   <li>诚实拒答：无相关信息时明确告知用户</li>
 *   <li>部分回答：只能解答部分时清晰说明</li>
 * </ul>
 *
 * <h3>注意：</h3>
 * <p>此接口的实现由 AiServices 在运行时动态代理生成，
 * 不需要手动编写实现类。具体的组装逻辑在 {@link LiteratureDialogueService} 中完成。</p>
 */
public interface ConstrainedGenerationAssistant {

    /**
     * 受约束生成方法 —— 第三道防线。
     *
     * <p>使用 GENERATE_PROMPT_TEMPLATE 进行受约束生成，
     * 将检索结果和用户问题注入 Prompt，强制 LLM 基于检索内容回答。</p>
     *
     * <p>@SystemMessage 定义了严格的学术纪律，@UserMessage 定义了检索结果和用户问题的格式。</p>
     *
     * @param query            用户原始问题
     * @param retrievedContext 格式化后的检索结果（带编号的文献片段）
     * @param profile          用户结构化画像
     * @return TokenStream 流式 Token 输出
     */
    @SystemMessage("""
            你是一个专业的学术研究助手。你的唯一任务是严格基于下方提供的【检索文献片段】来回答用户的问题。

            【核心学术纪律】
            1. 严禁幻觉：绝不能使用你自身的预训练知识库进行回答。所有事实、数据、结论和方法论必须完全来自下方的检索文献片段。
            2. 强制引用：在陈述任何观点或数据时，必须在句末标注文献来源的编号（例如："该算法的准确率达到了98% [文献1]。"）。
            3. 诚实拒答：如果检索文献中没有足够的信息来回答用户问题，请明确回复："根据当前检索到的文献库，暂无关于 [总结用户的核心问题] 的相关信息。"严禁推测。
            4. 部分回答：如果文献只能解答问题的一部分，请清晰地说明："文献中提到了 [可知部分]，但未提及 [未知部分]。"

            【用户画像信息】
            以下是当前用户的个人信息和研究偏好，请据此调整回答风格：
            {{profile}}
            """)
    @UserMessage("""
            【检索文献片段】
            {{retrievedContext}}

            用户提问：{{query}}
            """)
    TokenStream generateWithConstraints(
            @V("query") String query,
            @V("retrievedContext") String retrievedContext,
            @V("profile") String profile);
}
