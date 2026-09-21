package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * LLM 调用缓存值对象（对应 {@code llm_cache} 表）。
 * <p>缓存键 = MD5(model + prompt + 归一化参数) 的 32 位 hex 串（不含 kb_id）；
 * 命中检索携带 kb_id 与缓存分类走复合唯一键 {@code (kb_id, cache_type, cache_key)}，
 * 实现库级隔离不跨库共享、不同 LLM 产物分类互不碰撞。</p>
 *
 * @param id          主键
 * @param kbId        所属知识库ID（缓存隔离与按库整清的前置维度）
 * @param cacheKey    缓存键（MD5 hex，长度 32）
 * @param cacheType   缓存分类（复合唯一键组成部分，不可为空）
 * @param model       模型标识
 * @param prompt      完整提示词
 * @param response    模型返回内容
 * @param totalTokens 输入+输出 token 总量
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record LlmCacheEntry(
        Long id,
        Long kbId,
        String cacheKey,
        CacheType cacheType,
        String model,
        String prompt,
        String response,
        Integer totalTokens,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 紧凑构造器：不变量校验。
     */
    public LlmCacheEntry {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("LLM 缓存必须关联知识库");
        }
        if (ObjectUtils.isEmpty(cacheType)) {
            throw new IllegalArgumentException("缓存分类不能为空");
        }
        if (StringUtils.isBlank(cacheKey)) {
            throw new IllegalArgumentException("缓存键不能为空");
        }
        if (cacheKey.length() != 32) {
            throw new IllegalArgumentException("缓存键必须为 32 位 MD5 hex");
        }
    }

    /**
     * 新建缓存条目（未命中回写入口）。
     *
     * @param kbId        所属知识库ID
     * @param cacheKey    缓存键（32 位 MD5 hex）
     * @param cacheType   缓存分类（不可为空）
     * @param model       模型标识
     * @param prompt      完整提示词
     * @param response    模型返回内容
     * @param totalTokens 输入+输出 token 总量（可为 null）
     * @return 初始缓存条目
     */
    public static LlmCacheEntry create(Long kbId, String cacheKey, CacheType cacheType, String model,
                                       String prompt, String response, Integer totalTokens) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new LlmCacheEntry(null, kbId, cacheKey, cacheType, model, prompt, response, totalTokens, now, now);
    }

    /**
     * 从数据库恢复缓存条目。
     */
    public static LlmCacheEntry restore(Long id, Long kbId, String cacheKey, CacheType cacheType, String model,
                                        String prompt, String response, Integer totalTokens,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new LlmCacheEntry(id, kbId, cacheKey, cacheType, model, prompt, response, totalTokens,
                createdAt, updatedAt);
    }
}