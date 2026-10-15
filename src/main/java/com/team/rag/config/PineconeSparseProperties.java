package com.team.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Pinecone 稀疏向量索引配置属性类
 *
 * <p>
 * 管理 Pinecone 稀疏向量索引的连接、索引创建和数据写入参数。
 * 稀疏向量用于关键词精确召回，与稠密向量（语义检索）配合形成双路混合检索策略。
 *
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * pinecone:
 *   sparse:
 *     enabled: true
 *     index-name: replace-me
 *     host: https://example.invalid
 *     auto-resolve-host: true
 *     auto-create-index: false
 *     cloud: aws
 *     region: us-east-1
 *     api-version: "2026-04"
 *     namespace-prefix: rg
 *     upsert-batch-size: 96
 *     model: pinecone-sparse-english-v0
 *     text-field: text
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "pinecone.sparse")
public class PineconeSparseProperties {

    /**
     * 是否启用 Pinecone 稀疏向量索引
     * 设为 false 时将跳过稀疏检索，仅使用稠密向量检索
     * 默认值：true
     */
    private boolean enabled = true;

    /**
     * 稀疏向量索引名称
     * 与稠密向量索引分开存储，避免相互干扰
     * 默认值："replace-me"
     */
    private String indexName = "replace-me";

    /**
     * Pinecone 稀疏索引的主机地址（HTTPS URL）
     * 通常在创建索引后由 Pinecone 分配，格式如 https://example.invalid
     * 配置项：pinecone.sparse.host
     */
    private String host;

    /**
     * 是否自动解析索引主机地址
     * 开启后会通过 Pinecone API 自动获取索引的 host，无需手动配置
     * 默认值：true
     */
    private boolean autoResolveHost = true;

    /**
     * 是否自动创建索引
     * 开启后如果目标索引不存在，会自动调用 Pinecone API 创建
     * 默认值：false（生产环境建议手动创建索引）
     */
    private boolean autoCreateIndex = false;

    /**
     * 云服务商类型
     * 可选值：aws、gcp、azure
     * 默认值："aws"
     */
    private String cloud = "aws";

    /**
     * 部署区域
     * 与 cloud 配合使用，决定索引的物理位置
     * 默认值："us-east-1"
     */
    private String region = "us-east-1";

    /**
     * Pinecone API 版本号
     * 用于指定调用的 API 版本，确保兼容性
     * 默认值："2026-04"
     */
    private String apiVersion = "2026-04";

    /**
     * 命名空间前缀
     * 用于在稀疏索引中区分不同租户或用户组的数据
     * 实际命名空间 = 前缀 + 具体标识符
     * 默认值："rg"
     */
    private String namespacePrefix = "rg";

    /**
     * 批量写入（Upsert）的批次大小
     * 控制每次向 Pinecone 写入向量的数量，过大会导致请求超时，过小会增加网络开销
     * 默认值：96
     */
    private int upsertBatchSize = 96;

    /**
     * 稀疏向量编码模型名称
     * 使用 Pinecone 官方提供的稀疏编码模型将文本转换为稀疏向量
     * 默认值："pinecone-sparse-english-v0"
     */
    private String model = "pinecone-sparse-english-v0";

    /**
     * 文本字段名称
     * 指定向量元数据中存储原始文本的字段名
     * 默认值："text"
     */
    private String textField = "text";

    // ======================== Getter / Setter ========================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public boolean isAutoResolveHost() {
        return autoResolveHost;
    }

    public void setAutoResolveHost(boolean autoResolveHost) {
        this.autoResolveHost = autoResolveHost;
    }

    public boolean isAutoCreateIndex() {
        return autoCreateIndex;
    }

    public void setAutoCreateIndex(boolean autoCreateIndex) {
        this.autoCreateIndex = autoCreateIndex;
    }

    public String getCloud() {
        return cloud;
    }

    public void setCloud(String cloud) {
        this.cloud = cloud;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getNamespacePrefix() {
        return namespacePrefix;
    }

    public void setNamespacePrefix(String namespacePrefix) {
        this.namespacePrefix = namespacePrefix;
    }

    public int getUpsertBatchSize() {
        return upsertBatchSize;
    }

    public void setUpsertBatchSize(int upsertBatchSize) {
        this.upsertBatchSize = upsertBatchSize;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTextField() {
        return textField;
    }

    public void setTextField(String textField) {
        this.textField = textField;
    }
}
