package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.entity.DocumentManifest;
import com.team.rag.infrastructure.RedisRetryGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档清单存储服务（Document Manifest Store）。
 *
 * <p>职责：管理文档的元数据清单（DocumentManifest），包括文档的基本信息、
 * 处理状态、存储路径、分块数量等。是文档生命周期管理的核心组件。</p>
 *
 * <h3>设计思想：</h3>
 * <p>采用"Redis + 本地缓存"的双层存储架构：</p>
 * <ul>
 *   <li>Redis - 持久化存储，支持跨实例共享和重启恢复</li>
 *   <li>本地 ConcurrentHashMap - 内存缓存，加速热点数据访问</li>
 * </ul>
 *
 * <p>当 Redis 不可用时，自动降级为纯本地模式，确保服务不中断。
 * 这种"优雅降级"策略在分布式系统中很常见——宁可功能受限，也不能完全不可用。</p>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * DocumentUploadController（文档上传入口）
 *     └── DocumentManifestStore（本类：文档清单管理）
 *         ├── Redis（持久化）
 *         └── ConcurrentHashMap（本地缓存）
 *
 * DocumentReadService（文档读取服务）
 *     └── DocumentManifestStore（本类：查询文档元数据）
 *
 * DocumentDiagnosticsService（文档诊断服务）
 *     └── DocumentManifestStore（本类：诊断文档状态）
 * </pre>
 *
 * <h3>Redis Key 设计：</h3>
 * <ul>
 *   <li>document:manifest:{documentId} - 文档清单主体（JSON 格式）</li>
 *   <li>document:manifest:index:{researchGroupId} - 研究组文档索引（Set 结构）</li>
 * </ul>
 */
@Service
public class DocumentManifestStore {

    private static final Logger log = LoggerFactory.getLogger(DocumentManifestStore.class);

    /** Redis Key 前缀：文档清单主体 */
    private static final String KEY_PREFIX = "document:manifest:";

    /** Redis Key 前缀：研究组文档索引（Set 结构，存储 documentId 集合） */
    private static final String INDEX_PREFIX = "document:manifest:index:";

    /** Redis 操作模板，用于持久化存储文档清单 */
    private final StringRedisTemplate redisTemplate;

    /** JSON 序列化器，用于 DocumentManifest 与 JSON 的互转 */
    private final ObjectMapper objectMapper;

    /** 本地缓存：documentId -> DocumentManifest，加速热点数据访问 */
    private final Map<String, DocumentManifest> local = new ConcurrentHashMap<>();
    private final Map<String, Instant> touchedAt = new ConcurrentHashMap<>();
    private final RedisRetryGate redisGate = new RedisRetryGate(Duration.ofSeconds(30));
    private static final int MAX_LOCAL_MANIFESTS = 5000;

    public DocumentManifestStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存文档清单。
     *
     * <p>采用"先写本地缓存，再异步写 Redis"的策略：
     * <ol>
     *   <li>先更新本地缓存（确保当前实例立即可见）</li>
     *   <li>再写 Redis（确保跨实例共享和持久化）</li>
     * </ol>
     *
     * <p>如果 Redis 写入失败，自动降级为本地模式（redisAvailable = false），
     * 后续操作只读写本地缓存。</p>
     *
     * @param manifest 待保存的文档清单
     */
    public void save(DocumentManifest manifest) {
        // 参数校验：documentId 是清单的唯一标识，不能为空
        if (manifest == null || manifest.documentId == null || manifest.documentId.isBlank()) {
            return;
        }
        // 更新时间戳，用于排序和过期清理
        manifest.updatedAt = Instant.now();
        // 先写本地缓存，确保当前实例立即可见
        local.put(manifest.documentId, manifest);
        touch(manifest.documentId);
        enforceLocalCap();
        if (!redisGate.canAttempt()) {
            return;  // Redis 不可用时只写本地
        }
        try {
            // 写 Redis 主体：documentId -> JSON
            redisTemplate.opsForValue().set(key(manifest.documentId), objectMapper.writeValueAsString(manifest));
            // 写 Redis 索引：researchGroupId -> Set<documentId>
            // 这样查询某个研究组的所有文档时，只需一次 Set 操作
            redisTemplate.opsForSet().add(indexKey(manifest.researchGroupId), manifest.documentId);
            redisGate.success();
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Document manifest Redis persistence cooling down for 30s | error={}", e.getMessage());
        }
    }

    /**
     * 获取文档清单。
     *
     * <p>采用"本地缓存优先"策略：
     * <ol>
     *   <li>先查本地缓存（O(1) 时间复杂度）</li>
     *   <li>缓存未命中时查 Redis（网络 IO）</li>
     *   <li>Redis 查询成功后回填本地缓存（Cache-Aside 模式）</li>
     * </ol>
     *
     * @param documentId 文档唯一标识
     * @return 文档清单，不存在时返回 null
     */
    public DocumentManifest get(String documentId) {
        // 优先查本地缓存，避免网络 IO
        DocumentManifest cached = local.get(documentId);
        if (cached != null) {
            touch(documentId);
            return cached;
        }
        if (!redisGate.canAttempt()) {
            return null;  // Redis 不可用时只查本地
        }
        try {
            // 从 Redis 读取 JSON 并反序列化
            String json = redisTemplate.opsForValue().get(key(documentId));
            if (json == null || json.isBlank()) {
                redisGate.success();
                return null;
            }
            DocumentManifest manifest = objectMapper.readValue(json, DocumentManifest.class);
            // 回填本地缓存（Cache-Aside 模式）
            local.put(documentId, manifest);
            touch(documentId);
            enforceLocalCap();
            redisGate.success();
            return manifest;
        } catch (Exception e) {
            redisGate.failure();
            return null;
        }
    }

    /**
     * 列出指定研究组的所有文档清单。
     *
     * <p>查询策略：合并本地缓存和 Redis 索引的结果，
     * 确保不遗漏任何一方的数据。使用 LinkedHashMap 去重（documentId 作为 key）。</p>
     *
     * <p>排序规则：按创建时间降序（最新上传的文档排在前面）。</p>
     *
     * @param researchGroupId 研究组标识（可选，为空时返回所有文档）
     * @return 文档清单列表，按创建时间降序排列
     */
    public List<DocumentManifest> list(String researchGroupId) {
        Map<String, DocumentManifest> result = new LinkedHashMap<>();
        // 第一步：从本地缓存中筛选符合条件的文档
        local.values().stream()
                .filter(item -> researchGroupId == null || researchGroupId.isBlank()
                        || researchGroupId.equals(item.researchGroupId))
                .forEach(item -> result.put(item.documentId, item));
        // 第二步：从 Redis 索引中补充本地缓存可能遗漏的文档
        // （本地缓存是进程级的，其他实例上传的文档可能不在本地缓存中）
        if (redisGate.canAttempt() && researchGroupId != null && !researchGroupId.isBlank()) {
            try {
                // 从 Redis Set 中获取该研究组的所有 documentId
                var ids = redisTemplate.opsForSet().members(indexKey(researchGroupId));
                if (ids != null) {
                    for (String id : ids) {
                        // get() 方法会自动回填本地缓存
                        DocumentManifest manifest = get(id);
                        if (manifest != null) {
                            result.put(id, manifest);  // LinkedHashMap 自动去重
                        }
                    }
                }
                redisGate.success();
            } catch (Exception e) {
                redisGate.failure();
            }
        }
        // 按创建时间降序排列，最新上传的文档排在前面
        return result.values().stream()
                .sorted(Comparator.comparing(
                        (DocumentManifest item) -> item.createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /**
     * 删除文档清单。
     *
     * <p>删除策略：先删本地缓存，再删 Redis 主体和索引。
     * 需要删除两个 Redis Key：
     * <ol>
     *   <li>document:manifest:{documentId} - 清单主体</li>
     *   <li>document:manifest:index:{researchGroupId} - 索引中的 documentId</li>
     * </ol>
     *
     * @param documentId 文档唯一标识
     */
    public void delete(String documentId) {
        // 先从本地缓存移除，并保留 manifest 引用（用于获取 researchGroupId）
        DocumentManifest manifest = local.remove(documentId);
        touchedAt.remove(documentId);
        if (!redisGate.canAttempt()) {
            return;
        }
        try {
            // 删除 Redis 中的清单主体
            redisTemplate.delete(key(documentId));
            // 从研究组索引中移除 documentId
            if (manifest != null && manifest.researchGroupId != null) {
                redisTemplate.opsForSet().remove(indexKey(manifest.researchGroupId), documentId);
            }
            redisGate.success();
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Document manifest Redis delete cooling down for 30s | error={}", e.getMessage());
        }
    }

    private void touch(String documentId) {
        touchedAt.put(documentId, Instant.now());
    }

    private void enforceLocalCap() {
        int overflow = local.size() - MAX_LOCAL_MANIFESTS;
        if (overflow <= 0) return;
        touchedAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(id -> { local.remove(id); touchedAt.remove(id); });
    }

    /**
     * 生成文档清单的 Redis Key。
     *
     * @param documentId 文档唯一标识
     * @return Redis Key（如 "document:manifest:abc123"）
     */
    private String key(String documentId) {
        return KEY_PREFIX + documentId;
    }

    /**
     * 生成研究组文档索引的 Redis Key。
     *
     * <p>如果 researchGroupId 为空，使用 "default" 作为默认值。</p>
     *
     * @param researchGroupId 研究组标识
     * @return Redis Key（如 "document:manifest:index:team-alpha"）
     */
    private String indexKey(String researchGroupId) {
        return INDEX_PREFIX + (researchGroupId == null || researchGroupId.isBlank() ? "default" : researchGroupId);
    }
}
