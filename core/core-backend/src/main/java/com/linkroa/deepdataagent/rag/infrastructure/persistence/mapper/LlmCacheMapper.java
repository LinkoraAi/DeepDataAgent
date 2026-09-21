package com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.LlmCacheEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * LLM 调用缓存 Mapper（llm_cache 表）。
 * <p>命中检索携带 kb_id 与缓存分类走复合唯一键 {@code (kb_id, cache_type, cache_key)}；
 * 回写经 {@code ON CONFLICT DO NOTHING}，并发重复写入幂等安全。</p>
 */
@Mapper
public interface LlmCacheMapper extends BaseMapper<LlmCacheEntity> {

    /**
     * 按知识库、缓存分类与缓存键查询缓存条目（复合唯一键命中）。
     *
     * @param kbId      所属知识库ID
     * @param cacheType 缓存分类（CacheType 枚举名）
     * @param cacheKey  缓存键（32 位 MD5 hex）
     * @return 命中的条目；未命中返回 null
     */
    default LlmCacheEntity selectByKbIdAndCacheKey(Long kbId, String cacheType, String cacheKey) {
        return selectOne(Wrappers.<LlmCacheEntity>lambdaQuery()
                .eq(LlmCacheEntity::getKbId, kbId)
                .eq(LlmCacheEntity::getCacheType, cacheType)
                .eq(LlmCacheEntity::getCacheKey, cacheKey)
                .last("LIMIT 1"));
    }

    /**
     * 按知识库、缓存分类与缓存键集合批量查询缓存条目（复合唯一键 IN 命中，重放与回收路径专用）。
     *
     * @param kbId      所属知识库ID
     * @param cacheType 缓存分类（CacheType 枚举名）
     * @param cacheKeys 缓存键列表（为空时直接返回空列表，不下发 IN 空列表的非法 SQL）
     * @return 命中的条目列表（不存在的键不出现）
     */
    default List<LlmCacheEntity> selectByKbIdAndCacheKeys(Long kbId, String cacheType, List<String> cacheKeys) {
        if (ObjectUtils.isEmpty(cacheKeys)) {
            return List.of();
        }
        return selectList(Wrappers.<LlmCacheEntity>lambdaQuery()
                .eq(LlmCacheEntity::getKbId, kbId)
                .eq(LlmCacheEntity::getCacheType, cacheType)
                .in(LlmCacheEntity::getCacheKey, cacheKeys));
    }

    /**
     * 按知识库、缓存分类与缓存键集合批量物理删除缓存行（复合唯一键前缀 IN 命中，
     * 抽取缓存按引用计数回收专用；键列表为空时返回 0，不下发 IN 空列表的非法 SQL）。
     *
     * @param kbId      所属知识库ID
     * @param cacheType 缓存分类（CacheType 枚举名）
     * @param cacheKeys 缓存键列表（调用方已按引用判定筛出「归零」集并分批）
     * @return 实际物理删除行数
     */
    default int deleteByKbIdAndCacheKeys(Long kbId, String cacheType, List<String> cacheKeys) {
        if (ObjectUtils.isEmpty(cacheKeys)) {
            return 0;
        }
        return delete(Wrappers.<LlmCacheEntity>lambdaQuery()
                .eq(LlmCacheEntity::getKbId, kbId)
                .eq(LlmCacheEntity::getCacheType, cacheType)
                .in(LlmCacheEntity::getCacheKey, cacheKeys));
    }

    /**
     * 回写缓存条目（存在则忽略，避免并发唯一冲突）。
     *
     * @param entity 缓存条目（审计字段已由调用方填充）
     * @return 受影响行数（0 表示已存在忽略）
     */
    @Insert("""
            INSERT INTO llm_cache (kb_id, cache_type, cache_key, model, prompt, response, total_tokens,
                                   created_at, updated_at, created_by, updated_by)
            VALUES (#{kbId}, #{cacheType}, #{cacheKey}, #{model}, #{prompt}, #{response}, #{totalTokens},
                    #{createdAt}, #{updatedAt}, #{createdBy}, #{updatedBy})
            ON CONFLICT (kb_id, cache_type, cache_key) DO NOTHING
            """)
    int insertIfAbsent(LlmCacheEntity entity);
}