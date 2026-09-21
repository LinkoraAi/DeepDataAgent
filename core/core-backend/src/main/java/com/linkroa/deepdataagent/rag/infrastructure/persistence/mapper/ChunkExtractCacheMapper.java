package com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.ChunkExtractCacheEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 抽取缓存归属 Mapper（{@code chunk_extract_cache} 表）。
 * <p>登记走多值 INSERT + {@code ON CONFLICT (chunk_id, cache_type, cache_key) DO NOTHING}：
 * 重复归属被数据库忽略，不产生重复行、也不触碰 {@code llm_cache} 行本身；
 * 查询与删除均携带 {@code kb_id} 等值条件，把扫描面收窄到本知识库。</p>
 * <p>自定义注解 SQL 不触发 MetaObjectHandler，审计字段必须在 Java 侧显式赋值。</p>
 */
@Mapper
public interface ChunkExtractCacheMapper extends BaseMapper<ChunkExtractCacheEntity> {

    /**
     * 批量登记归属行（多值 INSERT，冲突即忽略）。
     *
     * @param list 待登记的归属实体列表（非空，调用方已去重并补齐审计字段）
     * @return 实际插入行数（被 DO NOTHING 忽略的重复行不计入）
     */
    @Insert("""
            <script>
            INSERT INTO chunk_extract_cache (kb_id, chunk_id, cache_type, cache_key,
                                            created_at, updated_at, created_by, updated_by)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.kbId}, #{it.chunkId}, #{it.cacheType}, #{it.cacheKey},
                 #{it.createdAt}, #{it.updatedAt}, #{it.createdBy}, #{it.updatedBy})
            </foreach>
            ON CONFLICT (chunk_id, cache_type, cache_key) DO NOTHING
            </script>
            """)
    int insertBatchIfAbsent(@Param("list") List<ChunkExtractCacheEntity> list);

    /**
     * 按知识库 + 缓存分类 + 分块集合查询归属行（重建与回收的追溯查询）。
     *
     * @param kbId      所属知识库ID
     * @param cacheType 缓存分类（CacheType 枚举名）
     * @param chunkIds  分块ID集合（为空时直接返回空列表，不下发 IN 空列表的非法 SQL）
     * @return 命中的归属行（无归属的分块不出现）
     */
    default List<ChunkExtractCacheEntity> selectByKbIdAndChunkIds(Long kbId, String cacheType, List<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        return selectList(Wrappers.<ChunkExtractCacheEntity>lambdaQuery()
                .eq(ChunkExtractCacheEntity::getKbId, kbId)
                .eq(ChunkExtractCacheEntity::getCacheType, cacheType)
                .in(ChunkExtractCacheEntity::getChunkId, chunkIds));
    }

    /**
     * 在给定缓存键集合中查出仍被任何分块引用的键（引用计数判定，去重后返回）。
     *
     * @param kbId      所属知识库ID（隔离维度，避免跨库同键互认引用）
     * @param cacheType 缓存分类（CacheType 枚举名）
     * @param cacheKeys 待判定的缓存键列表（非空，调用方已判空与去重）
     * @return 仍存在于归属表中的缓存键（未命中的键不出现）
     */
    @Select("""
            <script>
            SELECT DISTINCT cache_key
            FROM chunk_extract_cache
            WHERE kb_id = #{kbId}
              AND cache_type = #{cacheType}
              AND cache_key IN
            <foreach collection="cacheKeys" item="key" open="(" separator="," close=")">#{key}</foreach>
            </script>
            """)
    List<String> selectReferencedKeys(@Param("kbId") Long kbId,
                                     @Param("cacheType") String cacheType,
                                     @Param("cacheKeys") List<String> cacheKeys);

    /**
     * 按知识库 + 分块集合物理删除归属行（无 {@code @TableLogic}，delete(wrapper) 即 DELETE FROM）。
     *
     * @param kbId     所属知识库ID（隔离维度）
     * @param chunkIds 分块ID集合（为空时直接返回 0，不下发 IN 空列表的非法 SQL）
     * @return 被删除的归属行数
     */
    default int deleteByKbIdAndChunkIds(Long kbId, List<Long> chunkIds) {
        if (ObjectUtils.isEmpty(chunkIds)) {
            return 0;
        }
        return delete(Wrappers.<ChunkExtractCacheEntity>lambdaQuery()
                .eq(ChunkExtractCacheEntity::getKbId, kbId)
                .in(ChunkExtractCacheEntity::getChunkId, chunkIds));
    }
}
