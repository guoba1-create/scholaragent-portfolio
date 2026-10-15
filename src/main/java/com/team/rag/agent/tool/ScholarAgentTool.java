package com.team.rag.agent.tool;

/**
 * ScholarAgent 工具契约接口
 *
 * <p>所有 Agent 工具必须实现此接口，提供：
 * <ul>
 *   <li>标准化的输入输出</li>
 *   <li>工具描述和 Schema</li>
 *   <li>执行追踪支持</li>
 *   <li>错误处理</li>
 * </ul>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface ScholarAgentTool<I, O> {

    /**
     * 获取工具名称
     *
     * @return 工具名称（唯一标识）
     */
    String name();

    /**
     * 获取工具描述
     *
     * @return 工具描述
     */
    String description();

    /**
     * 获取输入 Schema
     *
     * @return 输入的 JSON Schema 描述
     */
    ToolSchema inputSchema();

    /**
     * 获取输出 Schema
     *
     * @return 输出的 JSON Schema 描述
     */
    ToolSchema outputSchema();

    /**
     * 执行工具
     *
     * @param input   输入参数
     * @param context 执行上下文
     * @return 工具执行结果
     */
    ToolResult<O> execute(I input, ToolContext context);

    /**
     * 获取输入类型，用于将 ReAct JSON 参数转换为强类型工具输入。
     */
    default Class<I> inputType() {
        return null;
    }

    /**
     * 获取工具类型
     *
     * @return 工具类型
     */
    default ToolType type() {
        return ToolType.CUSTOM;
    }

    /**
     * 获取工具优先级（用于工具排序）
     *
     * @return 优先级（数值越小优先级越高）
     */
    default int priority() {
        return 100;
    }

    /**
     * 判断工具是否可用
     *
     * @return true 如果工具可用
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 获取最大重试次数
     *
     * @return 最大重试次数
     */
    default int maxRetries() {
        return 0;
    }

    /**
     * 获取超时时间（毫秒）
     *
     * @return 超时时间
     */
    default long timeoutMs() {
        return 30000; // 30 seconds
    }

    /**
     * 工具类型枚举
     */
    enum ToolType {
        RETRIEVAL("检索"),
        ANALYSIS("分析"),
        GENERATION("生成"),
        MEMORY("记忆"),
        CUSTOM("自定义");

        private final String description;

        ToolType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
