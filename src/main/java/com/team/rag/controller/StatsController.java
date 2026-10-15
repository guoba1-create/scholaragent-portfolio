package com.team.rag.controller;

import com.team.rag.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 统计数据 REST 控制器 -- 提供系统统计数据接口。
 *
 * <p>为前端数据看板提供真实的统计数据，包括：</p>
 * <ul>
 *   <li>总对话数、文档数、记忆数</li>
 *   <li>对话活动趋势</li>
 *   <li>功能使用分布</li>
 * </ul>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/stats/overview</td><td>系统概览统计（对话/文档/记忆总数、平均响应时间）</td></tr>
 *   <tr><td>GET</td><td>/api/stats/activity</td><td>对话活动趋势（按天统计）</td></tr>
 *   <tr><td>GET</td><td>/api/stats/usage</td><td>功能使用分布统计</td></tr>
 *   <tr><td>POST</td><td>/api/stats/conversation</td><td>记录对话事件（内部调用）</td></tr>
 *   <tr><td>POST</td><td>/api/stats/document</td><td>记录文档上传事件（内部调用）</td></tr>
 *   <tr><td>POST</td><td>/api/stats/memory</td><td>记录记忆保存事件（内部调用）</td></tr>
 * </table>
 *
 * @see StatsService 统计服务
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(StatsController.class);

    /** 统计服务，负责统计数据的收集、聚合和查询 */
    private final StatsService statsService;

    /**
     * 构造函数，通过 Spring 依赖注入初始化统计服务。
     *
     * @param statsService 统计服务
     */
    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 获取系统概览统计 -- 返回当前系统的整体统计数据。
     *
     * <p>包括总对话数、总文档数、总记忆数和平均响应时间（毫秒）。
     * 用于前端首页的数据看板展示。</p>
     *
     * @return 包含各项统计数据的 Map：totalConversations、totalDocuments、
     *         totalMemories、avgResponseTime
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        log.debug("获取系统概览统计");

        // 聚合各项统计数据
        Map<String, Object> stats = Map.of(
                "totalConversations", statsService.getTotalConversations(),
                "totalDocuments", statsService.getTotalDocuments(),
                "totalMemories", statsService.getTotalMemories(),
                "avgResponseTime", statsService.getAvgResponseTime()
        );

        return ResponseEntity.ok(stats);
    }

    /**
     * 获取对话活动趋势 -- 返回指定天数内每天的对话数量。
     *
     * <p>用于前端活动趋势图的展示，支持自定义时间范围。</p>
     *
     * @param days 查询的天数范围（默认 7 天）
     * @return 包含 success 标志和每日对话数据列表的响应
     */
    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> getActivityTrend(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        log.debug("获取对话活动趋势 | days={}", days);

        // 查询指定天数内的活动趋势数据
        List<Map<String, Object>> trend = statsService.getActivityTrend(days);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", trend
        ));
    }

    /**
     * 获取功能使用分布 -- 返回各功能模块的使用次数统计。
     *
     * <p>统计维度包括学术检索、记忆管理、Telegram 集成等各功能的调用次数，
     * 用于前端功能使用热力图或饼图展示。</p>
     *
     * @return 包含 success 标志和功能使用分布 Map 的响应
     */
    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsageDistribution() {
        log.debug("获取功能使用分布");

        // 查询各功能模块的使用次数
        Map<String, Integer> distribution = statsService.getUsageDistribution();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", distribution
        ));
    }

    /**
     * 记录对话事件 -- 由 ChatController 在对话完成后调用。
     *
     * <p>记录一次对话的用户 ID、课题组 ID 和响应时间，
     * 用于更新对话总数和平均响应时间等统计指标。</p>
     *
     * @param userId        用户 ID
     * @param teamId        课题组/团队 ID
     * @param responseTime  本次对话的响应时间（毫秒）
     * @return 操作结果（success=true）
     */
    @PostMapping("/conversation")
    public ResponseEntity<Map<String, Object>> recordConversation(
            @RequestParam("userId") String userId,
            @RequestParam("teamId") String teamId,
            @RequestParam("responseTime") long responseTime) {
        log.debug("记录对话事件 | userId={}, teamId={}, responseTime={}ms", userId, teamId, responseTime);

        // 委托统计服务记录对话事件
        statsService.recordConversation(userId, teamId, responseTime);

        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 记录文档上传事件 -- 由文档处理管线在入库完成后调用。
     *
     * <p>记录文档上传的课题组和分块数量，用于更新文档总数统计。</p>
     *
     * @param teamId       课题组/团队 ID
     * @param segmentCount 本次上传产生的文档分块数量
     * @return 操作结果（success=true）
     */
    @PostMapping("/document")
    public ResponseEntity<Map<String, Object>> recordDocumentUpload(
            @RequestParam("teamId") String teamId,
            @RequestParam("segmentCount") int segmentCount) {
        log.debug("记录文档上传事件 | teamId={}, segmentCount={}", teamId, segmentCount);

        // 委托统计服务记录文档上传事件
        statsService.recordDocumentUpload(teamId, segmentCount);

        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 记录记忆保存事件 -- 由记忆管理器在保存记忆后调用。
     *
     * <p>记录记忆保存的用户和类型（structured/semantic），用于更新记忆总数统计。</p>
     *
     * @param userId 用户 ID
     * @param type   记忆类型："structured"（结构化档案）或 "semantic"（语义记忆）
     * @return 操作结果（success=true）
     */
    @PostMapping("/memory")
    public ResponseEntity<Map<String, Object>> recordMemorySave(
            @RequestParam("userId") String userId,
            @RequestParam("type") String type) {
        log.debug("记录记忆保存事件 | userId={}, type={}", userId, type);

        // 委托统计服务记录记忆保存事件
        statsService.recordMemorySave(userId, type);

        return ResponseEntity.ok(Map.of("success", true));
    }
}
