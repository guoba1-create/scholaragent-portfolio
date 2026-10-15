package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structure-first representation of an uploaded academic document.
 *
 * <p>This file is the document structure layer. Pinecone keeps the searchable
 * vectors, while this model keeps the section tree, ordered chunks, and chunk
 * adjacency needed for read-back and citation context.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentStructure {

    public String documentId;
    public String researchGroupId;
    public String filename;
    public String title;
    public List<String> authors = new ArrayList<>();
    public String parser;
    public String parserVersion;
    public boolean referencesRemoved = true;
    public int sectionCount;
    public int chunkCount;
    public int pageCount;
    public List<DocumentSection> sections = new ArrayList<>();
    public List<DocumentChunk> chunks = new ArrayList<>();
    public Instant createdAt = Instant.now();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentSection {
        public String sectionId;
        public String parentSectionId;
        public String title;
        public String normalizedTitle;
        public String sectionPath;
        public int level;
        public int order;
        public Integer pageStart;
        public Integer pageEnd;
        public List<Integer> chunkIndexes = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentChunk {
        public String chunkId;
        public String documentId;
        public String sectionId;
        public String title;
        public String sectionTitle;
        public String sectionPath;
        public String text;
        public int chunkIndex;
        public int indexInSection;
        public Integer pageStart;
        public Integer pageEnd;
        public String prevChunkId;
        public String nextChunkId;
        public int tokenCount;
        public Map<String, Object> metadata = new LinkedHashMap<>();
    }
}
