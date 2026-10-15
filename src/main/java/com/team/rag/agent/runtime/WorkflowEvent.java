package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流事件 —— 工作流执行过程中产生的可观测事件。
 *
 * <p>面向前端 Agent Workbench 面板，实时推送工作流的执行进度。
 * 每个事件记录了事件类型、所处阶段、执行的 Agent/工具、状态摘要等信息，
 * 用于前端进度展示和调试追踪。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowEvent {

    /** 事件唯一标识符，格式：evt_{uuid8} */
    @JsonProperty("eventId")
    private String eventId = "evt_" + UUID.randomUUID().toString().substring(0, 8);

    /** 所属工作流 ID */
    @JsonProperty("workflowId")
    private String workflowId;

    /** 事件类型，如 "STEP_START"、"TOOL_CALL"、"STEP_COMPLETE"、"WORKFLOW_COMPLETE" 等 */
    @JsonProperty("eventType")
    private String eventType;

    /** 工作流所处阶段，如 "ROUTING"、"RETRIEVING"、"VERIFYING"、"SYNTHESIZING" 等 */
    @JsonProperty("phase")
    private String phase;

    /** 触发事件的 Agent 名称 */
    @JsonProperty("agentName")
    private String agentName;

    /** 调用的工具名称（工具调用事件时填入） */
    @JsonProperty("toolName")
    private String toolName;

    /** 事件状态，如 "STARTED"、"IN_PROGRESS"、"COMPLETED"、"FAILED" */
    @JsonProperty("status")
    private String status;

    /** 事件摘要，人类可读的简短描述 */
    @JsonProperty("summary")
    private String summary;

    /** 事件附加数据，存储与事件相关的详细信息 */
    @JsonProperty("payload")
    private Map<String, Object> payload = new LinkedHashMap<>();

    /** 事件创建时间 */
    @JsonProperty("createdAt")
    private Instant createdAt = Instant.now();

    public WorkflowEvent() {}

    public WorkflowEvent(String workflowId, String eventType, String phase, String status, String summary) {
        this.workflowId = workflowId;
        this.eventType = eventType;
        this.phase = phase;
        this.status = status;
        this.summary = summary;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public void addPayload(String key, Object value) { this.payload.put(key, value); }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
