package com.team.rag.agent.trace;

/**
 * 追踪状态枚举
 *
 * <p>表示 {@link AgentTraceStep} 的执行状态，支持终态判断和失败检测。</p>
 *
 * @see AgentTraceStep — 追踪步骤
 */
public enum TraceStatus {

    /** 步骤正在执行中 */
    RUNNING("运行中"),
    /** 步骤执行成功 */
    SUCCESS("成功"),
    /** 步骤执行失败 */
    FAILED("失败"),
    /** 步骤被跳过（如前置条件不满足） */
    SKIPPED("跳过"),
    /** 步骤执行超时 */
    TIMEOUT("超时");

    /** 中文描述 */
    private final String description;

    TraceStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** 是否为终态（SUCCESS / FAILED / SKIPPED / TIMEOUT），终态不可再变更 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED || this == TIMEOUT;
    }

    /** 是否为成功状态 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** 是否为失败状态（FAILED 或 TIMEOUT） */
    public boolean isFailed() {
        return this == FAILED || this == TIMEOUT;
    }
}
