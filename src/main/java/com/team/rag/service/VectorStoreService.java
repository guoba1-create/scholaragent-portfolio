package com.team.rag.service;

import com.team.rag.entity.ChunkSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.data.document.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量存储服务（Pinecone 访问层）。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>负责 Chunk 与 TextSegment 的互转</li>
 *   <li>负责向量入库（单条/批量）</li>
 *   <li>负责按 metadata 条件检索（userId / researchGroupId）</li>
 * </ul>
 *
 * <p>说明：本类不做业务编排（如意图识别、提示词组装），
 * 只做向量层 CRUD/查询封装，供上层服务调用。</p>
 */
/**
 * 向量存储服务（Pinecone 访问层）。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>负责 Chunk 与 TextSegment 的互转</li>
 *   <li>负责向量入库（单条/批量）</li>
 *   <li>负责按 metadata 条件检索（userId / researchGroupId）</li>
 * </ul>
 *
 * <p>说明：本类不做业务编排（如意图识别、提示词组装），
 * 只做向量层 CRUD/查询封装，供上层服务调用。</p>
 *
 * <h3>设计思想：</h3>
 * <p>采用"薄封装"模式，将 LangChain4j 的 EmbeddingStore API 封装为
 * 业务友好的 ChunkSegment 操作，屏蔽底层向量库的实现细节。
 * 这样做的好处是：如果未来需要切换向量库（如从 Pinecone 切换到 Milvus），
 * 只需要修改本类，上层业务代码无需改动。</p>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * AcademicPipelineService（学术文档处理流水线）
 *     └── VectorStoreService（本类：向量存储层）
 *         └── EmbeddingStore<TextSegment>（Pinecone 向量库）
 *
 * LiteratureDialogueService（文献对话服务）
 *     └── VectorStoreService（本类：向量检索）
 * </pre>
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    /** 文本嵌入模型，负责将文本转换为向量表示（如 OpenAI text-embedding-ada-002） */
    private final EmbeddingModel embeddingModel;

    /** 向量存储后端（Pinecone），负责存储和检索向量及其关联的 TextSegment */
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    public VectorStoreService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 存储单个 Chunk：内容 + metadata 一起向量化并写入向量库。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>将 ChunkSegment 转换为 LangChain4j 的 TextSegment（保留 metadata）</li>
     *   <li>调用 EmbeddingModel 将文本内容转换为向量</li>
     *   <li>将向量和 TextSegment 一起写入 EmbeddingStore</li>
     * </ol>
     *
     * @param chunk 待入库的文本块，包含内容和元数据（标题、章节、作者等）
     * @return 向量库返回的向量ID（Pinecone 生成的唯一标识）
     */
    public String storeChunk(ChunkSegment chunk) {
        log.debug("存储单个Chunk | title={}, section={}",
                chunk.getMetadata(ChunkSegment.MetadataKeys.TITLE),
                chunk.getMetadata(ChunkSegment.MetadataKeys.SECTION));

        // 将业务层的 ChunkSegment 转换为 LangChain4j 的 TextSegment
        // TextSegment = 文本内容 + Metadata 键值对
        TextSegment segment = convertToTextSegment(chunk);

        // 调用嵌入模型将文本转换为向量（如 1536 维的浮点数组）
        Embedding embedding = embeddingModel.embed(segment).content();

        // 将向量和原始文本一起写入 Pinecone，返回生成的向量 ID
        String id = embeddingStore.add(embedding, segment);
        log.debug("Chunk存储成功 | id={}", id);

        return id;
    }

    /**
     * 批量存储 Chunk。
     *
     * <p>实现上逐条写入并记录成功/失败数量，避免单条失败导致整批中断。
     * 这种"尽力而为"的策略在文档处理场景中更实用——
     * 一个 100 页的论文可能有 200 个 chunk，不应该因为其中 1 个 chunk 失败
     * 就丢弃其余 199 个。</p>
     *
     * @param chunks 待入库的文本块列表
     * @return 成功入库的向量ID列表
     */
    public List<String> storeChunks(List<ChunkSegment> chunks) {
        log.info("批量存储Chunks | 数量={}", chunks.size());

        List<String> ids = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        // 逐条写入，而非批量 API——因为 Pinecone 批量接口有 payload 大小限制
        // 且逐条写入可以在部分失败时继续处理剩余 chunk
        for (int i = 0; i < chunks.size(); i++) {
            try {
                ChunkSegment chunk = chunks.get(i);
                String id = storeChunk(chunk);
                ids.add(id);
                successCount++;

                // 每 10 条记录一次进度，方便监控大文档的处理进度
                if ((i + 1) % 10 == 0) {
                    log.info("存储进度 | 已完成={}/{}", i + 1, chunks.size());
                }
            } catch (Exception e) {
                // 单条失败不中断整批，记录错误继续处理
                log.error("存储Chunk失败 | index={}, error={}", i, e.getMessage());
                failCount++;
            }
        }

        log.info("批量存储完成 | 成功={}, 失败={}", successCount, failCount);
        return ids;
    }

    /**
     * 为用户场景写入 Chunk，并补齐隔离用 metadata。
     *
     * <p>这是多租户数据隔离的关键方法。每个 Chunk 都会被打上以下标签：</p>
     * <ul>
     *   <li>userId - 用户标识，用于个人记忆检索时的数据隔离</li>
     *   <li>researchGroupId - 研究组标识，用于团队知识库的文档隔离</li>
     *   <li>documentId - 文档标识，用于后续按文档删除向量</li>
     *   <li>chunkIndex - 块序号，用于文档诊断和排序</li>
     * </ul>
     *
     * <p>这些 metadata 标签是向量检索时"多租户隔离"的基础——
     * 检索时通过 MetadataFilter 确保用户只能访问自己有权限的数据。</p>
     *
     * @param chunks 待入库的文本块列表
     * @param userId 用户唯一标识
     * @param researchGroupId 研究组唯一标识
     * @return 成功入库的向量ID列表
     */
    public List<String> storeChunksForUser(List<ChunkSegment> chunks, String userId, String researchGroupId) {
        log.info("为用户存储Chunks | userId={}, researchGroupId={}, chunkCount={}",
                userId, researchGroupId, chunks.size());

        // 生成或复用 documentId，用于后续按文档维度管理向量（如删除）
        String batchDocumentId = resolveBatchDocumentId(chunks, researchGroupId);
        int chunkIndex = 0;
        for (ChunkSegment chunk : chunks) {
            // 强制写入隔离标识，确保每个 chunk 都有完整的租户信息
            chunk.addMetadata(ChunkSegment.MetadataKeys.USER_ID, userId);
            chunk.addMetadata(ChunkSegment.MetadataKeys.RESEARCH_GROUP_ID, researchGroupId);
            // 如果 chunk 已有 documentId（来自上游解析器），则复用；否则使用批量生成的 ID
            if (chunk.getMetadata(ChunkSegment.MetadataKeys.DOCUMENT_ID) == null) {
                chunk.addMetadata(ChunkSegment.MetadataKeys.DOCUMENT_ID, batchDocumentId);
            }
            // 记录块序号，便于文档诊断时定位缺失的 chunk
            if (chunk.getMetadata(ChunkSegment.MetadataKeys.CHUNK_INDEX) == null) {
                chunk.addMetadata(ChunkSegment.MetadataKeys.CHUNK_INDEX, String.valueOf(chunkIndex));
            }
            chunkIndex++;
        }

        return storeChunks(chunks);
    }

    /**
     * 解析或生成批量文档 ID。
     *
     * <p>优先复用 chunk 中已有的 documentId（来自上游 AcademicPdfParser 解析）；
     * 如果没有，则基于 researchGroupId + chunk 数量 + 首个 chunk 内容的哈希值
     * 生成一个确定性 ID。</p>
     *
     * <p>为什么用哈希而不是 UUID？因为同一份文档重复上传时，我们希望 documentId 一致，
     * 这样可以实现"幂等上传"——相同文档不会在向量库中产生重复数据。</p>
     */
    private String resolveBatchDocumentId(List<ChunkSegment> chunks, String researchGroupId) {
        // 优先复用上游已设置的 documentId
        for (ChunkSegment chunk : chunks) {
            Object existing = chunk.getMetadata(ChunkSegment.MetadataKeys.DOCUMENT_ID);
            if (existing != null && !existing.toString().isBlank()) {
                return existing.toString();
            }
        }
        // 生成确定性 ID：基于研究组 + chunk 数量 + 首个 chunk 内容的前 2048 字符
        // 这样同一份文档在同一个研究组下重复上传时，会得到相同的 documentId
        String firstContent = chunks.isEmpty() || chunks.get(0).getContent() == null
                ? ""
                : chunks.get(0).getContent();
        String seed = String.valueOf(researchGroupId) + "|" + chunks.size() + "|"
                + firstContent.substring(0, Math.min(firstContent.length(), 2048));
        return "doc_adhoc_" + sha256(seed).substring(0, 16);
    }

    /**
     * SHA-256 哈希计算，用于生成确定性文档 ID。
     *
     * @param value 待哈希的字符串
     * @return 64 位十六进制哈希字符串
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 Java 标准算法，理论上不会不可用
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 将业务层的 ChunkSegment 转换为 LangChain4j 的 TextSegment。
     *
     * <p>TextSegment 是 LangChain4j 的标准数据模型，包含：
     * <ul>
     *   <li>text() - 文本内容</li>
     *   <li>metadata() - 键值对形式的元数据</li>
     * </ul>
     */
    private TextSegment convertToTextSegment(ChunkSegment chunk) {
        // 处理 null 的 metadata，避免 NPE
        Metadata metadata = Metadata.from(chunk.getMetadata() != null ? chunk.getMetadata() : Map.of());
        return TextSegment.from(chunk.getContent(), metadata);
    }

    /**
     * 简化版相似度检索（无过滤条件，最低分数为 0）。
     *
     * <p>适用于不需要多租户隔离的场景（如系统内部诊断）。
     * 生产环境建议使用带 userId/researchGroupId 参数的重载版本。</p>
     *
     * @param query 用户查询文本
     * @param maxResults 最大返回结果数
     * @return 相似度最高的 ChunkSegment 列表
     */
    public List<ChunkSegment> searchSimilar(String query, int maxResults) {
        return searchSimilar(query, maxResults, 0.0, null, null);
    }

    /**
     * 相似度检索主入口。
     *
     * <p>这是五层检索链中"向量召回"环节的核心方法。
     * 检索流程：</p>
     * <ol>
     *   <li>对 query 进行 embedding，将自然语言转换为向量</li>
     *   <li>构造检索请求（topK + minScore + metadata filter）</li>
     *   <li>在 Pinecone 中执行近似最近邻搜索（ANN）</li>
     *   <li>将匹配结果转换为 ChunkSegment 返回上层</li>
     * </ol>
     *
     * <h3>参数说明：</h3>
     * <ul>
     *   <li>maxResults - 最大返回数，通常设为 5~10，过大会增加延迟和 token 消耗</li>
     *   <li>minScore - 最低相似度阈值（0~1），用于过滤低质量结果</li>
     *   <li>userId/researchGroupId - 多租户隔离标识，确保用户只能访问自己的数据</li>
     * </ul>
     *
     * @param query 用户查询文本
     * @param maxResults 最大返回结果数
     * @param minScore 最低相似度阈值（0.0~1.0）
     * @param userId 用户标识（可选，用于个人记忆隔离）
     * @param researchGroupId 研究组标识（可选，用于团队知识库隔离）
     * @return 相似度匹配的 ChunkSegment 列表，按相似度降序排列
     */
    public List<ChunkSegment> searchSimilar(String query, int maxResults, double minScore,
                                             String userId, String researchGroupId) {
        log.info("向量检索 | query长度={}, maxResults={}, minScore={}, userId={}, researchGroupId={}",
                query.length(), maxResults, minScore, userId, researchGroupId);

        // Step 1: 将用户查询文本转换为向量（与文档 chunk 使用相同的嵌入模型）
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // Step 2: 构造检索请求，包含向量、topK、最低分数和 metadata 过滤器
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(buildMetadataFilter(userId, researchGroupId))  // 多租户隔离的关键
                .build();

        // Step 3: 在 Pinecone 中执行向量检索
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);

        // Step 4: 将 LangChain4j 的 EmbeddingMatch 转换为业务层的 ChunkSegment
        List<ChunkSegment> results = searchResult.matches().stream()
                .map(this::convertToChunkSegment)
                .collect(Collectors.toList());

        log.info("检索完成 | 匹配数量={}", results.size());
        return results;
    }

    /**
     * 构建 metadata 过滤器（多租户隔离关键点）。
     *
     * <p>这是数据安全的核心方法。通过 metadata 过滤器，确保检索结果
     * 只包含当前用户/研究组有权限访问的数据。</p>
     *
     * <p>组合规则：</p>
     * <ul>
     *   <li>userId + researchGroupId 同时存在：AND 组合（最严格的隔离）</li>
     *   <li>仅 userId：按用户隔离（用于个人记忆检索）</li>
     *   <li>仅 researchGroupId：按研究组隔离（用于团队知识库检索）</li>
     *   <li>均为空：不加过滤（仅限内部诊断场景，绝不对外开放）</li>
     * </ul>
     *
     * @param userId 用户标识（可选）
     * @param researchGroupId 研究组标识（可选）
     * @return LangChain4j 的 Filter 对象，用于 Pinecone metadata 过滤
     */
    private Filter buildMetadataFilter(String userId, String researchGroupId) {
        // 无过滤条件：仅限内部诊断，生产环境应避免
        if (userId == null && researchGroupId == null) {
            return null;
        }

        // 双重过滤：最严格的隔离模式，同时按用户和研究组过滤
        if (userId != null && researchGroupId != null) {
            return Filter.and(
                    metadataKey(ChunkSegment.MetadataKeys.USER_ID).isEqualTo(userId),
                    metadataKey(ChunkSegment.MetadataKeys.RESEARCH_GROUP_ID).isEqualTo(researchGroupId)
            );
        } else if (userId != null) {
            // 仅按用户过滤：用于个人记忆检索场景
            return metadataKey(ChunkSegment.MetadataKeys.USER_ID).isEqualTo(userId);
        } else {
            // 仅按研究组过滤：用于团队知识库检索场景
            return metadataKey(ChunkSegment.MetadataKeys.RESEARCH_GROUP_ID).isEqualTo(researchGroupId);
        }
    }

    /**
     * 将 LangChain4j 的 EmbeddingMatch 转换为业务层的 ChunkSegment。
     *
     * <p>转换过程中会附加 similarityScore，方便上层服务
     * 根据相似度分数进行二次过滤或排序。</p>
     */
    private ChunkSegment convertToChunkSegment(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();

        // 重建 ChunkSegment：保留原始内容和 metadata
        ChunkSegment chunk = ChunkSegment.builder()
                .content(segment.text())
                .metadata(segment.metadata().toMap())
                .build();

        // 附加相似度分数，供上层服务使用（如 CRAG 判断、结果排序）
        chunk.addMetadata("similarityScore", match.score());

        return chunk;
    }

    /**
     * 按文档 ID 删除所有关联的向量。
     *
     * <p>实现策略：由于 Pinecone 不支持"按 metadata 条件删除"的原生 API，
     * 这里采用"先查后删"的方式——先检索出所有匹配的向量 ID，再批量删除。</p>
     *
     * <p>注意：这里使用了零向量作为查询向量，因为我们需要的是 metadata 过滤结果，
     * 而非相似度匹配。minScore 设为 0 确保不会过滤掉任何结果。</p>
     *
     * @param documentId 文档唯一标识
     */
    public void deleteByDocumentId(String documentId) {
        log.info("删除文档向量 | documentId={}", documentId);
        try {
            // 构造 metadata 过滤器，按 documentId 精确匹配
            Filter filter = metadataKey("documentId").isEqualTo(documentId);
            // 使用零向量作为查询向量——我们只需要 metadata 过滤，不需要相似度匹配
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(new Embedding(new float[1024]))  // 零向量占位
                    .maxResults(10000)  // 足够大的上限，确保能检索到所有关联向量
                    .minScore(0.0)  // 不过滤低分结果
                    .filter(filter)
                    .build();
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(searchRequest);
            // 提取所有匹配的向量 ID
            List<String> ids = result.matches().stream()
                    .map(EmbeddingMatch::embeddingId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            if (!ids.isEmpty()) {
                // 批量删除所有关联向量
                embeddingStore.removeAll(ids);
                log.info("文档向量已删除 | documentId={}, count={}", documentId, ids.size());
            } else {
                log.info("未找到文档向量 | documentId={}", documentId);
            }
        } catch (Exception e) {
            // 删除失败不抛异常，避免影响文档删除流程的其他步骤
            log.warn("删除文档向量失败 | documentId={}, error={}", documentId, e.getMessage());
        }
    }

    public void deleteByResearchGroup(String researchGroupId) {
        log.info("删除研究组向量 | researchGroupId={}", researchGroupId);
        log.warn("注意: 当前Pinecone实现不支持直接删除，需要通过metadata过滤后批量删除");
    }

    public int countByResearchGroup(String researchGroupId) {
        log.info("统计研究组向量数量 | researchGroupId={}", researchGroupId);
        log.warn("注意: 当前Pinecone实现不支持直接计数，返回-1");
        return -1;
    }
}
