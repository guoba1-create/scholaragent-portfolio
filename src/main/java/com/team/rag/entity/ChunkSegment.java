package com.team.rag.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档分块实体。
 */
public class ChunkSegment {

    private String content;
    private Map<String, Object> metadata;

    public ChunkSegment() {
        this(null, new HashMap<>());
    }

    public ChunkSegment(String content, Map<String, Object> metadata) {
        this.content = content;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public static ChunkSegmentBuilder builder() {
        return new ChunkSegmentBuilder();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getMetadata() {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public void addMetadata(String key, Object value) {
        getMetadata().put(key, value);
    }

    public Object getMetadata(String key) {
        return getMetadata().get(key);
    }

    public static final class ChunkSegmentBuilder {
        private String content;
        private Map<String, Object> metadata = new HashMap<>();

        private ChunkSegmentBuilder() {
        }

        public ChunkSegmentBuilder content(String content) {
            this.content = content;
            return this;
        }

        public ChunkSegmentBuilder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            return this;
        }

        public ChunkSegment build() {
            return new ChunkSegment(content, metadata);
        }
    }

    public static final class MetadataKeys {
        public static final String TITLE = "title";
        public static final String AUTHORS = "authors";
        public static final String YEAR = "year";
        public static final String SECTION = "section";
        public static final String SOURCE_PATH = "sourcePath";
        public static final String PAGE_NUMBER = "pageNumber";
        public static final String CHUNK_INDEX = "chunkIndex";
        public static final String IS_TABLE = "isTable";
        public static final String RESEARCH_GROUP_ID = "researchGroupId";
        public static final String USER_ID = "userId";
        public static final String DOCUMENT_ID = "documentId";

        private MetadataKeys() {
        }
    }
}
