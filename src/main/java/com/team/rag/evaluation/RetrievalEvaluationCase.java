package com.team.rag.evaluation;

import java.util.ArrayList;
import java.util.List;

/** One ranked retrieval result paired with human-verified relevant chunk IDs. */
public class RetrievalEvaluationCase {
    public String sampleId;
    public List<String> goldChunkIds = new ArrayList<>();
    public List<String> retrievedChunkIds = new ArrayList<>();
}
