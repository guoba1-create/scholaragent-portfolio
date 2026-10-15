package com.team.rag.agent.routing;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 混合路由决策结果 —— 封装用户意图分类和引擎路由的决策信息。
 *
 * <p>职责：在请求进入系统后，由混合路由器（Hybrid Router）分析用户意图，
 * 决定将请求路由到哪个执行引擎（如 ReAct Agent、Workflow Pipeline 等），
 * 本类作为该决策过程的输出载体。
 *
 * <p>设计思想：
 * <ul>
 *   <li>使用 {@code @JsonInclude(NON_NULL)} 序列化时忽略空字段，减少前端解析负担</li>
 *   <li>{@code signals} 列表记录路由器的判断依据（如关键词命中、语义相似度），
 *       便于调试和决策可解释性</li>
 *   <li>{@code metadata} Map 保留扩展能力，可携带路由器的任意附加信息</li>
 *   <li>提供链式 {@code addSignal} 方法，方便路由器逐步构建决策</li>
 * </ul>
 *
 * <p>在系统中的位置：请求入口 -> IntentClassifier -> HybridRouter -> 生成 HybridRouteDecision
 * -> 根据 engine 字段分发到 ReAct Runtime 或 Workflow Pipeline。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HybridRouteDecision {

    /** 目标执行引擎（如 "react", "workflow", "direct" 等） */
    public String engine;

    /** 任务类型，更细粒度的分类（如 "evidence_retrieval", "document_read"） */
    public String taskType;
    public String intent;

    /** 目标 Agent 的 profile 名称，用于 Handoff 场景 */
    public String profile;

    /** 路由决策的置信度，取值范围 [0.0, 1.0]，越高表示路由器越确定 */
    public double confidence;

    /** 路由决策的理由说明，用于日志和前端展示 */
    public String reason;

    /** 路由判断的信号列表（如 "keyword:论文列表", "semantic:0.92"），用于可解释性分析 */
    public List<String> signals = new ArrayList<>();

    /**
     * Deprecated compatibility field. Current workflow topology is selected
     * by taskType/WorkflowTemplate and this value remains null.
     */
    public String executionMode;

    /** 路由器附加的元数据，可携带任意键值对 */
    public Map<String, Object> metadata;

    /** 决策创建时间戳 */
    public Instant createdAt = Instant.now();

    /**
     * 静态工厂方法 —— 创建完整的路由决策。
     *
     * @param engine      目标引擎
     * @param taskType    任务类型（同时承担原 intent 的语义）
     * @param profile     目标 Agent profile
     * @param confidence  置信度
     * @param reason      决策理由
     * @return 新的 HybridRouteDecision 实例
     */
    public static HybridRouteDecision of(String engine, String taskType,
                                         String profile, double confidence, String reason) {
        HybridRouteDecision decision = new HybridRouteDecision();
        decision.engine = engine;
        decision.taskType = taskType;
        decision.intent = taskType;
        decision.profile = profile;
        decision.confidence = confidence;
        decision.reason = reason;
        return decision;
    }

    /**
     * 链式添加路由信号。
     * <p>信号用于记录路由器的判断依据，便于调试和决策回溯。
     *
     * @param signal 信号描述（如 "keyword:论文", "semantic:0.85"）
     * @return this，支持链式调用
     */
    public HybridRouteDecision addSignal(String signal) {
        if (signal != null && !signal.isBlank()) {
            signals.add(signal);
        }
        return this;
    }
}
