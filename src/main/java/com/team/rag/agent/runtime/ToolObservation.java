package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具观察结果 —— 工具调用执行后的压缩表示。
 *
 * <p>职责：作为 {@link ToolResultCompressor} 的输出，保存工具调用的压缩摘要、
 * 引用信息和原始数据引用，供 ReAct 推理步骤和前端 Trace 面板使用。
 *
 * <p>设计思想：
 * <ul>
 *   <li>使用 {@code @JsonInclude(NON_NULL)} 序列化时忽略空字段，减小传输体积</li>
 *   <li>分离压缩文本（compressedText）和原始数据引用（rawPayloadRef），
 *       LLM 上下文中只注入 compressedText，前端可通过 rawPayloadRef 获取完整数据</li>
 *   <li>{@code citations} 和 {@code evidence} 分别记录引用来源和证据片段，
 *       支持前端的引用展示和证据高亮</li>
 *   <li>{@code rawPayloadTruncated} 标记原始数据是否被截断，前端可据此提示用户</li>
 * </ul>
 *
 * <p>在系统中的位置：ToolResultCompressor.compress() -> 生成 ToolObservation
 * -> 存入 ToolObservationStore -> 注入 ReAct 工作记忆 -> 前端 Trace 面板展示。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolObservation {

    /** 观察记录的唯一标识（如 "obs_a1b2c3d4"） */
    public String observationId;

    /** 所属工作流标识 */
    public String workflowId;

    /** 所属步骤标识（如 "react-3"） */
    public String stepId;

    /** 被调用的工具名称 */
    public String toolName;

    /** 执行状态："SUCCESS" 或 "FAILED" */
    public String status;

    /** 一句话摘要（如 "Retrieved 5 evidence span(s)."） */
    public String summary;

    /** 压缩后的文本内容，用于注入 LLM 上下文窗口 */
    public String compressedText;

    /** 原始负载数据的引用标识，前端可通过此 ID 获取完整数据 */
    public String rawPayloadRef;

    /** 原始负载数据的预览文本（截断到 20000 字符） */
    public String rawPayloadPreview;

    /** 原始负载数据是否被截断 */
    public boolean rawPayloadTruncated;

    /** 工具执行耗时（毫秒） */
    public long latencyMs;

    /** Token size before/after deterministic or semantic compression. */
    public int rawTokenCount;
    public int compressedTokenCount;

    /** Structured facts retained for the next reasoning step. */
    public List<Map<String, Object>> facts = new ArrayList<>();

    /** Compression warnings, including schema loss or citation issues. */
    public List<String> warnings = new ArrayList<>();

    /** 引用列表，每个元素包含 documentId、title、section、pageNumber 等引用信息 */
    public List<Map<String, Object>> citations = new ArrayList<>();

    /** 证据片段列表，结构与 citations 类似，用于前端证据高亮展示 */
    public List<Map<String, Object>> evidence = new ArrayList<>();

    /** 记录创建时间 */
    public Instant createdAt = Instant.now();
}
