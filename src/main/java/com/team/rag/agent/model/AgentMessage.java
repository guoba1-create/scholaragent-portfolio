package com.team.rag.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

/**
 * 多 Agent 协作中的消息载体。
 *
 * <p>在工作流执行过程中，父 Agent 可以通过 Handoff 机制将子任务分发给子 Agent，
 * 本类封装了这类跨 Agent 消息的完整生命周期信息。
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>HANDOFF_REQUEST — 父 Agent 向子 Agent 发起任务委托</li>
 *   <li>HANDOFF_RESPONSE — 子 Agent 返回执行结果给父 Agent</li>
 *   <li>TASK_REQUEST / TASK_RESPONSE — 通用任务请求/响应</li>
 * </ul>
 *
 * <p>消息通过 {@link AgentMessageBus} 进行内存存储和 Redis 持久化，
 * 支持按 workflowId 查询、按 messageId 精确查找、状态更新等操作。
 *
 * @see AgentMessageBus — 消息总线，负责存储和检索
 * @see MessageType — 消息类型枚举
 * @see MessageStatus — 消息状态枚举
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMessage {

    @JsonProperty("messageId")
    private String messageId;

    @JsonProperty("workflowId")
    private String workflowId;

    @JsonProperty("parentStepId")
    private String parentStepId;

    @JsonProperty("sender")
    private String sender;

    @JsonProperty("receiver")
    private String receiver;

    @JsonProperty("messageType")
    private MessageType messageType;

    @JsonProperty("task")
    private String task;

    @JsonProperty("payload")
    private Map<String, Object> payload = new HashMap<>();

    @JsonProperty("status")
    private MessageStatus status = MessageStatus.PENDING;

    @JsonProperty("response")
    private Map<String, Object> response;

    @JsonProperty("createdAt")
    private Instant createdAt = Instant.now();

    @JsonProperty("completedAt")
    private Instant completedAt;

    public AgentMessage() {
        this.messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public AgentMessage(String workflowId, String sender, String receiver, MessageType messageType, String task) {
        this();
        this.workflowId = workflowId;
        this.sender = sender;
        this.receiver = receiver;
        this.messageType = messageType;
        this.task = task;
    }

    /** 创建 Handoff 请求消息（父 Agent → 子 Agent）。 */
    public static AgentMessage handoffRequest(String workflowId, String parentStepId,
                                               String sender, String receiver, String task) {
        AgentMessage msg = new AgentMessage(workflowId, sender, receiver, MessageType.HANDOFF_REQUEST, task);
        msg.setParentStepId(parentStepId);
        return msg;
    }

    /** 创建 Handoff 响应消息（子 Agent → 父 Agent），自动设置状态为 COMPLETED。 */
    public static AgentMessage handoffResponse(String workflowId, String parentStepId,
                                                String sender, String receiver, Map<String, Object> response) {
        AgentMessage msg = new AgentMessage(workflowId, sender, receiver, MessageType.HANDOFF_RESPONSE, null);
        msg.setParentStepId(parentStepId);
        msg.setResponse(response);
        msg.setStatus(MessageStatus.COMPLETED);
        msg.setCompletedAt(Instant.now());
        return msg;
    }

    /** 链式添加 payload 键值对。 */
    public AgentMessage addPayload(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }

    /** 标记消息为已完成，记录响应结果和完成时间。 */
    public void complete(Map<String, Object> response) {
        this.response = response;
        this.status = MessageStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** 标记消息为失败，将错误信息写入 response。 */
    public void fail(String error) {
        this.status = MessageStatus.FAILED;
        this.response = Map.of("error", error);
        this.completedAt = Instant.now();
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getParentStepId() { return parentStepId; }
    public void setParentStepId(String parentStepId) { this.parentStepId = parentStepId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }

    public Map<String, Object> getResponse() { return response; }
    public void setResponse(Map<String, Object> response) { this.response = response; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    /** 消息类型：HANDOFF（父子 Agent 任务委托）和 TASK（通用任务请求/响应）。 */
    public enum MessageType {
        HANDOFF_REQUEST("handoff_request"),
        HANDOFF_RESPONSE("handoff_response"),
        TASK_REQUEST("task_request"),
        TASK_RESPONSE("task_response");

        private final String description;

        MessageType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** 消息状态：PENDING → PROCESSING → COMPLETED / FAILED。 */
    public enum MessageStatus {
        PENDING("pending"),
        PROCESSING("processing"),
        COMPLETED("completed"),
        FAILED("failed");

        private final String description;

        MessageStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @Override
    public String toString() {
        return "AgentMessage{" +
                "id='" + messageId + '\'' +
                ", sender='" + sender + '\'' +
                ", receiver='" + receiver + '\'' +
                ", type=" + messageType +
                ", status=" + status +
                '}';
    }
}
