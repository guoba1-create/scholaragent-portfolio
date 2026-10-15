package com.team.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 运行时配置属性类
 *
 * <p>
 * 管理 RAG 检索增强生成流程中的各项功能开关和性能参数。
 * 所有配置项均可通过 application.yml 中 team-rag.runtime 前缀动态调整，
 * 无需重新编译即可在运行时灵活控制 RAG 管线行为。
 *
 * <p>
 * 功能模块包括：
 * <ul>
 * <li>查询重写（Query Rewrite）：对用户原始查询进行改写，提升检索召回率</li>
 * <li>重排序（Rerank）：对检索结果进行二次精排，提升相关性</li>
 * <li>答案缓存（Answer Cache）：缓存已生成的回答，避免重复调用 LLM</li>
 * <li>并行检索（Parallel Retrieval）：多路召回并行执行，降低延迟</li>
 * <li>混合稀疏检索（Hybrid Sparse）：结合稠密向量与 Pinecone 稀疏向量检索</li>
 * </ul>
 *
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * team-rag:
 *   runtime:
 *     query-rewrite-enabled: true
 *     rerank-enabled: true
 *     rerank-cache-enabled: true
 *     answer-cache-enabled: true
 *     parallel-retrieval-enabled: true
 *     hybrid-sparse-enabled: true
 *     query-rewrite-min-length: 6
 *     sparse-top-k: 12
 *     retrieval-executor-threads: 4
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "team-rag.runtime")
public class RagRuntimeProperties {

    /**
     * 是否启用查询重写功能
     * 开启后会使用 LLM 对用户原始查询进行改写和扩展，提升检索召回率
     * 默认值：true
     */
    private boolean queryRewriteEnabled = true;

    /**
     * 是否启用重排序功能
     * 开启后会对初步检索结果使用当前 LLM Rerank 服务进行二次精排
     * 默认值：true
     */
    private boolean rerankEnabled = true;

    /**
     * 是否启用重排序缓存
     * 开启后会缓存 Rerank 结果，避免对相同查询-文档对重复计算
     * 默认值：true
     */
    private boolean rerankCacheEnabled = true;

    /**
     * 是否启用答案缓存
     * 开启后会将 LLM 生成的回答缓存到 Redis，相同查询直接返回缓存结果
     * 默认值：true
     */
    private boolean answerCacheEnabled = true;

    /**
     * 是否启用并行检索
     * 开启后文献检索与个人记忆检索并行执行，降低整体延迟。
     * 稠密/稀疏两路召回在 RetrievalFormattingService 内仍按确定顺序执行并做 RRF 融合。
     * 默认值：true
     */
    private boolean parallelRetrievalEnabled = true;

    /**
     * 是否启用混合稀疏检索
     * 开启后在稠密向量检索的基础上，额外使用 Pinecone 稀疏索引做双路召回
     * 默认值：true
     */
    private boolean hybridSparseEnabled = true;

    /**
     * 查询重写最小字符长度
     * 用户输入长度低于此阈值时跳过查询重写，避免对过短查询进行无意义改写
     * 默认值：6
     */
    private int queryRewriteMinLength = 6;

    /**
     * 稀疏检索返回的最大结果数（Top-K）
     * 从 Pinecone 稀疏索引中检索的最大文档片段数量
     * 默认值：12
     */
    private int sparseTopK = 12;

    /**
     * 并行检索线程池大小
     * 控制多路并行检索时的并发线程数，值越大并行度越高但资源消耗也越大
     * 默认值：4
     */
    private int retrievalExecutorThreads = 4;

    // ======================== Getter / Setter ========================

    public boolean isQueryRewriteEnabled() {
        return queryRewriteEnabled;
    }

    public void setQueryRewriteEnabled(boolean queryRewriteEnabled) {
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public boolean isRerankCacheEnabled() {
        return rerankCacheEnabled;
    }

    public void setRerankCacheEnabled(boolean rerankCacheEnabled) {
        this.rerankCacheEnabled = rerankCacheEnabled;
    }

    public boolean isAnswerCacheEnabled() {
        return answerCacheEnabled;
    }

    public void setAnswerCacheEnabled(boolean answerCacheEnabled) {
        this.answerCacheEnabled = answerCacheEnabled;
    }

    public boolean isParallelRetrievalEnabled() {
        return parallelRetrievalEnabled;
    }

    public void setParallelRetrievalEnabled(boolean parallelRetrievalEnabled) {
        this.parallelRetrievalEnabled = parallelRetrievalEnabled;
    }

    public boolean isHybridSparseEnabled() {
        return hybridSparseEnabled;
    }

    public void setHybridSparseEnabled(boolean hybridSparseEnabled) {
        this.hybridSparseEnabled = hybridSparseEnabled;
    }

    public int getQueryRewriteMinLength() {
        return queryRewriteMinLength;
    }

    public void setQueryRewriteMinLength(int queryRewriteMinLength) {
        this.queryRewriteMinLength = queryRewriteMinLength;
    }

    public int getSparseTopK() {
        return sparseTopK;
    }

    public void setSparseTopK(int sparseTopK) {
        this.sparseTopK = sparseTopK;
    }

    public int getRetrievalExecutorThreads() {
        return retrievalExecutorThreads;
    }

    public void setRetrievalExecutorThreads(int retrievalExecutorThreads) {
        this.retrievalExecutorThreads = retrievalExecutorThreads;
    }
}
