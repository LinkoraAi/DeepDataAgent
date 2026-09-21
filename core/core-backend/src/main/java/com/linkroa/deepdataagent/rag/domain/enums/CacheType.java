package com.linkroa.deepdataagent.rag.domain.enums;

/**
 * LLM 调用缓存分类（对应 {@code llm_cache.cache_type} 列）。
 * <p>检索/摄入链路的不同 LLM 产物按分类隔离存储：{@code (kb_id, cache_type, cache_key)}
 * 复合唯一键保证同一知识库内相同缓存键在不同分类下互不碰撞；
 * 数据库列默认 {@code 'ANSWER'}，兼容既有写入语义。</p>
 */
public enum CacheType {

    /** 双层关键词提取结果（Stage 0，MIX 模式）；键 = MD5(model+query+language+llmIdentity) */
    KEYWORD_EXTRACT,

    /** 问题改写结果（Stage 0 前置，开关配套）；键 = MD5(model+query+language+llmIdentity) */
    QUERY_REWRITE,

    /** 答案生成结果（Stage 5，MIX/NAIVE 与通用 LLM 调用默认分类）；键按方案要素串 MD5 */
    ANSWER,

    /** 实体描述摘要（摄入链路 summarize_description 产物） */
    ENTITY_DESC,

    /**
     * 摄入期实体/关系抽取与媒体描述响应（首轮抽取、补漏续抽与多模态媒体描述的 LLM/VLM 调用）；
     * 键机制沿用 {@code (kb_id, cache_type, cache_key)} 三元组复合唯一键，与查询答案、
     * 描述摘要分区互不串回放（spec llm-cache-partitioning）。
     */
    EXTRACT,

    /**
     * 通路 A 查询附图转译结果：缓存键由
     * {@code CachingLlmClient} 内嵌管理（含每图 SHA-256 摘要，同文本不同图必不同键），
     * 本分类仅用于与其他转译/答案产物在 {@code llm_cache} 内互不串键。
     */
    QUERY_IMAGE_TRANSCRIBE
}