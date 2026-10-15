package com.team.rag.agent.tool;

import com.team.rag.service.CragJudgeService;
import com.team.rag.entity.CragResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 证据质量判断工具
 *
 * <p>封装 CRAG 相关性判断能力，用于评估检索到的证据是否足够支持断言
 */
@Component
public class EvidenceQualityTool implements ScholarAgentTool<EvidenceQualityTool.Input, EvidenceQualityTool.Output> {

    private static final Logger log = LoggerFactory.getLogger(EvidenceQualityTool.class);

    private final CragJudgeService cragJudgeService;

    public EvidenceQualityTool(CragJudgeService cragJudgeService) {
        this.cragJudgeService = cragJudgeService;
    }

    @Override
    public String name() {
        return "judge_evidence_quality";
    }

    @Override
    public String description() {
        return "判断检索到的证据是否足够支持学术断言";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("证据质量判断输入");
        schema.addProperty("claim", ToolSchema.PropertySchema.string("学术断言"));
        schema.addProperty("evidence", ToolSchema.PropertySchema.string("检索到的证据"));
        schema.required("claim", "evidence");
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("证据质量判断输出");
        schema.addProperty("judgment", ToolSchema.PropertySchema.string("判断结果")
                .enumValues("SUFFICIENT", "PARTIAL", "IRRELEVANT"));
        schema.addProperty("reason", ToolSchema.PropertySchema.string("判断理由"));
        schema.addProperty("confidence", ToolSchema.PropertySchema.number("置信度"));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        log.info("执行证据质量判断 | claim={}", truncate(input.claim, 50));

        long startTime = System.currentTimeMillis();

        try {
            CragResult cragResult = cragJudgeService.judge(input.claim, input.evidence);

            Output output = new Output();
            output.judgment = cragResult.getJudgment();
            output.reason = cragResult.getReason();
            output.confidence = cragResult.isSufficient() ? 0.9 : cragResult.isPartial() ? 0.6 : 0.3;

            long latencyMs = System.currentTimeMillis() - startTime;
            log.info("证据质量判断完成 | judgment={}, latency={}ms", output.judgment, latencyMs);

            return ToolResult.success(output, latencyMs);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("证据质量判断失败 | error={}", e.getMessage(), e);
            return ToolResult.failure("JUDGMENT_ERROR", e.getMessage(), latencyMs);
        }
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ToolType type() {
        return ToolType.ANALYSIS;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 输入类
     */
    public static class Input {
        public String claim;
        public String evidence;
    }

    /**
     * 输出类
     */
    public static class Output {
        public String judgment;
        public String reason;
        public double confidence;
    }
}
