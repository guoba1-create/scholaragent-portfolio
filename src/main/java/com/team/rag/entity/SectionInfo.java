package com.team.rag.entity;

/**
 * 文档章节信息。
 */
public class SectionInfo {

    private String sectionName;
    private String normalizedSectionName;
    private int startIndex;
    private int endIndex;
    private int level;
    private String content;

    public SectionInfo() {
    }

    public SectionInfo(
            String sectionName,
            String normalizedSectionName,
            int startIndex,
            int endIndex,
            int level,
            String content) {
        this.sectionName = sectionName;
        this.normalizedSectionName = normalizedSectionName;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.level = level;
        this.content = content;
    }

    public static SectionInfoBuilder builder() {
        return new SectionInfoBuilder();
    }

    public String getSectionName() {
        return sectionName;
    }

    public void setSectionName(String sectionName) {
        this.sectionName = sectionName;
    }

    public String getNormalizedSectionName() {
        return normalizedSectionName;
    }

    public void setNormalizedSectionName(String normalizedSectionName) {
        this.normalizedSectionName = normalizedSectionName;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public void setStartIndex(int startIndex) {
        this.startIndex = startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public void setEndIndex(int endIndex) {
        this.endIndex = endIndex;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isReferencesSection() {
        if (sectionName == null) {
            return false;
        }
        String lower = sectionName.toLowerCase().trim();
        return lower.equals("references")
                || lower.equals("bibliography")
                || lower.startsWith("reference")
                || lower.startsWith("bibliograph");
    }

    public static final class SectionInfoBuilder {
        private String sectionName;
        private String normalizedSectionName;
        private int startIndex;
        private int endIndex;
        private int level;
        private String content;

        private SectionInfoBuilder() {
        }

        public SectionInfoBuilder sectionName(String sectionName) {
            this.sectionName = sectionName;
            return this;
        }

        public SectionInfoBuilder normalizedSectionName(String normalizedSectionName) {
            this.normalizedSectionName = normalizedSectionName;
            return this;
        }

        public SectionInfoBuilder startIndex(int startIndex) {
            this.startIndex = startIndex;
            return this;
        }

        public SectionInfoBuilder endIndex(int endIndex) {
            this.endIndex = endIndex;
            return this;
        }

        public SectionInfoBuilder level(int level) {
            this.level = level;
            return this;
        }

        public SectionInfoBuilder content(String content) {
            this.content = content;
            return this;
        }

        public SectionInfo build() {
            return new SectionInfo(sectionName, normalizedSectionName, startIndex, endIndex, level, content);
        }
    }
}
