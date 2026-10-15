package com.team.rag.telegram;

import com.team.rag.agent.runtime.WorkflowRunService;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Telegram 工作流通知器。
 *
 * <p>负责在 RAG 工作流完成后将结果发送给 Telegram 用户。
 * 在本学术 RAG 系统中，该组件是异步通知链的最后一环：
 * <ol>
 *   <li>用户通过 Telegram 发送问题；</li>
 *   <li>系统触发 RAG 工作流处理；</li>
 *   <li>本组件轮询等待工作流完成；</li>
 *   <li>工作流完成后，将结果通过 Telegram Bot API 发送给用户。</li>
 * </ol>
 *
 * <p>通知机制：通过轮询方式检查工作流状态（每秒一次，最长等待 300 秒）。
 * 支持异步（@Async）和同步两种调用方式。
 *
 * @see TelegramBotClient 发送消息的客户端
 * @see TelegramDeliveryStore 记录投递状态
 * @see WorkflowRunService 查询工作流状态
 */
@Service
public class TelegramWorkflowNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramWorkflowNotifier.class);

    /** 最大轮询等待时间（秒），超过此时间视为工作流超时 */
    private static final int MAX_POLL_SECONDS = 300;

    /** 工作流运行服务，用于查询工作流快照 */
    private final WorkflowRunService workflowRunService;

    /** Telegram Bot API 客户端，用于发送消息 */
    private final TelegramBotClient botClient;

    /** 投递记录存储，用于更新投递状态 */
    private final TelegramDeliveryStore deliveryStore;

    /**
     * 构造函数，注入依赖。
     *
     * @param workflowRunService 工作流运行服务
     * @param botClient          Telegram Bot 客户端
     * @param deliveryStore      投递记录存储
     */
    public TelegramWorkflowNotifier(
            WorkflowRunService workflowRunService,
            TelegramBotClient botClient,
            TelegramDeliveryStore deliveryStore) {
        this.workflowRunService = workflowRunService;
        this.botClient = botClient;
        this.deliveryStore = deliveryStore;
    }

    /**
     * 异步等待工作流完成后发送通知。
     * <p>使用 Spring @Async 注解在独立线程中执行，不阻塞调用方。
     *
     * @param chatId     Telegram 聊天 ID
     * @param workflowId 工作流 ID
     */
    @Async
    public void notifyWhenComplete(long chatId, String workflowId) {
        notifyWhenCompleteSync(chatId, workflowId);
    }

    /**
     * 同步等待工作流完成后发送通知。
     *
     * <p>每秒检查一次工作流状态，最长等待 {@link #MAX_POLL_SECONDS} 秒。
     * 工作流完成或失败后立即发送结果；超时则发送超时错误信息。
     *
     * @param chatId     Telegram 聊天 ID
     * @param workflowId 工作流 ID
     */
    public void notifyWhenCompleteSync(long chatId, String workflowId) {
        try {
            // 轮询等待工作流完成
            for (int i = 0; i < MAX_POLL_SECONDS; i++) {
                WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
                if ("COMPLETED".equalsIgnoreCase(snapshot.status) || "FAILED".equalsIgnoreCase(snapshot.status)) {
                    sendAndRecord(chatId, snapshot);
                    return;
                }
                Thread.sleep(1000);
            }

            // 超时：标记为失败并发送通知
            WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
            snapshot.status = "FAILED";
            snapshot.errorMessage = "Workflow did not finish within the Telegram notification window.";
            sendAndRecord(chatId, snapshot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Telegram workflow notifier interrupted | workflowId={}", workflowId);
        } catch (Exception e) {
            log.warn("Telegram workflow notifier failed | workflowId={}, error={}",
                    workflowId, e.getMessage());
        }
    }

    /**
     * 发送工作流结果并更新投递记录。
     *
     * <p>执行以下步骤：
     * <ol>
     *   <li>标记工作流已完成，记录答案预览；</li>
     *   <li>通过 Telegram Bot API 发送结果消息；</li>
     *   <li>根据发送结果更新投递记录状态（成功/失败）。</li>
     * </ol>
     *
     * @param chatId   Telegram 聊天 ID
     * @param snapshot 工作流运行快照
     */
    private void sendAndRecord(long chatId, WorkflowRunSnapshot snapshot) {
        // 标记工作流完成，更新投递记录状态为 SENDING
        deliveryStore.markWorkflowComplete(snapshot.workflowId, snapshot);
        String link = botClient.publicWorkflowLink(snapshot.workflowId);
        try {
            // 发送工作流结果到 Telegram
            TelegramSendResult result = botClient.sendWorkflowResult(chatId, snapshot);
            if (result.success) {
                deliveryStore.markSent(snapshot.workflowId, result, link);
            } else {
                deliveryStore.markFailed(snapshot.workflowId, result, link);
            }
        } catch (Exception e) {
            // 发送异常，记录失败
            TelegramSendResult result = TelegramSendResult.failure("SEND_EXCEPTION", e.getMessage());
            deliveryStore.markFailed(snapshot.workflowId, result, link);
            log.warn("Telegram delivery failed | workflowId={} | error={}",
                    snapshot.workflowId, e.getMessage());
        }
    }
}
