package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档读取结果
 *
 * <p>封装从文档存储中读取页面或分块内容的结果。
 * 支持两种读取模式：按页码范围读取和按分块索引读取。</p>
 *
 * @see com.team.rag.service.DocumentReadService — 读取服务
 * @see com.team.rag.agent.tool.DocumentReadTool — Agent 工具
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentReadResult {
    /** 读取是否成功 */
    public boolean success;
    /** 文档 ID */
    public String documentId;
    /** 文件名 */
    public String filename;
    /** 研究组 ID */
    public String researchGroupId;
    /** 读取的起始页码（按页读取模式） */
    public Integer pageStart;
    /** 读取的结束页码（按页读取模式） */
    public Integer pageEnd;
    /** 读取的分块索引（按分块读取模式） */
    public Integer chunkIndex;
    /** 读取到的文本内容 */
    public String text;
    /** 分块引用列表（按页读取时返回该页的所有分块引用） */
    public List<DocumentChunkRef> chunkRefs = new ArrayList<>();
    /** 分块的完整元数据（按分块读取时返回） */
    public Map<String, Object> chunk;
    /** Structure-layer section metadata when the read result comes from structure.json. */
    public Map<String, Object> section;
    /** Structure-layer source path. */
    public String structurePath;
    /** 失败时的错误信息 */
    public String errorMessage;

    /** 创建失败结果的工厂方法 */
    public static DocumentReadResult failure(String documentId, String message) {
        DocumentReadResult result = new DocumentReadResult();
        result.success = false;
        result.documentId = documentId;
        result.errorMessage = message;
        return result;
    }
}
