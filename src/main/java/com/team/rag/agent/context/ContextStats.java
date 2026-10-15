package com.team.rag.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextStats {
    public int originalTokens;
    public int finalTokens;
    public double compressionRatio;
    public int originalSegments;
    public int finalSegments;
    public int citationsBefore;
    public int citationsAfter;
    public boolean citationsPreserved;
    public boolean modelCompressionUsed;
    public Map<String, Integer> tokensByType = new LinkedHashMap<>();
    public List<String> droppedSegments = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
}
