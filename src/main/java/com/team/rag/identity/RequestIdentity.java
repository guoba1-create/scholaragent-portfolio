package com.team.rag.identity;

/** Server-trusted identity used to isolate data and gate administrative diagnostics. */
public record RequestIdentity(String userId, String knowledgeSpaceId, String source, boolean admin) {
}
