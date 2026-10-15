package com.team.rag.wechat;

import com.team.rag.agent.runtime.WorkflowRunService;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 微信工作流通知器。
 *
 * <p>负责在 RAG 工作流完成后将结果发送给微信用户。
 * 在本学术 RAG 系统中，该组件是异步通知链的最后一环：
 * <ol>
 *   <li>用户通过微信发送问题；</li>
 *   <li>系统触发 RAG 工作流处理；</li>
 *   <li>本组件轮询等待工作流完成；</li>
 *   <li>工作流完成后，通过微信客服消息接口将结果发送给用户。</li>
 * </ol>
 *
 * <p>通知机制：通过轮询方式检查工作流状态（每秒一次，最长等待 300 秒）。
 * 使用 Spring @Async 注解在独立线程中执行，不阻塞调用方。
 *
 * @see WeChatReplyService 微信回复服务（负责发送消息）
 * @see WeChatSessionStore 会话存储（记录投递状态）
 * @see WorkflowRunService 查询工作流状态
 */
@Service
public class WeChatWorkflowNotifier {

    private static final Logger log = LoggerFactory.getLogger(WeChatWorkflowNotifier.class);

    /** 最大轮询等待时间（秒），超过此时间视为工作流超时 */
    private static final int MAX_POLL_SECONDS = 300;

    /** 工作流运行服务，用于查询工作流快照 */
    private final WorkflowRunService workflowRunService;

    /** 微信回复服务，用于发送客服消息 */
    private final WeChatReplyService replyService;

    /** 微信会话存储，用于更新投递状态 */
    private final WeChatSessionStore sessionStore;

    /**
     * 构造函数，注入依赖。
     *
     * @param workflowRunService 工作流运行服务
     * @param replyService       微信回复服务
     * @param sessionStore       微信会话存储
     */
    public WeChatWorkflowNotifier(
            WorkflowRunService workflowRunService,
            WeChatReplyService replyService,
            WeChatSessionStore sessionStore) {
        this.workflowRunService = workflowRunService;
        this.replyService = replyService;
        this.sessionStore = sessionStore;
    }

    /**
     * 异步等待工作流完成后发送通知。
     *
     * <p>每秒检查一次工作流状态，最长等待 {@link #MAX_POLL_SECONDS} 秒。
     * 工作流完成或失败后立即发送结果；超时则发送超时错误信息。
     * 使用 @Async 注解在独立线程中执行。
     *
     * @param openId     微信用户的 OpenID
     * @param workflowId 工作流 ID
     */
    @Async
    public void notifyWhenComplete(String openId, String workflowId) {
        try {
            // 轮询等待工作流完成
            for (int i = 0; i < MAX_POLL_SECONDS; i++) {
                WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
                if ("COMPLETED".equalsIgnoreCase(snapshot.status) || "FAILED".equalsIgnoreCase(snapshot.status)) {
                    sendAndRecord(openId, snapshot);
                    return;
                }
                Thread.sleep(1000);
            }

            // 超时：标记为失败并发送通知
            WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
            snapshot.status = "FAILED";
            snapshot.errorMessage = "Workflow did not finish within the WeChat notification window.";
            sendAndRecord(openId, snapshot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WeChat workflow notifier interrupted | workflowId={}", workflowId);
        } catch (Exception e) {
            log.error("WeChat workflow notifier failed | workflowId={}, error={}", workflowId, e.getMessage(), e);
        }
    }

    /**
     * 发送工作流结果并更新投递记录。
     *
     * <p>执行以下步骤：
     * <ol>
     *   <li>标记工作流已完成，记录答案预览；</li>
     *   <li>通过微信客服消息接口发送结果；</li>
     *   <li>根据发送结果更新投递记录状态（成功/失败）。</li>
     * </ol>
     *
     * @param openId   微信用户的 OpenID
     * @param snapshot 工作流运行快照
     */
    private void sendAndRecord(String openId, WorkflowRunSnapshot snapshot) {
        // 标记工作流完成，更新投递记录状态为 SENDING
        sessionStore.markWorkflowComplete(snapshot.workflowId, snapshot);
        String link = null;
        try {
            // 生成工作流详情页面链接
            link = replyLink(snapshot.workflowId);
            // 发送工作流结果到微信
            WeChatSendResult result = replyService.sendWorkflowResult(openId, snapshot);
            if (result.success) {
                sessionStore.markSent(snapshot.workflowId, result, link);
            } else {
                sessionStore.markFailed(snapshot.workflowId, result, link);
            }
        } catch (Exception e) {
            // 发送异常，记录失败
            WeChatSendResult result = WeChatSendResult.failure("SEND_EXCEPTION", e.getMessage());
            sessionStore.markFailed(snapshot.workflowId, result, link);
            log.error("WeChat delivery failed | workflowId={} | error={}", snapshot.workflowId, e.getMessage(), e);
        }
    }

    /**
     * 生成工作流详情页面的公开链接。
     *
     * @param workflowId 工作流 ID
     * @return 完整的前端页面 URL
     */
    private String replyLink(String workflowId) {
        return replyService.publicWorkflowLink(workflowId);
    }
}
