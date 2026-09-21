package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 知识库级 LLM 缓存清退契约（跨 BC 服务边界，依赖倒置端口）。
 * <p>提供方为 rag BC（LLM 调用缓存 {@code llm_cache} 归本 BC 所有，实现位于 rag.infrastructure），
 * 消费方为 knowledgebase BC 的知识库删除收敛链（当前为 BC 间进程内消费，不提供 REST 端点）。
 * knowledgebase 包 MUST NOT 直接依赖 rag 包，故经本端口反向解耦。</p>
 *
 * <p>清退语义：缓存键为内容哈希（不含 kb_id 维度外的精准清理依据），仅支持知识库维度整清——
 * 清空该库在 {@code llm_cache} 全部缓存分类（cache_type）分区下的条目。
 * 缓存属可重建的派生数据，清退失败不阻断知识库删除链收口（条件 DELETE），由收敛链下一轮重入天然重试。</p>
 */
public interface KbCacheCleanupApi {

    /**
     * 按知识库整清其全部 LLM 缓存条目。
     * <p>幂等：按 {@code kbId} 条件逻辑删除，重复调用结果一致；该库无缓存条目时静默成功、不报错。
     * 仅影响指定知识库，不触碰其他库的缓存分区。</p>
     *
     * @param kbId 知识库主键，必填；为空时按无操作处理（不触达仓储）
     */
    void deleteCachesByKnowledgeBase(Long kbId);
}
