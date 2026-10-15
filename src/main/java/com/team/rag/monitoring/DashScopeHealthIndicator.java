package com.team.rag.monitoring;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * DashScope（通义千问）LLM 健康检查指示器
 *
 * <p>
 * 实现 Spring Boot Actuator 的 HealthIndicator 接口，
 * 通过向 DashScope 发送一个简单的 "ping" 请求来验证 LLM 服务是否可用。
 *
 * <p>
 * 检查结果会暴露在 /actuator/health 端点中，包含以下信息：
 * <ul>
 * <li>model    - 使用的模型名称（qwen-plus）</li>
 * <li>provider - 服务提供商（dashscope）</li>
 * <li>error    - 失败时的错误信息（仅在 DOWN 状态时出现）</li>
 * </ul>
 *
 * <p>
 * 可用于 Kubernetes 存活探针（livenessProbe）或就绪探针（readinessProbe），
 * 当 DashScope 服务不可用时自动触发告警或重启。
 */
@Component
public class DashScopeHealthIndicator implements HealthIndicator {

    /** LangChain4j 对话模型实例，由 Spring 自动注入 */
    private final ChatLanguageModel chatModel;

    /**
     * 构造函数，注入对话模型
     *
     * @param chatModel LangChain4j 对话模型（DashScope Qwen-Plus）
     */
    public DashScopeHealthIndicator(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行健康检查
     *
     * <p>
     * 向 DashScope 发送 "ping" 文本，如果模型能正常返回则标记为 UP，
     * 异常时标记为 DOWN 并附带错误详情。
     *
     * @return Health 健康状态（UP 或 DOWN）
     */
    @Override
    public Health health() {
        try {
            // 发送简单的 ping 请求验证 LLM 连通性
            chatModel.generate("ping");
            return Health.up()
                    .withDetail("model", "qwen-plus")       // 模型名称
                    .withDetail("provider", "dashscope")     // 服务提供商
                    .build();
        } catch (Exception e) {
            // 捕获所有异常（网络超时、鉴权失败、限流等），标记为 DOWN
            return Health.down(e)
                    .withDetail("model", "qwen-plus")
                    .withDetail("provider", "dashscope")
                    .withDetail("error", e.getMessage())     // 错误信息
                    .build();
        }
    }
}
