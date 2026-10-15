package com.team.rag.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行上下文
 *
 * <p>封装工具执行所需的所有上下文信息，包括调用者身份、所属工作流和追踪开关。
 * 由 {@link MCPToolRegistry} 在执行工具前构建并传递给具体工具。</p>
 *
 * @see com.team.rag.agent.tool.ToolResult — 工具执行结果
 * @see com.team.rag.agent.trace.AgentTraceStep — 追踪步骤
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolContext {

    /** 当前工作流 ID，用于关联追踪和事件 */
    @JsonProperty("workflowId")
    private String workflowId;

    /** 发起请求的用户 ID */
    @JsonProperty("userId")
    private String userId;

    /** 研究组 ID，用于多租户数据隔离 */
    @JsonProperty("researchGroupId")
    private String researchGroupId;

    /** 调用此工具的节点或 Agent 名称 */
    @JsonProperty("agentName")
    private String agentName;

    /** 是否启用执行追踪（默认 true，关闭可减少日志开销） */
    @JsonProperty("traceEnabled")
    private boolean traceEnabled = true;

    /** 附加元数据，用于传递自定义键值对 */
    @JsonProperty("metadata")
    private Map<String, Object> metadata = new HashMap<>();

    // Constructors
    public ToolContext() {}

    public ToolContext(String workflowId, String userId, String researchGroupId, String agentName) {
        this.workflowId = workflowId;
        this.userId = userId;
        this.researchGroupId = researchGroupId;
        this.agentName = agentName;
    }

    // Static factory method
    public static ToolContext of(String workflowId, String userId, String researchGroupId, String agentName) {
        return new ToolContext(workflowId, userId, researchGroupId, agentName);
    }

    // Getters and Setters
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getResearchGroupId() { return researchGroupId; }
    public void setResearchGroupId(String researchGroupId) { this.researchGroupId = researchGroupId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public boolean isTraceEnabled() { return traceEnabled; }
    public void setTraceEnabled(boolean traceEnabled) { this.traceEnabled = traceEnabled; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public void addMetadata(String key, Object value) { this.metadata.put(key, value); }

    @Override
    public String toString() {
        return "ToolContext{" +
                "workflowId='" + workflowId + '\'' +
                ", userId='" + userId + '\'' +
                ", agentName='" + agentName + '\'' +
                '}';
    }
}
