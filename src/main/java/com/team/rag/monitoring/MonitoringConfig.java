package com.team.rag.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 监控指标配置类
 *
 * <p>
 * 使用 Micrometer 注册自定义监控指标，用于采集系统运行时的关键性能数据。
 * 所有指标统一标记 application=scholar-agent，便于在 Prometheus/Grafana 中筛选。
 *
 * <p>
 * 注册的指标包括：
 * <ul>
 * <li>llm.request.duration - LLM API 调用耗时（Timer）</li>
 * <li>llm.request.error    - LLM API 调用错误计数（Counter）</li>
 * <li>rag.crag.judgment    - CRAG 判断结果分布（Counter）</li>
 * <li>workflow.duration    - 工作流执行耗时（Timer）</li>
 * </ul>
 */
@Configuration
public class MonitoringConfig {

    /**
     * LLM API 调用耗时计时器
     *
     * <p>
     * 记录每次 LLM API 调用的执行时间，可用于监控接口响应速度、
     * 检测超时异常以及分析 P50/P95/P99 延迟分布。
     *
     * @param registry Micrometer 指标注册中心（自动注入）
     * @return Timer 计时器实例
     */
    @Bean
    public Timer llmRequestDuration(MeterRegistry registry) {
        return Timer.builder("llm.request.duration")
                .description("LLM API call duration")               // 指标描述
                .tag("application", "scholar-agent")                // 应用标签，用于筛选
                .register(registry);
    }

    /**
     * LLM API 调用错误计数器
     *
     * <p>
     * 累计 LLM API 调用失败次数，包括网络超时、限流（429）、
     * 服务端错误（5xx）等异常情况。可配合告警规则及时发现问题。
     *
     * @param registry Micrometer 指标注册中心（自动注入）
     * @return Counter 计数器实例
     */
    @Bean
    public Counter llmRequestError(MeterRegistry registry) {
        return Counter.builder("llm.request.error")
                .description("LLM API call error count")            // 指标描述
                .tag("application", "scholar-agent")                // 应用标签
                .register(registry);
    }

    /**
     * CRAG（Corrective RAG）判断结果分布计数器
     *
     * <p>
     * 统计 CRAG 模块对检索结果的判断分布（如 CORRECT / AMBIGUOUS / INCORRECT），
     * 用于分析检索质量和决定是否触发网络搜索补充。
     *
     * @param registry Micrometer 指标注册中心（自动注入）
     * @return Counter 计数器实例
     */
    @Bean
    public Counter cragJudgmentCounter(MeterRegistry registry) {
        return Counter.builder("rag.crag.judgment")
                .description("CRAG judgment result distribution")   // 指标描述
                .tag("application", "scholar-agent")                // 应用标签
                .register(registry);
    }

    /**
     * 工作流整体执行耗时计时器
     *
     * <p>
     * 记录从用户提问到最终回答返回的端到端耗时，
     * 包括查询重写、检索、Rerank、LLM 生成等全部阶段。
     *
     * @param registry Micrometer 指标注册中心（自动注入）
     * @return Timer 计时器实例
     */
    @Bean
    public Timer workflowDuration(MeterRegistry registry) {
        return Timer.builder("workflow.duration")
                .description("Workflow execution duration")         // 指标描述
                .tag("application", "scholar-agent")                // 应用标签
                .register(registry);
    }
}
