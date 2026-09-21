package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 分块中间态值对象（仅内存，不落库）。
 * <p>sequence 由解析顺序决定，重解析时可能变化，故不作持久化键（幂等靠 documentId 级清空重建）。</p>
 *
 * @param sequence 块在文档内的序号（从 1 开始）
 * @param text     分块后的正文（唯一参与向量化与 LLM 抽取的字段，落库前已剥坐标标签）
 * @param tokens   块的 token 数量（真实 encode 口径）
 * @param block    来源内容块（携带 heading / sidecar / positions 等检索期元数据）
 */
public record ChunkVO(Integer sequence, String text, Integer tokens, ContentBlockVO block) {

    /**
     * 紧凑构造器：分块不变量校验。
     */
    public ChunkVO {
        if (ObjectUtils.isEmpty(sequence) || sequence < 1) {
            throw new IllegalArgumentException("切片序号必须从 1 开始");
        }
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException("切片内容不能为空");
        }
        if (ObjectUtils.isEmpty(tokens) || tokens < 0) {
            throw new IllegalArgumentException("切片 token 数不能为负");
        }
    }
}