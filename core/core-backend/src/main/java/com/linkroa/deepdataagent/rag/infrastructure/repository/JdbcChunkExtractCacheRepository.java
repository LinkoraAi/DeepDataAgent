package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ChunkExtractCache;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.ChunkExtractCacheEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.ChunkExtractCacheMapper;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽取缓存归属仓储的关系型实现（MyBatis-Plus，{@code chunk_extract_cache} 表）。
 * <p>登记走多值 INSERT + {@code ON CONFLICT (chunk_id, cache_type, cache_key) DO NOTHING}：
 * 归属行天然会被多个分块、多条链路重复登记（重解析、重试、并发重复调用），
 * 幂等由数据库唯一键承担，Java 侧再对同批入参去重以压缩语句体积；
 * 大批量按固定批次大小分批下发，避免单条 SQL 过长（事务规范 3.3）。</p>
 * <p>归属以 {@code cache_key} 而非 {@code llm_cache.id} 定位缓存行：缓存回写的
 * {@code ON CONFLICT DO NOTHING} 不返回自增主键，无法回填 id；
 * 而 {@code (kb_id, cache_type, cache_key)} 三元组即缓存行的复合唯一键，定位结果唯一。</p>
 * <p>彻底物理删体系：归属行随分块删除而消失，无逻辑删墓碑；audit 字段在插入前显式填充。</p>
 */
@Repository
public class JdbcChunkExtractCacheRepository implements ChunkExtractCacheRepository {

    /** 归属登记单批行数上限（多值 INSERT 分批口径，与项目既有批量写入一致） */
    private static final int REGISTER_BATCH_SIZE = 500;

    private final ChunkExtractCacheMapper mapper;

    public JdbcChunkExtractCacheRepository(ChunkExtractCacheMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int registerAll(Long kbId, Long chunkId, CacheType cacheType, Collection<String> cacheKeys) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(chunkId) || ObjectUtils.isEmpty(cacheType)
                || ObjectUtils.isEmpty(cacheKeys)) {
            return 0;
        }
        // 同批入参先按登记顺序去重并剔除空白键：重复键不产生第二条归属行，也不放大冲突分支
        Set<String> distinctKeys = new LinkedHashSet<>();
        for (String cacheKey : cacheKeys) {
            if (StringUtils.isNotBlank(cacheKey)) {
                distinctKeys.add(cacheKey);
            }
        }
        if (ObjectUtils.isEmpty(distinctKeys)) {
            return 0;
        }
        List<ChunkExtractCacheEntity> entities = distinctKeys.stream()
                .map(cacheKey -> ChunkExtractCache.create(kbId, chunkId, cacheType, cacheKey))
                .map(JdbcChunkExtractCacheRepository::toEntity)
                .toList();
        int inserted = 0;
        for (List<ChunkExtractCacheEntity> batch : BatchSplitter.split(entities, REGISTER_BATCH_SIZE)) {
            // 多值 INSERT 不触发 MetaObjectHandler，审计字段逐行显式补齐（登记为系统级写入）
            batch.forEach(entity -> RagAuditFieldUtils.fillInsert(entity, null));
            inserted += mapper.insertBatchIfAbsent(batch);
        }
        return inserted;
    }

    @Override
    public Map<Long, Set<String>> findCacheKeysByChunkIds(Long kbId, CacheType cacheType, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(cacheType) || ObjectUtils.isEmpty(chunkIds)) {
            return Map.of();
        }
        List<ChunkExtractCacheEntity> rows = mapper.selectByKbIdAndChunkIds(kbId, cacheType.name(),
                List.copyOf(new LinkedHashSet<>(chunkIds)));
        Map<Long, Set<String>> keysByChunk = new LinkedHashMap<>();
        for (ChunkExtractCache row : toDomains(rows, cacheType)) {
            keysByChunk.computeIfAbsent(row.chunkId(), chunkId -> new LinkedHashSet<>()).add(row.cacheKey());
        }
        return keysByChunk;
    }

    @Override
    public int deleteByChunkIds(Long kbId, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(chunkIds)) {
            return 0;
        }
        return mapper.deleteByKbIdAndChunkIds(kbId, List.copyOf(new LinkedHashSet<>(chunkIds)));
    }

    @Override
    public Set<String> findReferencedKeys(Long kbId, CacheType cacheType, Collection<String> cacheKeys) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(cacheType) || ObjectUtils.isEmpty(cacheKeys)) {
            return Set.of();
        }
        List<String> distinctKeys = cacheKeys.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (ObjectUtils.isEmpty(distinctKeys)) {
            return Set.of();
        }
        // 只返回归属表中仍存在的键：入参中未出现的键即引用归零（可由调用方安全回收对应缓存行）
        return new LinkedHashSet<>(mapper.selectReferencedKeys(kbId, cacheType.name(), distinctKeys));
    }

    /**
     * 领域归属行转持久化实体（主键置空交由数据库自增，审计字段由调用方插入前填充）。
     *
     * @param domain 领域归属行
     * @return 待插入实体
     */
    private static ChunkExtractCacheEntity toEntity(ChunkExtractCache domain) {
        ChunkExtractCacheEntity entity = new ChunkExtractCacheEntity();
        entity.setId(null);
        entity.setKbId(domain.kbId());
        entity.setChunkId(domain.chunkId());
        entity.setCacheType(domain.cacheType().name());
        entity.setCacheKey(domain.cacheKey());
        return entity;
    }

    /**
     * 持久化行转领域归属行。
     * <p>缓存分类直接以查询入参枚举回填（结果集已由该分类等值条件收窄），
     * 避免对库内文本做 {@code valueOf} 解析。</p>
     *
     * @param rows      持久化行列表
     * @param cacheType 本次查询的缓存分类
     * @return 领域归属行列表
     */
    private static List<ChunkExtractCache> toDomains(List<ChunkExtractCacheEntity> rows, CacheType cacheType) {
        return rows.stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(row -> ChunkExtractCache.restore(row.getId(), row.getKbId(), row.getChunkId(), cacheType,
                        row.getCacheKey(), row.getCreatedAt(), row.getUpdatedAt()))
                .toList();
    }
}
