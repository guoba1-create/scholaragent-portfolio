package com.team.rag.evaluation;

import java.util.ArrayList;
import java.util.List;

/** Versioned offline evaluation sample loaded from JSONL. */
public class EvaluationSample {
    public String id;
    public String query;
    public String expectedIntent;
    public String expectedStrategy;
    public String expectedWorkflowTemplate;
    public List<String> goldDocumentIds = new ArrayList<>();
    public List<String> goldChunkIds = new ArrayList<>();
    public List<String> retrievedChunkIds = new ArrayList<>();
    public List<String> keyFacts = new ArrayList<>();
    public List<String> allowedCitations = new ArrayList<>();
    public List<String> forbiddenClaims = new ArrayList<>();
    public List<String> tags = new ArrayList<>();
}
