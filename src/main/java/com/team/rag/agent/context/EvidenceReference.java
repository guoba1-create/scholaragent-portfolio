package com.team.rag.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceReference {
    public String citationId;
    public String sourceType;
    public String documentId;
    public String chunkId;
    public String title;
    public String section;
    public Integer pageNumber;
    public Integer chunkIndex;
    public String quote;
    public Double score;
    public String url;

    public String stableIdentity() {
        return String.join("|",
                safe(citationId), safe(sourceType), safe(documentId), safe(chunkId), safe(url));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
