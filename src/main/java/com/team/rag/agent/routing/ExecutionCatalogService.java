package com.team.rag.agent.routing;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single source of truth for public modes, workflow templates and real agents. */
@Service
public class ExecutionCatalogService {

    public static final String CATALOG_VERSION = "execution-catalog-v3";
    public static final String DEEP_RESEARCH_PROFILE = "deep_research_react";
    public static final String EXTERNAL_SCOUT_PROFILE = "external_scholar_scout_react";
    public static final Set<String> AGENT_PROFILES = Set.of(
            DEEP_RESEARCH_PROFILE, EXTERNAL_SCOUT_PROFILE);

    public List<Map<String, Object>> publicModes() {
        return List.of(
                mode("auto", "自动", "系统根据任务选择快速问答、证据工作流或深度研究"),
                mode("quick", "快速回答", "低延迟的对话或增强 RAG"),
                mode("deep", "深度研究", "动态计划、证据评估与受控外部补证")
        );
    }

    public List<Map<String, Object>> workflowTemplates() {
        return List.of(
                template(WorkflowTemplate.CLAIM_VERIFY, "论文依据核验"),
                template(WorkflowTemplate.PAPER_COMPARE, "论文比较"),
                template(WorkflowTemplate.RELATED_WORK, "相关工作分析"),
                template(WorkflowTemplate.CITATION_SUPPORT, "引用支持")
        );
    }

    public List<Map<String, Object>> agents() {
        return List.of(
                agent(
                        DEEP_RESEARCH_PROFILE,
                        "DeepResearch Coordinator",
                        "生成研究计划、执行本地检索、评价覆盖度、重规划并合成报告",
                        12,
                        12,
                        List.of("list_papers", "get_library_summary", "query_rewrite",
                                "retrieve_evidence", "judge_evidence_quality", "read_document",
                                "memory_recall", "diagnose_library"),
                        List.of(EXTERNAL_SCOUT_PROFILE)
                ),
                agent(
                        EXTERNAL_SCOUT_PROFILE,
                        "External Scholar Scout",
                        "根据最小化委托检索外部论文和网页证据，不生成最终报告",
                        5,
                        6,
                        List.of("query_rewrite", "search_papers", "web_search"),
                        List.of()
                )
        );
    }

    public boolean isAgentProfile(String profile) {
        return profile != null && AGENT_PROFILES.contains(profile);
    }

    public WorkflowTemplate templateForTask(String taskType) {
        if (taskType == null) {
            return null;
        }
        return switch (taskType) {
            case "claim_verification" -> WorkflowTemplate.CLAIM_VERIFY;
            case "paper_compare" -> WorkflowTemplate.PAPER_COMPARE;
            case "related_work" -> WorkflowTemplate.RELATED_WORK;
            case "citation_recommendation" -> WorkflowTemplate.CITATION_SUPPORT;
            default -> null;
        };
    }

    private Map<String, Object> mode(String id, String name, String description) {
        return Map.of("id", id, "name", name, "description", description);
    }

    private Map<String, Object> template(WorkflowTemplate id, String name) {
        return Map.of("id", id.name(), "name", name);
    }

    private Map<String, Object> agent(String profile, String name, String description,
                                      int maxSteps, int maxToolCalls, List<String> tools,
                                      List<String> handoffTargets) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profile", profile);
        row.put("agentName", name);
        row.put("strategy", ExecutionStrategy.DEEP_RESEARCH.name());
        row.put("description", description);
        row.put("maxSteps", maxSteps);
        row.put("maxToolCalls", maxToolCalls);
        row.put("allowedTools", tools);
        row.put("handoffTargets", handoffTargets);
        return row;
    }
}
