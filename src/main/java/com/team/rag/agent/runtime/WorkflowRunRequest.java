package com.team.rag.agent.runtime;

/**
 * Unified workflow run request for `/api/workflows/run`.
 */
public class WorkflowRunRequest {

    /** @deprecated Use mode: auto / quick / evidence / deep. */
    @Deprecated
    public String engine = "auto";

    /** Server-derived caller user id. External clients must not rely on this field. */
    public String userId;

    /** Server-derived knowledge-space id, kept under the legacy field name internally. */
    public String researchGroupId;

    /** Conversation id for context isolation. */
    public String conversationId;

    /** User message payload. */
    public String message;

    /** Source channel tag: web / telegram / wechat / api. Set by the adapter. */
    public String source = "web";

    /** Public execution mode: auto / quick / evidence / deep. */
    public String mode = "auto";

    /** Internal/admin Agent profile override. Normal user requests use auto. */
    public String profile = "auto";

    /**
     * @deprecated Runtime target is inferred from route decision and feature flags.
     */
    @Deprecated
    public String runtimeMode = "auto";
}
