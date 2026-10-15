package com.team.rag.monitoring;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pinecone 向量数据库健康检查指示器
 *
 * <p>
 * 实现 Spring Boot Actuator 的 HealthIndicator 接口，
 * 通过向 Pinecone 发送一个探测查询来验证向量存储服务是否可用。
 *
 * <p>
 * 探测策略：构造一个 1024 维的探测向量（仅第一个维度为 1.0，其余为 0），
 * 执行一次 Top-1 相似度搜索。如果能正常返回结果（即使结果集为空），
 * 则说明 Pinecone 连接正常。
 *
 * <p>
 * 检查结果会暴露在 /actuator/health 端点中，包含以下信息：
 * <ul>
 * <li>index           - 目标索引名称（replace-me）</li>
 * <li>probeResultCount - 探测查询返回的结果数量</li>
 * <li>error           - 失败时的错误信息（仅在 DOWN 状态时出现）</li>
 * </ul>
 */
@Component
public class PineconeHealthIndicator implements HealthIndicator {

    /** 向量存储实例，由 Spring 自动注入 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 构造函数，注入向量存储
     *
     * @param embeddingStore Pinecone 或内存向量存储实例
     */
    public PineconeHealthIndicator(EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingStore = embeddingStore;
    }

    /**
     * 执行健康检查
     *
     * <p>
     * 使用探测向量对 Pinecone 进行一次最小化查询，
     * 验证索引连接和搜索功能是否正常。
     *
     * @return Health 健康状态（UP 或 DOWN）
     */
    @Override
    public Health health() {
        try {
            // 构造 1024 维探测向量，仅第一个维度为 1.0
            float[] probe = new float[1024];
            probe[0] = 1.0f;

            // 构建探测搜索请求，只返回 1 条结果
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(new Embedding(probe))
                    .maxResults(1)
                    .build();

            // 执行搜索，验证 Pinecone 连通性
            List<EmbeddingMatch<TextSegment>> results = embeddingStore.search(request).matches();

            return Health.up()
                    .withDetail("index", "replace-me")          // 索引名称
                    .withDetail("probeResultCount", results.size()) // 探测结果数
                    .build();
        } catch (Exception e) {
            // 捕获所有异常（连接超时、认证失败、索引不存在等），标记为 DOWN
            return Health.down(e)
                    .withDetail("index", "replace-me")
                    .withDetail("error", e.getMessage())            // 错误信息
                    .build();
        }
    }
}
