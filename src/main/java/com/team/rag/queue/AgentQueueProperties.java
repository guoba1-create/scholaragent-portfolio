package com.team.rag.queue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Delivery backend for Telegram notification tasks. Document ingestion owns
 * a separate queue configuration; workflow execution is transported through
 * the gRPC runtime contract.
 */
@Component
public class AgentQueueProperties {

    /**
     * 队列后端类型
     * 可选值："local"（本地线程池）或 "kafka"（Kafka 消息队列）
     * 配置项：agent.queue.backend，默认值 "local"
     */
    @Value("${agent.queue.backend:local}")
    private String backend;

    /**
     * 是否显式启用 Kafka
     * 也可以通过将 backend 设为 "kafka" 隐式启用
     * 配置项：agent.queue.kafka.enabled，默认值 false
     */
    @Value("${agent.queue.kafka.enabled:false}")
    private boolean kafkaEnabled;

    /**
     * Kafka 主题名称
     * Telegram 通知任务将被发送到此主题
     * 配置项：agent.queue.kafka.topic，默认值 "agent.telegram.notify"
     */
    @Value("${agent.queue.kafka.topic:agent.telegram.notify}")
    private String kafkaTopic;

    /**
     * 本地队列线程池大小
     * 控制本地模式下并发处理通知任务的线程数，会被限制在 1-8 之间
     * 配置项：agent.queue.local.threads，默认值 1
     */
    @Value("${agent.queue.local.threads:1}")
    private int localThreads;

    /**
     * 获取队列后端类型（经过归一化处理）
     *
     * <p>
     * 处理逻辑：null 或空白字符串回退到 "local"，否则转小写并去除首尾空格。
     *
     * @return 后端类型字符串："local" 或 "kafka"
     */
    public String backend() {
        return backend == null || backend.isBlank() ? "local" : backend.trim().toLowerCase();
    }

    /**
     * 判断 Kafka 是否启用
     *
     * <p>
     * 两种情况会启用 Kafka：
     * <ol>
     * <li>kafkaEnabled 配置项为 true</li>
     * <li>backend 配置项为 "kafka"</li>
     * </ol>
     *
     * @return true 表示使用 Kafka，false 表示使用本地队列
     */
    public boolean kafkaEnabled() {
        return kafkaEnabled || "kafka".equals(backend());
    }

    /**
     * 获取 Kafka 主题名称（经过归一化处理）
     *
     * @return Kafka 主题名，默认 "agent.telegram.notify"
     */
    public String kafkaTopic() {
        return kafkaTopic == null || kafkaTopic.isBlank() ? "agent.telegram.notify" : kafkaTopic.trim();
    }

    /**
     * 获取本地线程池线程数（经过边界限制）
     *
     * <p>
     * 线程数会被强制限制在 [1, 8] 范围内，避免资源浪费或过度消耗。
     *
     * @return 线程数，范围 [1, 8]
     */
    public int localThreads() {
        return Math.max(1, Math.min(localThreads, 8)); // 限制在 1-8 之间
    }
}
