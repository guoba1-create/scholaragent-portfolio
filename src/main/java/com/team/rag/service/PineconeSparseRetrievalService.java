package com.team.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.config.PineconeSparseProperties;
import com.team.rag.entity.DocumentManifest;
import com.team.rag.entity.RetrievalHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pinecone 稀疏检索服务（Pinecone Sparse Retrieval Service）。
 *
 * <p>职责：封装 Pinecone 的稀疏向量检索 API，提供词汇级别的召回能力。
 * 是 hybrid retrieval 中 sparse lane 的核心组件。</p>
 *
 * <h3>设计思想：</h3>
 * <p>向量检索（稠密检索）擅长语义匹配，稀疏检索擅长标题词、缩写词、专有名词等精确命中。
 * 两路召回结合后，既保留语义能力，也补齐精确匹配能力：</p>
 * <ul>
 *   <li>稠密检索（Dense）：语义相似，如"注意力机制"匹配"attention mechanism"</li>
 *   <li>稀疏检索（Sparse）：精确匹配，如"Transformer"只匹配包含"Transformer"的文档</li>
 * </ul>
 *
 * <p>Pinecone 的稀疏向量 API 使用托管的 sparse embedding / sparse index 能力，
 * 无需额外部署 Elasticsearch 或 Lucene。</p>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * RetrievalFormattingService（检索格式化服务）
 *     ├── VectorStoreService（稠密向量检索）
 *     └── PineconeSparseRetrievalService（本类：稀疏向量检索）
 * </pre>
 */
@Service
public class PineconeSparseRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(PineconeSparseRetrievalService.class);

    /** 返回字段列表（Pinecone 查询时指定需要返回哪些字段） */
    private static final List<String> RETURN_FIELDS = List.of(
            "title",
            "filename",
            "section",
            "text",
            "documentId",
            "documentHash",
            "chunkId",
            "sectionId",
            "sectionPath",
            "pageNumber",
            "pageStart",
            "pageEnd",
            "chunkIndex",
            "indexInSection",
            "prevChunkId",
            "nextChunkId",
            "sourcePath",
            "storagePath",
            "researchGroupId"
    );

    /** Pinecone API Key（从配置文件读取） */
    private final String pineconeApiKey;

    /** Pinecone 稀疏检索配置属性 */
    private final PineconeSparseProperties properties;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端（用于调用 Pinecone REST API） */
    private final WebClient webClient;

    /** 已解析的 Pinecone 数据主机地址（缓存） */
    private volatile String resolvedHost;

    /** 会话级禁用标志（API 调用失败时自动禁用，避免持续报错） */
    private volatile boolean disabledForSession;

    public PineconeSparseRetrievalService(@Value("${pinecone.api-key:}") String pineconeApiKey,
                                          PineconeSparseProperties properties,
                                          ObjectMapper objectMapper,
                                          WebClient.Builder builder) {
        this.pineconeApiKey = pineconeApiKey;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.build();
    }

    /**
     * 批量上传记录到 Pinecone 稀疏索引。
     *
     * <p>使用 NDJSON 格式批量上传，每批大小由配置决定。
     * NDJSON（Newline Delimited JSON）是 Pinecone 稀疏 API 的要求格式。</p>
     *
     * @param researchGroupId 研究组标识（用于命名空间隔离）
     * @param records 待上传的记录列表
     */
    public void upsertRecords(String researchGroupId, List<Map<String, Object>> records) {
        if (!isActive() || isBlank(researchGroupId) || records == null || records.isEmpty()) {
            return;
        }
        String host = resolveDataHost();
        if (host == null) {
            return;
        }

        // 按研究组隔离命名空间
        String namespace = namespaceFor(researchGroupId);
        int batchSize = Math.max(1, properties.getUpsertBatchSize());
        // 分批上传
        for (int start = 0; start < records.size(); start += batchSize) {
            int end = Math.min(records.size(), start + batchSize);
            List<Map<String, Object>> batch = records.subList(start, end);
            // 转换为 NDJSON 格式（每行一个 JSON 对象）
            String ndjson = batch.stream()
                    .map(this::toJson)
                    .collect(Collectors.joining("\n"));
            try {
                JsonNode response = postNdjson(host + "/records/namespaces/" + namespace + "/upsert", ndjson);
                int upserted = response.path("upsertedCount").asInt(batch.size());
                log.info("Pinecone sparse upsert completed | namespace={} | batchSize={} | upserted={}",
                        namespace, batch.size(), upserted);
            } catch (Exception e) {
                log.warn("Pinecone sparse upsert failed | namespace={} | error={}", namespace, sanitize(e.getMessage()));
                // 上传失败时禁用服务，避免持续报错
                disableIfRecoverable();
                return;
            }
        }
    }

    /**
     * 稀疏向量检索。
     *
     * <p>使用 Pinecone 的 sparse index 做词汇级别检索。
     * 该结果会与稠密向量结果一起参与 hybrid 融合。</p>
     *
     * @param query 查询文本
     * @param researchGroupId 研究组标识（用于命名空间隔离）
     * @param topK 最大返回数
     * @return 检索结果列表
     */
    public List<RetrievalHit> searchLiterature(String query, String researchGroupId, int topK) {
        if (!isActive() || isBlank(query) || isBlank(researchGroupId)) {
            return List.of();
        }
        String host = resolveDataHost();
        if (host == null) {
            return List.of();
        }

        // 构造检索请求
        String namespace = namespaceFor(researchGroupId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", Map.of(
                "inputs", Map.of("text", query),
                "top_k", Math.max(1, topK)
        ));
        request.put("fields", RETURN_FIELDS);

        try {
            // 调用 Pinecone 稀疏检索 API
            JsonNode response = postJson(host + "/records/namespaces/" + namespace + "/search", request);
            // 兼容不同版本的响应格式
            JsonNode matches = response.path("result").path("hits");
            if (!matches.isArray() || matches.isEmpty()) {
                matches = response.path("matches");
            }
            if (!matches.isArray() || matches.isEmpty()) {
                return List.of();
            }

            // 转换为 RetrievalHit 列表
            List<RetrievalHit> hits = new ArrayList<>();
            for (JsonNode node : matches) {
                RetrievalHit hit = toRetrievalHit(node);
                if (hit != null) {
                    hits.add(hit);
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("Pinecone sparse search failed | namespace={} | error={}", namespace, sanitize(e.getMessage()));
            disableIfRecoverable();
            return List.of();
        }
    }

    /**
     * 删除文档的所有稀疏向量记录。
     *
     * <p>根据文档清单中的 chunkRefs 或 chunkCount 构建记录 ID 列表，
     * 然后批量删除。</p>
     *
     * @param manifest 文档清单
     */
    public void deleteDocument(DocumentManifest manifest) {
        if (!isActive() || manifest == null || isBlank(manifest.documentId) || isBlank(manifest.researchGroupId)) {
            return;
        }
        String host = resolveDataHost();
        if (host == null) {
            return;
        }

        // 构建记录 ID 列表
        List<String> ids = buildRecordIds(manifest);
        if (ids.isEmpty()) {
            return;
        }

        // 批量删除
        Map<String, Object> body = Map.of(
                "namespace", namespaceFor(manifest.researchGroupId),
                "ids", ids
        );
        try {
            postJson(host + "/vectors/delete", body);
            log.info("Pinecone sparse delete completed | documentId={} | ids={}", manifest.documentId, ids.size());
        } catch (Exception e) {
            log.warn("Pinecone sparse delete failed | documentId={} | error={}",
                    manifest.documentId, sanitize(e.getMessage()));
            disableIfRecoverable();
        }
    }

    /**
     * 生成稀疏向量记录的 ID。
     *
     * <p>格式：documentId::sparse::chunkIndex
     * 使用双冒号分隔，避免与文档 ID 中的字符冲突。</p>
     *
     * @param documentId 文档标识
     * @param chunkIndex 块索引
     * @return 记录 ID
     */
    public String recordId(String documentId, Integer chunkIndex) {
        int safeChunkIndex = chunkIndex == null ? 0 : Math.max(0, chunkIndex);
        return documentId + "::sparse::" + safeChunkIndex;
    }

    /**
     * 获取文本字段名（配置中的 text_field）。
     */
    public String textField() {
        return properties.getTextField();
    }

    /**
     * 将 Pinecone 响应转换为 RetrievalHit。
     *
     * <p>兼容不同版本的 Pinecone API 响应格式：
     * <ul>
     *   <li>新版本：result.hits[].fields</li>
     *   <li>旧版本：matches[].metadata</li>
     * </ul>
     */
    private RetrievalHit toRetrievalHit(JsonNode match) {
        if (match == null || match.isMissingNode()) {
            return null;
        }
        // 兼容不同版本的字段名
        JsonNode fields = match.path("fields");
        if ((fields == null || fields.isMissingNode() || fields.isEmpty()) && match.has("metadata")) {
            fields = match.path("metadata");
        }
        if (fields == null || fields.isMissingNode() || fields.isEmpty()) {
            fields = match;
        }

        RetrievalHit hit = new RetrievalHit();
        hit.embeddingId = prefer(text(match, "_id"), text(match, "id"));
        hit.score = numeric(match, "_score");
        if (hit.score == null) {
            hit.score = numeric(match, "score");
        }
        hit.sparseScore = hit.score;
        hit.reason = "sparse_vector";
        hit.title = prefer(text(fields, "title"), text(fields, "filename"), hit.embeddingId);
        hit.source = prefer(text(fields, "filename"), hit.title);
        hit.section = text(fields, "section");
        hit.sectionPath = text(fields, "sectionPath");
        hit.documentId = text(fields, "documentId");
        hit.chunkId = text(fields, "chunkId");
        hit.sectionId = text(fields, "sectionId");
        hit.documentHash = text(fields, "documentHash");
        hit.sourcePath = text(fields, "sourcePath");
        hit.pageNumber = integer(fields, "pageNumber");
        hit.pageStart = integer(fields, "pageStart");
        hit.pageEnd = integer(fields, "pageEnd");
        hit.chunkIndex = integer(fields, "chunkIndex");
        hit.content = prefer(text(fields, properties.getTextField()), text(fields, "text"), "");
        // 将所有返回字段存入 metadata
        for (String field : RETURN_FIELDS) {
            JsonNode value = fields.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                hit.metadata.put(field, value.isNumber() ? value.numberValue() : value.asText());
            }
        }
        return hit;
    }

    /**
     * 构建文档的记录 ID 列表。
     *
     * <p>优先从 chunkRefs 中提取，如果没有则根据 chunkCount 生成。</p>
     */
    private List<String> buildRecordIds(DocumentManifest manifest) {
        List<String> ids = new ArrayList<>();
        if (manifest.chunkRefs != null && !manifest.chunkRefs.isEmpty()) {
            manifest.chunkRefs.stream()
                    .filter(ref -> ref != null && ref.chunkIndex != null)
                    .map(ref -> recordId(manifest.documentId, ref.chunkIndex))
                    .distinct()
                    .forEach(ids::add);
        }
        // 如果 chunkRefs 为空，根据 chunkCount 生成 ID
        if (ids.isEmpty() && manifest.chunkCount > 0) {
            for (int i = 0; i < manifest.chunkCount; i++) {
                ids.add(recordId(manifest.documentId, i));
            }
        }
        return ids;
    }

    /**
     * 检查服务是否处于活跃状态。
     *
     * <p>需要同时满足以下条件：</p>
     * <ul>
     *   <li>配置中启用了稀疏检索</li>
     *   <li>未被会话级禁用</li>
     *   <li>API Key 不为空</li>
     *   <li>索引名称不为空</li>
     * </ul>
     */
    private boolean isActive() {
        return properties.isEnabled()
                && !disabledForSession
                && !isBlank(pineconeApiKey)
                && !isBlank(properties.getIndexName());
    }

    /**
     * 解析 Pinecone 数据主机地址。
     *
     * <p>解析策略：</p>
     * <ol>
     *   <li>如果配置中指定了 host，直接使用</li>
     *   <li>否则通过 Pinecone API 查询索引的 host</li>
     *   <li>如果索引不存在且启用了自动创建，则创建索引</li>
     * </ol>
     *
     * <p>解析结果会缓存在 resolvedHost 中，避免重复查询。</p>
     */
    private synchronized String resolveDataHost() {
        if (!isActive()) {
            return null;
        }
        // 使用缓存的 host
        if (!isBlank(resolvedHost)) {
            return resolvedHost;
        }
        // 优先使用配置中指定的 host
        if (!isBlank(properties.getHost())) {
            resolvedHost = normalizeHost(properties.getHost());
            return resolvedHost;
        }
        if (!properties.isAutoResolveHost()) {
            return null;
        }

        try {
            // 通过 Pinecone API 查询索引的 host
            JsonNode description = get("https://api.pinecone.io/indexes/" + properties.getIndexName());
            String host = description.path("host").asText("");
            // 如果索引不存在且启用了自动创建
            if (isBlank(host) && properties.isAutoCreateIndex()) {
                createIndexIfMissing();
                description = get("https://api.pinecone.io/indexes/" + properties.getIndexName());
                host = description.path("host").asText("");
            }
            if (isBlank(host)) {
                log.warn("Pinecone sparse host unavailable for index={}", properties.getIndexName());
                return null;
            }
            resolvedHost = normalizeHost(host);
            return resolvedHost;
        } catch (Exception e) {
            String message = sanitize(e.getMessage());
            // 索引不存在（404）且启用了自动创建
            if (properties.isAutoCreateIndex() && message.contains("status 404")) {
                createIndexIfMissing();
                return resolveDataHost();
            }
            log.warn("Unable to resolve Pinecone sparse host | index={} | error={}",
                    properties.getIndexName(), message);
            disableIfRecoverable();
            return null;
        }
    }

    /**
     * 创建 Pinecone 稀疏索引（如果不存在）。
     *
     * <p>使用 Pinecone 的 "create-for-model" API，
     * 自动配置稀疏索引模型和字段映射。</p>
     */
    private void createIndexIfMissing() {
        Map<String, Object> request = Map.of(
                "name", properties.getIndexName(),
                "cloud", properties.getCloud(),
                "region", properties.getRegion(),
                "embed", Map.of(
                        "model", properties.getModel(),
                        "field_map", Map.of(properties.getTextField(), properties.getTextField())
                )
        );
        try {
            postJson("https://api.pinecone.io/indexes/create-for-model", request);
            log.info("Pinecone sparse index created | index={}", properties.getIndexName());
        } catch (Exception e) {
            String message = sanitize(e.getMessage());
            // 409 Conflict 表示索引已存在
            if (message.contains("status 409")) {
                log.info("Pinecone sparse index already exists | index={}", properties.getIndexName());
                return;
            }
            throw new IllegalStateException("Failed to create Pinecone sparse index: " + message, e);
        }
    }

    private JsonNode get(String url) {
        HttpResult result = webClient.get()
                .uri(url)
                .headers(this::applyJsonHeaders)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new HttpResult(response.statusCode(), body)))
                .blockOptional()
                .orElse(new HttpResult(HttpStatusCode.valueOf(599), ""));
        return parseJson("GET", url, result);
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        HttpResult result = webClient.post()
                .uri(url)
                .headers(this::applyJsonHeaders)
                .bodyValue(body)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(raw -> new HttpResult(response.statusCode(), raw)))
                .blockOptional()
                .orElse(new HttpResult(HttpStatusCode.valueOf(599), ""));
        return parseJson("POST", url, result);
    }

    private JsonNode postNdjson(String url, String ndjsonBody) {
        HttpResult result = webClient.post()
                .uri(url)
                .headers(this::applyNdjsonHeaders)
                .bodyValue(ndjsonBody)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(raw -> new HttpResult(response.statusCode(), raw)))
                .blockOptional()
                .orElse(new HttpResult(HttpStatusCode.valueOf(599), ""));
        return parseJson("POST", url, result);
    }

    private JsonNode parseJson(String method, String url, HttpResult result) {
        if (result.statusCode == null || result.statusCode.isError()) {
            throw new IllegalStateException(method + " " + url + " failed with status "
                    + (result.statusCode == null ? "unknown" : result.statusCode.value())
                    + ": " + sanitize(result.body));
        }
        try {
            return objectMapper.readTree(result.body == null || result.body.isBlank() ? "{}" : result.body);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse Pinecone sparse response from " + url, e);
        }
    }

    private void applyJsonHeaders(HttpHeaders headers) {
        headers.set("Api-Key", pineconeApiKey);
        headers.set("X-Pinecone-Api-Version", properties.getApiVersion());
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    private void applyNdjsonHeaders(HttpHeaders headers) {
        headers.set("Api-Key", pineconeApiKey);
        headers.set("X-Pinecone-Api-Version", properties.getApiVersion());
        headers.set(HttpHeaders.CONTENT_TYPE, "application/x-ndjson");
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * 生成 Pinecone 命名空间。
     *
     * <p>命名空间用于多租户隔离：每个研究组有自己的命名空间，
     * 确保检索时不会跨组访问数据。</p>
     *
     * <p>命名规则：</p>
     * <ul>
     *   <li>转为小写</li>
     *   <li>只保留字母、数字和连字符</li>
     *   <li>去除首尾连字符</li>
     *   <li>可选的前缀（如 "rag-"）</li>
     * </ul>
     */
    private String namespaceFor(String researchGroupId) {
        String normalized = researchGroupId == null ? "default" : researchGroupId.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9-]+", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "default";
        }
        String prefix = properties.getNamespacePrefix();
        if (isBlank(prefix)) {
            return normalized;
        }
        return prefix.trim().toLowerCase(Locale.ROOT) + "-" + normalized;
    }

    private String normalizeHost(String host) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Pinecone sparse record", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double numeric(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String prefer(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        if (isBlank(pineconeApiKey)) {
            return value;
        }
        return value.replace(pineconeApiKey, "***");
    }

    private void disableIfRecoverable() {
        disabledForSession = true;
    }

    private record HttpResult(HttpStatusCode statusCode, String body) {
    }
}
