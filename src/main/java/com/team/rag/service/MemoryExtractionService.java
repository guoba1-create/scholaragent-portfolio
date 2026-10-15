package com.team.rag.service;

import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.memory.MemoryLifecycleStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 记忆提取服务
 *
 * <p>在工作流完成后，从用户消息和助手回答中提取值得记住的信息，
 * 保存为语义记忆（向量化后存入 EmbeddingStore）。</p>
 *
 * <p><b>提取策略：</b></p>
 * <ul>
 *   <li><b>显式记忆</b>：用户消息包含"记住"、"偏好"、"我喜欢"等关键词 → type=user_preference</li>
 *   <li><b>研究问题</b>：路由决策包含 research/paper/claim 等信号 → type=research_question</li>
 *   <li><b>工作流回答</b>：回答长度适中且非拒答 → type=workflow_answer</li>
 * </ul>
 *
 * <p><b>异步执行：</b>使用单线程 ExecutorService 异步执行，不阻塞主流程。</p>
 *
 * @see DualEngineMemoryManager — 语义记忆存储
 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);

    private final DualEngineMemoryManager memoryManager; // 语义记忆存储
    /** 单线程线程池，用于异步提取记忆，不阻塞主流程 */
    private final ExecutorService executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(200),
            runnable -> {
                Thread thread = new Thread(runnable, "memory-extractor");
                thread.setDaemon(true);
                return thread;
            },
            (task, executor) -> log.warn("Memory extraction queue full; candidate dropped"));

    public MemoryExtractionService(DualEngineMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 工作流完成后异步提取记忆
     * <p>将提取任务提交到单线程线程池，立即返回不阻塞。</p>
     */
    public void captureAfterWorkflow(
            String userId,
            String researchGroupId,
            String conversationId,
            String workflowId,
            String userMessage,
            String finalAnswer,
            HybridRouteDecision routeDecision) {
        if (isBlank(userId) || isBlank(userMessage)) {
            return;
        }
        if (!memoryManager.isLongTermMemoryEnabled(userId)) {
            return;
        }
        executor.submit(() -> captureSafely(userId, researchGroupId, conversationId, workflowId,
                userMessage, finalAnswer, routeDecision));
    }

    private void captureSafely(
            String userId,
            String researchGroupId,
            String conversationId,
            String workflowId,
            String userMessage,
            String finalAnswer,
            HybridRouteDecision routeDecision) {
        try {
            List<MemoryCandidate> candidates = extractMemoryCandidates(userMessage, finalAnswer, routeDecision);
            for (MemoryCandidate candidate : candidates) {
                if (isBlank(candidate.content)) {
                    continue;
                }
                memoryManager.saveSemanticMemory(
                        userId,
                        candidate.content,
                        researchGroupId,
                        conversationId,
                        workflowId,
                        candidate.type,
                        "private",
                        candidate.confidence,
                        candidate.status);
            }
        } catch (Exception e) {
            log.warn("Memory extraction skipped | workflowId={}, error={}", workflowId, e.getMessage());
        }
    }

    private List<MemoryCandidate> extractMemoryCandidates(
            String userMessage, String finalAnswer, HybridRouteDecision routeDecision) {
        List<MemoryCandidate> candidates = new ArrayList<>();
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.length() < 8 || text.length() > 1200) {
            return candidates;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        boolean explicitMemory = containsAny(lower,
                "remember",
                "my preference",
                "i prefer",
                "save this",
                "from now on",
                "\u8bb0\u4f4f",
                "\u4ee5\u540e",
                "\u504f\u597d",
                "\u6211\u559c\u6b22",
                "\u6211\u4e0d\u559c\u6b22",
                "\u6211\u7684\u7814\u7a76",
                "\u6211\u6b63\u5728",
                "\u6211\u5173\u6ce8",
                "\u4fdd\u5b58\u4e3a\u8bb0\u5fc6");
        boolean researchSignal = routeDecision != null && containsAny(
                safe(routeDecision.taskType),
                "research",
                "paper",
                "claim",
                "qa");

        if (explicitMemory) {
            candidates.add(new MemoryCandidate(
                    "user_preference", text, MemoryLifecycleStatus.ACTIVE, 1.0));
        }
        if (researchSignal) {
            candidates.add(new MemoryCandidate(
                    "research_question",
                    "Recent research question: " + text,
                    MemoryLifecycleStatus.CANDIDATE,
                    0.65));
            String answerMemory = extractAnswerMemory(finalAnswer);
            if (!isBlank(answerMemory)) {
                candidates.add(new MemoryCandidate(
                        "workflow_answer", answerMemory, MemoryLifecycleStatus.CANDIDATE, 0.55));
            }
        }
        return candidates;
    }

    private String extractAnswerMemory(String finalAnswer) {
        String answer = finalAnswer == null ? "" : finalAnswer.trim();
        if (answer.length() < 40 || answer.length() > 4000) {
            return "";
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        if (containsAny(lower,
                "no sufficient evidence",
                "超出知识范围",
                "无法回答",
                "did not retrieve usable evidence",
                "workflow finished but did not return")) {
            return "";
        }
        return "Recent workflow answer: " + truncate(answer, 900);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private record MemoryCandidate(
            String type,
            String content,
            MemoryLifecycleStatus status,
            double confidence) {
    }
}
