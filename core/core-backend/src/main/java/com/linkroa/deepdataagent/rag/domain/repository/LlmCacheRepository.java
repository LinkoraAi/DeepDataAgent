package com.linkroa.deepdataagent.rag.domain.repository;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * LLM 调用缓存仓储端口（对应 {@code llm_cache} 表）。
 * <p>缓存键 = MD5(model + prompt + 归一化参数) 的 32 位 hex（不含 kb_id），
 * 命中检索必须携带 kb_id 与缓存分类走复合唯一键 {@code (kb_id, cache_type, cache_key)}
 * 精确匹配，实现库级隔离与分类隔离；
 * 回写采用 {@code ON CONFLICT DO NOTHING}，并发重复写入安全（同一确定性键幂等）。</p>
 */
public interface LlmCacheRepository {

    /**
     * 按知识库、缓存分类与缓存键查询缓存条目（三元组精确匹配）。
     *
     * @param kbId      所属知识库ID（隔离维度，必填）
     * @param cacheType 缓存分类（隔离维度，必填）
     * @param cacheKey  缓存键（32 位 MD5 hex）
     * @return 缓存条目；未命中返回空
     */
    Optional<LlmCacheEntry> findByKbIdAndCacheKey(Long kbId, CacheType cacheType, String cacheKey);

    /**
     * 按知识库、缓存分类与缓存键集合批量查询缓存条目（三元组 IN 匹配，重放与回收路径专用）。
     * <p>一次批量读取而非逐键查询，避免 N+1；入参中的重复键在实现侧先去重再下发，
     * 键数超过单批上限时分批查询后合并。返回顺序不做约定：
     * 「按创建时间升序」等排序语义由调用方按自身业务口径完成。</p>
     *
     * @param kbId      所属知识库ID（隔离维度，必填；为空返回空列表）
     * @param cacheType 缓存分类（隔离维度，必填；为空返回空列表）
     * @param cacheKeys 缓存键集合（32 位 MD5 hex；为空或全为空白串时返回空列表，不下发 SQL）
     * @return 命中的缓存条目列表（不存在的键不出现在结果中）
     */
    List<LlmCacheEntry> findByKbIdAndCacheKeys(Long kbId, CacheType cacheType, Collection<String> cacheKeys);

    /**
     * 回写缓存条目（存在则忽略，避免并发重复回写触发唯一冲突）。
     *
     * @param entry 缓存条目（cacheKey 必须为 32 位 MD5 hex）
     */
    void saveIfAbsent(LlmCacheEntry entry);

    /**
     * 按知识库、缓存分类与缓存键集合批量删除缓存行（抽取缓存按引用计数回收专用）。
     * <p>调用前提是键集合已经过归属表引用判定（「追溯到且已归零」）——MUST NOT 以
     * 「查不到归属」为由把未经追溯的键传入本方法删除。删除范围严格限定
     * {@code (kbId, cacheType)} 交集内，不跨知识库、不跨缓存分类；键按 500 分批下发
     * （避免单条 IN 删除语句过长）。幂等：键已不存在时该批零影响，重复执行收敛为 0。</p>
     *
     * @param kbId      所属知识库ID（隔离维度，必填；为空返回 0，不下发 SQL）
     * @param cacheType 缓存分类（隔离维度，必填；为空返回 0，不下发 SQL）
     * @param cacheKeys 待删除的缓存键集合（32 位 MD5 hex；为空或全为空白串时返回 0，不下发 SQL）
     * @return 实际物理删除的缓存行数
     */
    int deleteByKbIdAndCacheKeys(Long kbId, CacheType cacheType, Collection<String> cacheKeys);

    /**
     * 按知识库整清缓存（知识库删除/重建时调用）。
     *
     * @param kbId 所属知识库ID
     */
    void deleteByKbId(Long kbId);
}