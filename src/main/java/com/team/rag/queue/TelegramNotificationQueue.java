package com.team.rag.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.telegram.TelegramDeliveryStore;
import com.team.rag.telegram.TelegramWorkflowNotifier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telegram 通知队列服务
 *
 * <p>
 * 负责将 Agent 工作流完成后的 Telegram 通知任务异步投递到队列中，
 * 支持 Kafka 和本地线程池两种后端，具备自动降级能力。
 *
 * <p>
 * 工作流程：
 * <ol>
 * <li>调用 {@link #enqueue(long, String)} 投递通知任务</li>
 * <li>优先尝试发送到 Kafka（如果启用）</li>
 * <li>Kafka 不可用时自动降级到本地线程池</li>
 * <li>任务执行时调用 {@link TelegramWorkflowNotifier#notifyWhenCompleteSync} 发送通知</li>
 * </ol>
 *
 * <p>
 * 消息追踪：通过 {@link TelegramDeliveryStore} 记录每个任务的入队、出队状态，
 * 便于排查通知丢失或延迟问题。
 */
@Service
public class TelegramNotificationQueue {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationQueue.class);

    /** 队列配置属性 */
    private final AgentQueueProperties properties;

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** Kafka 模板提供者（延迟获取，Kafka 未启用时为 null） */
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;

    /** Telegram 通知执行器 */
    private final TelegramWorkflowNotifier notifier;

    /** 通知投递状态存储（用于消息追踪） */
    private final TelegramDeliveryStore deliveryStore;

    /** 本地队列的线程池执行器 */
    private final ExecutorService localExecutor;

    /**
     * 构造函数，注入所有依赖
     *
     * @param properties            队列配置属性
     * @param objectMapper          JSON 序列化工具
     * @param kafkaTemplateProvider Kafka 模板提供者（可选依赖）
     * @param notifier              Telegram 通知执行器
     * @param deliveryStore         投递状态存储
     */
    public TelegramNotificationQueue(
            AgentQueueProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
            TelegramWorkflowNotifier notifier,
            TelegramDeliveryStore deliveryStore) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.notifier = notifier;
        this.deliveryStore = deliveryStore;
        // 初始化本地线程池：固定线程数、守护线程、自定义线程名
        this.localExecutor = Executors.newFixedThreadPool(properties.localThreads(), runnable -> {
            Thread thread = new Thread(runnable, "telegram-notify-local-queue");
            thread.setDaemon(true); // 守护线程，JVM 退出时自动终止
            return thread;
        });
    }

    /**
     * 将 Telegram 通知任务入队
     *
     * <p>
     * 投递策略：
     * <ol>
     * <li>如果 Kafka 启用且可用，发送到 Kafka 主题（同步等待确认）</li>
     * <li>Kafka 发送失败时，自动降级到本地线程池</li>
     * <li>Kafka 未启用时，直接使用本地线程池</li>
     * </ol>
     *
     * @param chatId     Telegram 聊天 ID（目标用户/群组）
     * @param workflowId 工作流 ID（用于关联通知与工作流）
     * @return 包含投递结果的 Map，包含 queued、mode、taskId 等信息
     */
    public Map<String, Object> enqueue(long chatId, String workflowId) {
        // 创建通知任务实例
        TelegramNotifyTask task = new TelegramNotifyTask(chatId, workflowId, "telegram-poller");

        // 优先尝试 Kafka
        if (properties.kafkaEnabled()) {
            try {
                KafkaTemplate<String, String> template = kafkaTemplateProvider.getIfAvailable();
                if (template != null) {
                    // 序列化任务为 JSON 并发送到 Kafka
                    String payload = objectMapper.writeValueAsString(task);
                    template.send(properties.kafkaTopic(), workflowId, payload).get(); // 同步等待发送确认
                    deliveryStore.markQueued(workflowId, "kafka", task.taskId, properties.kafkaTopic());
                    log.info("Telegram notify task queued in Kafka | workflowId={} | taskId={} | topic={}",
                            workflowId, task.taskId, properties.kafkaTopic());
                    return Map.of("queued", true, "mode", "kafka", "taskId", task.taskId,
                            "topic", properties.kafkaTopic());
                }
            } catch (Exception e) {
                // Kafka 发送失败，记录降级信息
                log.warn("Kafka enqueue failed, falling back to local queue | workflowId={} | error={}",
                        workflowId, e.getMessage());
                deliveryStore.markQueueFallback(workflowId, "kafka", e.getMessage());
            }
        }

        // 降级到本地队列
        enqueueLocal(task, "local");
        return Map.of("queued", true, "mode", "local", "taskId", task.taskId);
    }

    /**
     * Kafka 消费者监听方法
     *
     * <p>
     * 从 Kafka 主题消费 Telegram 通知任务，反序列化后调用通知执行器。
     * 监听的主题、消费者组 ID 和是否启用均由配置文件控制。
     *
     * @param payload Kafka 消息体（JSON 格式的 TelegramNotifyTask）
     */
    @KafkaListener(
            topics = "${agent.queue.kafka.topic:agent.telegram.notify}",
            groupId = "${agent.queue.kafka.group-id:agent-pipeline-local}",
            autoStartup = "${agent.queue.kafka.enabled:false}")
    public void consumeKafka(String payload) {
        try {
            // 反序列化任务
            TelegramNotifyTask task = objectMapper.readValue(payload, TelegramNotifyTask.class);
            // 记录出队时间
            deliveryStore.markDequeued(task.workflowId, "kafka", task.taskId, Instant.now());
            // 执行通知（同步等待完成）
            notifier.notifyWhenCompleteSync(task.chatId, task.workflowId);
        } catch (Exception e) {
            log.warn("Kafka telegram notify task failed | error={}", e.getMessage());
        }
    }

    /**
     * 获取队列当前状态信息
     *
     * @return 包含后端类型、Kafka 配置、本地线程数等信息的 Map
     */
    public Map<String, Object> status() {
        return Map.of(
                "backend", properties.backend(),
                "kafkaEnabled", properties.kafkaEnabled(),
                "kafkaTopic", properties.kafkaTopic(),
                "localThreads", properties.localThreads());
    }

    /**
     * 应用关闭时清理本地线程池资源
     *
     * <p>
     * 使用 @PreDestroy 注解，在 Spring 容器销毁 Bean 之前调用，
     * 立即终止所有正在执行的线程（shutdownNow）。
     */
    @PreDestroy
    public void shutdown() {
        localExecutor.shutdownNow();
    }

    /**
     * 将任务提交到本地线程池执行
     *
     * @param task 通知任务
     * @param mode 投递模式标识（用于消息追踪）
     */
    private void enqueueLocal(TelegramNotifyTask task, String mode) {
        // 记录入队状态
        deliveryStore.markQueued(task.workflowId, mode, task.taskId, "local-executor");
        // 提交到线程池异步执行
        localExecutor.submit(() -> {
            deliveryStore.markDequeued(task.workflowId, mode, task.taskId, Instant.now());
            notifier.notifyWhenCompleteSync(task.chatId, task.workflowId);
        });
    }
}
