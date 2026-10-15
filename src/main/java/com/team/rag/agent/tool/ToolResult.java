package com.team.rag.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具执行结果
 *
 * <p>泛型封装工具执行的统一返回结构，包含执行状态、返回数据、错误信息和执行统计。
 * 通过静态工厂方法 {@link #success} / {@link #failure} / {@link #empty} 创建实例。</p>
 *
 * @param <O> 输出数据类型（如 String、Map、List 等）
 * @see ToolContext — 工具执行上下文
 * @see MCPToolRegistry — 统一工具执行入口
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResult<O> {

    /** 执行是否成功 */
    @JsonProperty("success")
    private boolean success;

    /** 成功时的返回数据 */
    @JsonProperty("data")
    private O data;

    /** 失败时的错误码（如 "DOCUMENT_NOT_FOUND"） */
    @JsonProperty("errorCode")
    private String errorCode;

    /** 失败时的错误描述 */
    @JsonProperty("errorMessage")
    private String errorMessage;

    /** 执行耗时（毫秒） */
    @JsonProperty("latencyMs")
    private long latencyMs;

    /** LLM token 消耗（如果工具内部调用了 LLM） */
    @JsonProperty("tokenCost")
    private int tokenCost;

    /** 追踪 ID，关联 AgentTraceStep */
    @JsonProperty("traceId")
    private String traceId;

    // Constructors
    public ToolResult() {}

    public ToolResult(boolean success, O data) {
        this.success = success;
        this.data = data;
    }

    // Static factory methods
    public static <O> ToolResult<O> success(O data) {
        return new ToolResult<>(true, data);
    }

    public static <O> ToolResult<O> success(O data, long latencyMs) {
        ToolResult<O> result = new ToolResult<>(true, data);
        result.setLatencyMs(latencyMs);
        return result;
    }

    public static <O> ToolResult<O> failure(String errorCode, String errorMessage) {
        ToolResult<O> result = new ToolResult<>();
        result.setSuccess(false);
        result.setErrorCode(errorCode);
        result.setErrorMessage(errorMessage);
        return result;
    }

    public static <O> ToolResult<O> failure(String errorCode, String errorMessage, long latencyMs) {
        ToolResult<O> result = failure(errorCode, errorMessage);
        result.setLatencyMs(latencyMs);
        return result;
    }

    public static <O> ToolResult<O> empty() {
        return new ToolResult<>(true, null);
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public O getData() { return data; }
    public void setData(O data) { this.data = data; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public int getTokenCost() { return tokenCost; }
    public void setTokenCost(int tokenCost) { this.tokenCost = tokenCost; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    /**
     * 获取数据预览（截断长文本）
     *
     * @param maxLength 最大长度
     * @return 数据预览
     */
    public String getDataPreview(int maxLength) {
        if (data == null) return "null";
        String str = data.toString();
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    @Override
    public String toString() {
        return "ToolResult{" +
                "success=" + success +
                ", data=" + (data != null ? data.toString().substring(0, Math.min(100, data.toString().length())) : "null") +
                ", errorCode='" + errorCode + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", latencyMs=" + latencyMs +
                '}';
    }
}
