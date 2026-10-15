package com.team.rag.controller;

import com.team.rag.entity.RetrievalDiagnosticsResult;
import com.team.rag.identity.RequestIdentity;
import com.team.rag.identity.RequestIdentityResolver;
import com.team.rag.service.RetrievalDiagnosticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 检索诊断控制器 —— 提供 RAG 检索管线的诊断与调试接口。
 *
 * <p>本控制器用于诊断向量检索管线的健康状况，检查指定课题组下的文档清单数量、
 * 检索命中率、相似度分数分布等指标。是管线健康评估和问题排查的核心工具。</p>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/retrieval/diagnose</td><td>执行检索诊断，返回命中率和详细指标</td></tr>
 * </table>
 *
 * @see RetrievalDiagnosticsService 检索诊断服务
 * @see RetrievalDiagnosticsResult 诊断结果实体
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalDiagnosticsController {

    /** 检索诊断服务，负责执行实际的检索诊断逻辑 */
    private final RetrievalDiagnosticsService diagnosticsService;
    private final RequestIdentityResolver identityResolver;

    /**
     * 构造函数，通过 Spring 依赖注入初始化诊断服务。
     *
     * @param diagnosticsService 检索诊断服务
     */
    public RetrievalDiagnosticsController(RetrievalDiagnosticsService diagnosticsService,
                                          RequestIdentityResolver identityResolver) {
        this.diagnosticsService = diagnosticsService;
        this.identityResolver = identityResolver;
    }

    /**
     * 检索诊断端点 —— 对指定课题组和查询执行检索诊断。
     *
     * <p>诊断内容包括：</p>
     * <ul>
     *   <li>manifestCount：该课题组下的文档清单数量</li>
     *   <li>retrievalHitCount：实际命中的向量检索结果数量</li>
     *   <li>topScores：排名靠前的相似度分数列表</li>
     *   <li>warnings：诊断过程中发现的警告信息</li>
     *   <li>suggestions：改进建议</li>
     * </ul>
     *
     * @param request 请求体，包含：
     *                <ul>
     *                  <li>userId：用户 ID（默认 "user-001"）</li>
     *                  <li>researchGroupId：课题组 ID（默认 "replace-me"）</li>
     *                  <li>query：诊断用查询文本</li>
     *                  <li>topK：返回的最大结果数（默认 10）</li>
     *                  <li>minScore：最低相似度阈值（默认 0.0）</li>
     *                </ul>
     * @return 诊断结果，包含命中率、分数分布和警告信息
     */
    @PostMapping("/diagnose")
    public ResponseEntity<RetrievalDiagnosticsResult> diagnose(@RequestBody Map<String, Object> request,
                                                               HttpServletRequest servletRequest) {
        // 解析请求参数，设置默认值
        RequestIdentity identity = identityResolver.resolve(servletRequest);
        String userId = identity.userId();
        String researchGroupId = identity.knowledgeSpaceId();
        String query = stringValue(request, "query", "");
        int topK = intValue(request, "topK", 10);
        double minScore = doubleValue(request, "minScore", 0.0);

        // 调用诊断服务执行检索诊断
        return ResponseEntity.ok(diagnosticsService.diagnose(userId, researchGroupId, query, topK, minScore));
    }

    // ==================== 内部工具方法 ====================

    /**
     * 从 Map 中安全提取字符串值，缺失或空白时返回 fallback。
     *
     * @param map      源 Map
     * @param key      键名
     * @param fallback 默认值
     * @return 字符串值或 fallback
     */
    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /**
     * 从 Map 中安全提取整数值，支持 Number 类型和字符串解析。
     * 解析失败时返回 fallback。
     *
     * @param map      源 Map
     * @param key      键名
     * @param fallback 默认值
     * @return 整数值或 fallback
     */
    private int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 从 Map 中安全提取双精度浮点值，支持 Number 类型和字符串解析。
     * 解析失败时返回 fallback。
     *
     * @param map      源 Map
     * @param key      键名
     * @param fallback 默认值
     * @return 浮点值或 fallback
     */
    private double doubleValue(Map<String, Object> map, String key, double fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
