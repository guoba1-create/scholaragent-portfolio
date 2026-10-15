package com.team.rag.agent.context;

import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.agent.runtime.ToolObservation;

import java.util.ArrayList;
import java.util.List;

public class ContextCompileRequest {
    public String workflowId;
    public String strategy;
    public String systemPolicy;
    public String userQuery;
    public List<String> recentConversation = new ArrayList<>();
    public String conversationSummary;
    public MemoryRecallRecord memoryRecall;
    public List<EvidenceReference> evidenceRefs = new ArrayList<>();
    public String planState;
    public String workingMemory;
    public List<ToolObservation> toolObservations = new ArrayList<>();
    public List<String> diagnostics = new ArrayList<>();
    public Integer maxPromptTokens;
    public Integer maxOutputTokens;
    public boolean allowModelCompression = true;
}
