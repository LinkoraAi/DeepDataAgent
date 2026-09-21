package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 抽取缓存归属值对象（对应 {@code chunk_extract_cache} 表的一行）。
 * <p>语义：「某个分块使用过哪一行抽取缓存」——分块与缓存行之间的多对多关系记录，
 * 是文档删除时按引用计数回收缓存行的唯一依据（同一缓存行可被多个分块共用，
 * 同一分块也可对应多个缓存行：首轮抽取 + 补漏续抽 + 媒体描述）。</p>
 * <p>关联口径：本值对象以 {@code cacheKey}（而非 {@code llm_cache.id}）指向缓存行，
 * 因为缓存回写走 {@code INSERT ... ON CONFLICT DO NOTHING} 且不返回自增主键、
 * 也无法得知本次是否真的发生了插入；而 {@code (kbId, cacheType, cacheKey)} 三元组
 * 本身就是 {@code llm_cache} 的复合唯一键，足以稳定唯一定位缓存行。</p>
 * <p>落库唯一键为 {@code (chunkId, cacheType, cacheKey)}：重复登记经
 * {@code ON CONFLICT DO NOTHING} 幂等，MUST NOT 产生重复归属行。</p>
 *
 * @param id        主键（内存新建态为 null，由数据库自增回填）
 * @param kbId      所属知识库ID（与 {@code llm_cache} 复合唯一键同源的隔离维度，不可为空）
 * @param chunkId   分块ID，弱引用 {@code chunk.id}（归属的引用方，不可为空）
 * @param cacheType 缓存分类（抽取与媒体描述为 {@link CacheType#EXTRACT}，不可为空）
 * @param cacheKey  缓存键（32 位 MD5 hex，指向 {@code llm_cache.cache_key}，不可为空）
 * @param createdAt 创建时间（数据库恢复态才有值）
 * @param updatedAt 更新时间（数据库恢复态才有值）
 */
public record ChunkExtractCache(
        Long id,
        Long kbId,
        Long chunkId,
        CacheType cacheType,
        String cacheKey,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 紧凑构造器：不变量校验（归属行三要素缺一不可，否则既无法幂等登记也无法定位缓存行）。
     */
    public ChunkExtractCache {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("缓存归属必须关联知识库");
        }
        if (ObjectUtils.isEmpty(chunkId)) {
            throw new IllegalArgumentException("缓存归属必须关联分块");
        }
        if (ObjectUtils.isEmpty(cacheType)) {
            throw new IllegalArgumentException("缓存分类不能为空");
        }
        if (StringUtils.isBlank(cacheKey)) {
            throw new IllegalArgumentException("缓存键不能为空");
        }
    }

    /**
     * 新建归属行（摄入期登记入口，主键与审计时间由持久化层回填）。
     *
     * @param kbId      所属知识库ID
     * @param chunkId   分块ID
     * @param cacheType 缓存分类
     * @param cacheKey  缓存键（32 位 MD5 hex）
     * @return 内存新建态归属行
     */
    public static ChunkExtractCache create(Long kbId, Long chunkId, CacheType cacheType, String cacheKey) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new ChunkExtractCache(null, kbId, chunkId, cacheType, cacheKey, now, now);
    }

    /**
     * 从数据库恢复归属行。
     *
     * @param id        主键
     * @param kbId      所属知识库ID
     * @param chunkId   分块ID
     * @param cacheType 缓存分类
     * @param cacheKey  缓存键
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @return 恢复态归属行
     */
    public static ChunkExtractCache restore(Long id, Long kbId, Long chunkId, CacheType cacheType, String cacheKey,
                                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new ChunkExtractCache(id, kbId, chunkId, cacheType, cacheKey, createdAt, updatedAt);
    }
}
