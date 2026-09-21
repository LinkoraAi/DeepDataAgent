package com.linkroa.deepdataagent.rag.domain.repository;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 抽取缓存归属仓储端口（对应 {@code chunk_extract_cache} 表，design D1）。
 * <p>只回答一件事：「哪个分块用过哪一行抽取缓存」。归属关系为多对多——同一缓存行可被多个
 * 分块共用（内容相同的分块命中同一行），同一分块可对应多个缓存行（首轮抽取 + 补漏续抽 +
 * 媒体描述）。缓存行本身（{@code llm_cache}）的结构、缓存键计算与 {@code ON CONFLICT DO NOTHING}
 * 回写语义均不受本端口影响。</p>
 * <p><b>为何以 {@code cache_key} 而非 {@code llm_cache.id} 关联</b>：缓存回写经
 * {@code INSERT ... ON CONFLICT DO NOTHING}，既不返回自增主键、也不告知本次是否真的写入，
 * 因此拿不到可靠的 {@code llm_cache.id} 用于回填；而
 * {@code (kb_id, cache_type, cache_key)} 三元组本身即 {@code llm_cache} 的复合唯一键，
 * 以它定位缓存行既唯一又与写入路径同构，归属登记与缓存回写因此可各自独立、互不依赖先后。</p>
 * <p><b>删除链用法</b>（引用计数回收，design D4）：先以 {@link #findCacheKeysByChunkIds} 从待删
 * 分块追溯其用过的缓存键，再以 {@link #deleteByChunkIds} 删除本批归属行，最后以
 * {@link #findReferencedKeys} 判定这些键中哪些仍被其他存活分块引用——未被引用者才可删除缓存行。
 * MUST NOT 以「查不到归属」为理由删除任何缓存行（历史缓存行本就无归属）。</p>
 */
public interface ChunkExtractCacheRepository {

    /**
     * 批量登记某分块对一批缓存行的归属（摄入期调用，命中与未命中两条路径都须登记）。
     * <p>幂等：入参内的重复键在实现侧先去重，落库经
     * {@code INSERT ... ON CONFLICT (chunk_id, cache_type, cache_key) DO NOTHING} 保护，
     * 同一分块对同一缓存行重复登记（重解析、重试、并发重复调用）MUST NOT 产生重复归属行，
     * 也 MUST NOT 触碰 {@code llm_cache} 行本身。</p>
     *
     * @param kbId      所属知识库ID（隔离维度，为空时不登记）
     * @param chunkId   使用缓存的分块ID（为空时不登记）
     * @param cacheType 缓存分类（抽取与媒体描述为 {@link CacheType#EXTRACT}，为空时不登记）
     * @param cacheKeys 本次使用到的缓存键集合（32 位 MD5 hex；为空或全为空白串时不登记）
     * @return 实际新增的归属行数（重复登记被忽略时小于入参键数；未登记时返回 0）
     */
    int registerAll(Long kbId, Long chunkId, CacheType cacheType, Collection<String> cacheKeys);

    /**
     * 查一批分块在指定缓存分类下用过的缓存键（重建与回收的追溯入口）。
     * <p>返回「分块 → 该分块用过的缓存键集合」映射而非全局键集合：删除期重建需按分块逐一重放
     * 其抽取响应（design D5），键与分块的对应关系不可丢失；映射中不出现无任何归属的分块。
     * 供删除链在 {@link #deleteByChunkIds} 之前先追溯键集合，避免删完归属后无从判定引用。</p>
     *
     * @param kbId      所属知识库ID（隔离维度，为空时返回空映射）
     * @param cacheType 缓存分类（为空时返回空映射）
     * @param chunkIds  分块ID集合（为空时返回空映射，不下发 SQL）
     * @return 分块ID → 该分块用过的缓存键集合；无归属的分块不出现在映射中
     */
    Map<Long, Set<String>> findCacheKeysByChunkIds(Long kbId, CacheType cacheType, Collection<Long> chunkIds);

    /**
     * 按分块删除归属行（删除链清理第一步，与分块行删除同事务）。
     * <p>彻底物理删除：归属行随分块一起消失，不做逻辑删标记。删除范围严格限定在
     * {@code kbId + chunkIds} 交集内，不跨知识库。</p>
     *
     * @param kbId     所属知识库ID（隔离维度，为空时不删除）
     * @param chunkIds 待删除归属的分块ID集合（为空时不删除，不下发 SQL）
     * @return 被删除的归属行数（0 表示本批分块无归属，重复执行收敛为 0）
     */
    int deleteByChunkIds(Long kbId, Collection<Long> chunkIds);

    /**
     * 在给定缓存键集合中，查出仍被任何分块引用的键（引用计数判定）。
     * <p>调用时机：必须在 {@link #deleteByChunkIds} 之后——此时本批分块的归属已剔除，
     * 本方法返回的即「仍被其他存活分块引用」的键；入参键集合减去返回结果即为引用归零、
     * 可安全删除缓存行的键。共用键因此在删除一方文档后仍被保留。</p>
     * <p>只看归属表中是否仍存在对应行，绝不反向推定「无归属即可删」：历史缓存行本就无归属记录，
     * 不在本方法的判定范围内。</p>
     *
     * @param kbId      所属知识库ID（隔离维度，为空时返回空集合）
     * @param cacheType 缓存分类（为空时返回空集合）
     * @param cacheKeys 待判定的缓存键集合（为空时返回空集合，不下发 SQL）
     * @return 其中仍被分块引用的缓存键集合；全部归零时返回空集合
     */
    Set<String> findReferencedKeys(Long kbId, CacheType cacheType, Collection<String> cacheKeys);
}
