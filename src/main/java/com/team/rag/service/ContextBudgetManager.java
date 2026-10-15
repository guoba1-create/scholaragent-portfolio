package com.team.rag.service;

import com.team.rag.agent.runtime.ContextBudgetSnapshot;
import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.entity.RetrievalHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上下文预算管理器
 *
 * <p>问题：召回来的内容很多（记忆、检索结果、文档），全塞进 prompt 会超出 LLM 的 token 限制。</p>
 * <p>解决：按引擎类型分配字符预算，每段内容独立裁剪。</p>
 *
 * <p><b>引擎级预算（最大字符数）：</b></p>
 * <table>
 *   <tr><th>引擎</th><th>总预算</th><th>短期摘要</th><th>语义记忆</th><th>项目上下文</th><th>诊断信息</th></tr>
 *   <tr><td>react</td><td>3000</td><td>700</td><td>800</td><td>900</td><td>260</td></tr>
 *   <tr><td>workflow</td><td>3400</td><td>800</td><td>900</td><td>1000</td><td>300</td></tr>
 *   <tr><td>默认/auto</td><td>4200</td><td>1000</td><td>1100</td><td>1400</td><td>400</td></tr>
 * </table>
 *
 * <p>ReAct 引擎预算更紧，因为它的每一步都需要 LLM 调用，输入上下文太大会导致速度变慢。</p>
 *
 * <p><b>裁剪逻辑：</b>每段内容独立裁剪，记录 truncatedSections，
 * 最终写入 {@link MemoryRecallRecord#compactPromptContext}。</p>
 *
 * @see MemoryRecallService — 提供原始召回数据
 */
@Service
public class ContextBudgetManager {

    /**
     * 准备上下文预算，组装并裁剪 prompt 上下文
     *
     * <p><b>组装顺序：</b></p>
     * <ol>
     *   <li>Short-term summary（对话摘要）</li>
     *   <li>Semantic memories（语义记忆，每条最多 220 字符）</li>
     *   <li>Project context（文档 Manifest + 文献命中）</li>
     *   <li>Recall diagnostics（召回诊断信息）</li>
     * </ol>
     *
     * @param engine  引擎类型（react/workflow/auto）
     * @param profile Agent Profile ID
     * @param recall  记忆召回结果
     * @return 上下文预算快照，包含裁剪后的 promptContext
     */
    public ContextBudgetSnapshot prepare(String engine, String profile, MemoryRecallRecord recall) {
        ContextBudgetSnapshot snapshot = new ContextBudgetSnapshot();
        snapshot.engine = isBlank(engine) ? "auto" : engine;
        snapshot.profile = isBlank(profile) ? "auto" : profile;
        snapshot.maxPromptChars = maxPromptChars(snapshot.engine, snapshot.profile);

        if (recall == null) {
            snapshot.diagnostics.add("No memory recall record was provided.");
            return snapshot;
        }

        String shortTerm = trim(defaultString(recall.shortTermSummary), shortTermLimit(snapshot.engine));
        String semantic = buildSemanticSection(recall, snapshot);
        String project = buildProjectSection(recall, snapshot);
        String diagnostics = buildDiagnosticsSection(recall, snapshot);

        snapshot.shortTermChars = shortTerm.length();
        snapshot.semanticChars = semantic.length();
        snapshot.projectChars = project.length();
        snapshot.diagnosticsChars = diagnostics.length();

        StringBuilder prompt = new StringBuilder();
        appendSection(prompt, "Short-term summary", shortTerm);
        appendSection(prompt, "Semantic memories", semantic);
        appendSection(prompt, "Project context", project);
        appendSection(prompt, "Recall diagnostics", diagnostics);

        if (prompt.length() > snapshot.maxPromptChars) {
            snapshot.truncated = true;
            snapshot.truncatedSections.add("final_prompt");
        }
        snapshot.promptContext = trim(prompt.toString(), snapshot.maxPromptChars);
        snapshot.totalPromptChars = snapshot.promptContext.length();

        recall.compactPromptContext = snapshot.promptContext;
        recall.truncatedSections = new ArrayList<>(snapshot.truncatedSections);
        recall.selectedSemanticCount = snapshot.selectedSemanticCount;
        recall.selectedManifestCount = snapshot.selectedManifestCount;
        recall.selectedHitCount = snapshot.selectedHitCount;
        return snapshot;
    }

    private String buildSemanticSection(MemoryRecallRecord recall, ContextBudgetSnapshot snapshot) {
        List<Map<String, Object>> memories = recall.semanticMemories == null ? List.of() : recall.semanticMemories;
        int limit = semanticCountLimit(snapshot.engine);
        int maxChars = semanticCharLimit(snapshot.engine);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> memory : memories) {
            String content = defaultString(value(memory, "content"));
            if (content.isBlank()) {
                continue;
            }
            String line = "- " + trim(content, 220) + "\n";
            if (sb.length() + line.length() > maxChars || snapshot.selectedSemanticCount >= limit) {
                snapshot.truncated = true;
                snapshot.truncatedSections.add("semantic_memories");
                break;
            }
            sb.append(line);
            snapshot.selectedSemanticCount++;
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private String buildProjectSection(MemoryRecallRecord recall, ContextBudgetSnapshot snapshot) {
        Map<String, Object> project = recall.projectContext;
        if (project == null || project.isEmpty()) {
            return "";
        }
        int maxChars = projectCharLimit(snapshot.engine);
        StringBuilder sb = new StringBuilder();

        Object manifestsObj = project.get("manifests");
        if (manifestsObj instanceof List<?> manifests) {
            for (Object item : manifests) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> manifest = (Map<String, Object>) raw;
                String line = "- " + value(manifest, "filename") + " (" + value(manifest, "documentId")
                        + ", " + value(manifest, "status") + ", pages=" + value(manifest, "pageCount")
                        + ", chunks=" + value(manifest, "chunkCount") + ")\n";
                if (sb.length() + line.length() > maxChars || snapshot.selectedManifestCount >= manifestCountLimit(snapshot.engine)) {
                    snapshot.truncated = true;
                    snapshot.truncatedSections.add("project_manifests");
                    break;
                }
                sb.append(line);
                snapshot.selectedManifestCount++;
            }
        }

        Object hitsObj = project.get("topLiteratureHits");
        if (hitsObj instanceof List<?> hits) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            for (Object item : hits) {
                if (!(item instanceof RetrievalHit hit)) {
                    continue;
                }
                String line = "- " + trim(defaultString(hit.title), 80)
                        + " / page " + (hit.pageNumber == null ? "?" : hit.pageNumber)
                        + " / chunk " + (hit.chunkIndex == null ? "?" : hit.chunkIndex)
                        + " / score=" + (hit.score == null ? "-" : String.format("%.2f", hit.score))
                        + "\n";
                if (sb.length() + line.length() > maxChars || snapshot.selectedHitCount >= hitCountLimit(snapshot.engine)) {
                    snapshot.truncated = true;
                    snapshot.truncatedSections.add("project_hits");
                    break;
                }
                sb.append(line);
                snapshot.selectedHitCount++;
            }
        }
        return sb.toString().trim();
    }

    private String buildDiagnosticsSection(MemoryRecallRecord recall, ContextBudgetSnapshot snapshot) {
        List<String> diagnostics = recall.diagnostics == null ? List.of() : recall.diagnostics;
        StringBuilder sb = new StringBuilder();
        for (String item : diagnostics) {
            if (isBlank(item)) {
                continue;
            }
            String line = "- " + trim(item, 200) + "\n";
            if (sb.length() + line.length() > diagnosticsCharLimit(snapshot.engine)) {
                snapshot.truncated = true;
                snapshot.truncatedSections.add("recall_diagnostics");
                break;
            }
            sb.append(line);
        }
        snapshot.diagnostics = diagnostics.stream().limit(6).toList();
        return sb.toString().trim();
    }

    private void appendSection(StringBuilder sb, String title, String body) {
        if (isBlank(body)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("[").append(title).append("]\n").append(body);
    }

    /**
     * 根据引擎类型返回最大 prompt 字符数
     * <p>react 引擎预算最紧（每步都要 LLM 调用），workflow 中等，auto/default 最宽。</p>
     */
    private int maxPromptChars(String engine, String profile) {
        if ("react".equals(engine)) {
            return profile != null && profile.contains("claim") ? 2600 : 3000;
        }
        if ("workflow".equals(engine)) {
            return 3400;
        }
        return 4200;
    }

    private int shortTermLimit(String engine) {
        return "react".equals(engine) ? 700 : 1000;
    }

    private int semanticCharLimit(String engine) {
        return "react".equals(engine) ? 800 : 1100;
    }

    private int projectCharLimit(String engine) {
        return "react".equals(engine) ? 900 : 1400;
    }

    private int diagnosticsCharLimit(String engine) {
        return "react".equals(engine) ? 260 : 400;
    }

    private int semanticCountLimit(String engine) {
        return "react".equals(engine) ? 3 : 5;
    }

    private int manifestCountLimit(String engine) {
        return "react".equals(engine) ? 4 : 6;
    }

    private int hitCountLimit(String engine) {
        return "react".equals(engine) ? 3 : 5;
    }

    private String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
