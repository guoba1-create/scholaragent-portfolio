package com.team.rag.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 学术文档实体。
 */
public class AcademicDocument {

    private String title;
    private String authors;
    private Integer year;
    private String sourcePath;
    private List<ChunkSegment> chunks;
    private String fullText;
    private List<TableData> tables;

    public AcademicDocument() {
        this(null, null, null, null, new ArrayList<>(), null, new ArrayList<>());
    }

    public AcademicDocument(
            String title,
            String authors,
            Integer year,
            String sourcePath,
            List<ChunkSegment> chunks,
            String fullText,
            List<TableData> tables) {
        this.title = title;
        this.authors = authors;
        this.year = year;
        this.sourcePath = sourcePath;
        this.chunks = chunks != null ? new ArrayList<>(chunks) : new ArrayList<>();
        this.fullText = fullText;
        this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
    }

    public static AcademicDocumentBuilder builder() {
        return new AcademicDocumentBuilder();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthors() {
        return authors;
    }

    public void setAuthors(String authors) {
        this.authors = authors;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public List<ChunkSegment> getChunks() {
        if (chunks == null) {
            chunks = new ArrayList<>();
        }
        return chunks;
    }

    public void setChunks(List<ChunkSegment> chunks) {
        this.chunks = chunks != null ? new ArrayList<>(chunks) : new ArrayList<>();
    }

    public String getFullText() {
        return fullText;
    }

    public void setFullText(String fullText) {
        this.fullText = fullText;
    }

    public List<TableData> getTables() {
        if (tables == null) {
            tables = new ArrayList<>();
        }
        return tables;
    }

    public void setTables(List<TableData> tables) {
        this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
    }

    public static final class AcademicDocumentBuilder {
        private String title;
        private String authors;
        private Integer year;
        private String sourcePath;
        private List<ChunkSegment> chunks = new ArrayList<>();
        private String fullText;
        private List<TableData> tables = new ArrayList<>();

        private AcademicDocumentBuilder() {
        }

        public AcademicDocumentBuilder title(String title) {
            this.title = title;
            return this;
        }

        public AcademicDocumentBuilder authors(String authors) {
            this.authors = authors;
            return this;
        }

        public AcademicDocumentBuilder year(Integer year) {
            this.year = year;
            return this;
        }

        public AcademicDocumentBuilder sourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        public AcademicDocumentBuilder chunks(List<ChunkSegment> chunks) {
            this.chunks = chunks != null ? new ArrayList<>(chunks) : new ArrayList<>();
            return this;
        }

        public AcademicDocumentBuilder fullText(String fullText) {
            this.fullText = fullText;
            return this;
        }

        public AcademicDocumentBuilder tables(List<TableData> tables) {
            this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
            return this;
        }

        public AcademicDocument build() {
            return new AcademicDocument(title, authors, year, sourcePath, chunks, fullText, tables);
        }
    }

    public static class TableData {
        private int pageNumber;
        private String markdownContent;
        private String sectionHint;

        public TableData() {
        }

        public TableData(int pageNumber, String markdownContent, String sectionHint) {
            this.pageNumber = pageNumber;
            this.markdownContent = markdownContent;
            this.sectionHint = sectionHint;
        }

        public static TableDataBuilder builder() {
            return new TableDataBuilder();
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public void setPageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
        }

        public String getMarkdownContent() {
            return markdownContent;
        }

        public void setMarkdownContent(String markdownContent) {
            this.markdownContent = markdownContent;
        }

        public String getSectionHint() {
            return sectionHint;
        }

        public void setSectionHint(String sectionHint) {
            this.sectionHint = sectionHint;
        }

        public static final class TableDataBuilder {
            private int pageNumber;
            private String markdownContent;
            private String sectionHint;

            private TableDataBuilder() {
            }

            public TableDataBuilder pageNumber(int pageNumber) {
                this.pageNumber = pageNumber;
                return this;
            }

            public TableDataBuilder markdownContent(String markdownContent) {
                this.markdownContent = markdownContent;
                return this;
            }

            public TableDataBuilder sectionHint(String sectionHint) {
                this.sectionHint = sectionHint;
                return this;
            }

            public TableData build() {
                return new TableData(pageNumber, markdownContent, sectionHint);
            }
        }
    }
}
