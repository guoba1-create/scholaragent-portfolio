package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Full tool output retained outside prompts for bounded audit and debugging. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawToolPayload {

    public String payloadRef;
    public String workflowId;
    public String toolName;
    public String sha256;
    public String contentType = "application/json";
    public String payload;
    public Instant createdAt = Instant.now();
    public Instant expiresAt;
}
