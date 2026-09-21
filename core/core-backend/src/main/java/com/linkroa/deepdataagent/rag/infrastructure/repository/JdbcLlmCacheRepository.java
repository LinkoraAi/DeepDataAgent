package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import com.linkroa.deepdataagent.rag.infrastructure.convert.RagGraphPersistenceConvert;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.LlmCacheEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.LlmCacheMapper;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * LLM 调用缓存仓储的关系型实现（MyBatis-Plus，llm_cache 表）。
 * <p>命中检索携带 kb_id 与缓存分类走复合唯一键 {@code (kb_id, cache_type, cache_key)}
 * 三元组精确匹配，实现库级与分类双重隔离；
 * 回写经 {@code ON CONFLICT DO NOTHING} 幂等保护，并发重复回写安全。</p>
 */
@Repository
public class JdbcLlmCacheRepository implements LlmCacheRepository {

    /** 按键批量读取的单批键数上限（IN 列表分批口径，与项目既有批量写入一致，防单条 SQL 膨胀） */
    private static final int SELECT_BATCH_SIZE = 500;

    private final LlmCacheMapper mapper;

    public JdbcLlmCacheRepository(LlmCacheMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LlmCacheEntry> findByKbIdAndCacheKey(Long kbId, CacheType cacheType, String cacheKey) {
        // 分类枚举以名称文本参与列匹配（null 分类使三元组无法命中，等价未命中）
        String cacheTypeName = ObjectUtils.isEmpty(cacheType) ? null : cacheType.name();
        return Optional.ofNullable(RagGraphPersistenceConvert.INSTANCE.toLlmCacheEntry(
                mapper.selectByKbIdAndCacheKey(kbId, cacheTypeName, cacheKey)));
    }

    @Override
    public List<LlmCacheEntry> findByKbIdAndCacheKeys(Long kbId, CacheType cacheType, Collection<String> cacheKeys) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(cacheType) || CollectionUtils.isEmpty(cacheKeys)) {
            return List.of();
        }
        // 剔除空白键并按首次出现序去重：重复键不放大 IN 列表体积；空集合不下发 SQL
        List<String> distinctKeys = cacheKeys.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(distinctKeys)) {
            return List.of();
        }
        String cacheTypeName = cacheType.name();
        List<LlmCacheEntry> entries = new ArrayList<>();
        for (List<String> batch : BatchSplitter.split(distinctKeys, SELECT_BATCH_SIZE)) {
            mapper.selectByKbIdAndCacheKeys(kbId, cacheTypeName, batch).stream()
                    .filter(ObjectUtils::isNotEmpty)
                    .map(RagGraphPersistenceConvert.INSTANCE::toLlmCacheEntry)
                    .filter(ObjectUtils::isNotEmpty)
                    .forEach(entries::add);
        }
        return entries;
    }

    @Override
    public void saveIfAbsent(LlmCacheEntry entry) {
        LlmCacheEntity entity = RagGraphPersistenceConvert.INSTANCE.toEntity(entry);
        entity.setId(null);
        // 自定义 INSERT 不触发 MetaObjectHandler，显式补齐审计字段（首写视为系统操作）
        RagAuditFieldUtils.fillInsert(entity, null);
        mapper.insertIfAbsent(entity);
    }

    @Override
    public int deleteByKbIdAndCacheKeys(Long kbId, CacheType cacheType, Collection<String> cacheKeys) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(cacheType) || CollectionUtils.isEmpty(cacheKeys)) {
            return 0;
        }
        // 与批量读取同口径：剔除空白键、去重后分批下发，空集合不下发 SQL（防 IN 空列表非法语句）
        List<String> distinctKeys = cacheKeys.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(distinctKeys)) {
            return 0;
        }
        String cacheTypeName = cacheType.name();
        int deleted = 0;
        for (List<String> batch : BatchSplitter.split(distinctKeys, SELECT_BATCH_SIZE)) {
            deleted += mapper.deleteByKbIdAndCacheKeys(kbId, cacheTypeName, batch);
        }
        return deleted;
    }

    @Override
    public void deleteByKbId(Long kbId) {
        // 彻底物理删除：无 @TableLogic 改写，标准 delete(wrapper) 即 DELETE FROM
        mapper.delete(Wrappers.<LlmCacheEntity>lambdaQuery()
                .eq(LlmCacheEntity::getKbId, kbId));
    }
}