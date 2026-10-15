package com.team.rag.controller;

import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.ExecutionDecisionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 路由分析控制器 —— 提供混合路由决策的分析接口。
 *
 * <p>本控制器用于分析用户消息应由哪个引擎（Engine）和配置（Profile）处理。
 * RAG 系统采用混合路由策略（Hybrid Router），根据消息内容、意图和上下文
 * 自动选择最合适的处理引擎（如 ReAct Agent、直接检索、知识图谱等）。</p>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/router/analyze</td><td>分析消息路由决策，返回推荐的引擎和配置</td></tr>
 * </table>
 *
 * @see HybridRouterService 混合路由服务
 * @see HybridRouteDecision 路由决策结果
 */
@RestController
@RequestMapping("/api/router")
public class RouterController {

    /** 混合路由服务，负责根据消息内容做出路由决策 */
    private final ExecutionDecisionService executionDecisionService;

    /**
     * 构造函数，通过 Spring 依赖注入初始化路由服务。
     *
     * @param hybridRouterService 混合路由服务
     */
    public RouterController(ExecutionDecisionService executionDecisionService) {
        this.executionDecisionService = executionDecisionService;
    }

    /**
     * 路由分析端点 —— 分析用户消息并返回路由决策。
     *
     * <p>输入用户消息和可选的引擎/配置偏好，返回系统推荐的路由决策，
     * 包括应使用的处理引擎、配置方案和决策理由。</p>
     *
     * @param request 路由分析请求体，包含：
     *                <ul>
     *                  <li>message：用户消息文本</li>
     *                  <li>engine：引擎偏好，默认 "auto"（自动选择）</li>
     *                  <li>profile：配置偏好，默认 "auto"（自动选择）</li>
     *                </ul>
     * @return 路由决策结果（HybridRouteDecision），包含推荐的引擎和配置
     */
    @PostMapping("/analyze")
    public ResponseEntity<ExecutionDecision> analyze(@RequestBody RouterAnalyzeRequest request) {
        return ResponseEntity.ok(executionDecisionService.decide(
                request.message,
                request.mode != null ? request.mode : (request.engine == null ? "auto" : request.engine),
                request.profile == null ? "auto" : request.profile
        ));
    }

    /**
     * 路由分析请求体 DTO。
     */
    public static class RouterAnalyzeRequest {
        /** 用户消息文本 */
        public String message;
        /** 引擎偏好："auto" 表示自动选择，也可指定具体引擎名 */
        public String engine = "auto";
        /** 产品模式：auto / quick / deep。优先于兼容字段 engine。 */
        public String mode;
        /** 配置偏好："auto" 表示自动选择，也可指定具体配置名 */
        public String profile = "auto";
    }
}
