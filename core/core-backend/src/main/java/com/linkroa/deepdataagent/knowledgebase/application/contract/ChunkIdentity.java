package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.apache.commons.lang3.ObjectUtils;

/**
 * 切片身份投影（跨 BC 发布语言）：仅携带切片序号与真实主键的轻量对。
 * <p>用于摄入产物溯源：RAG 侧以 {@code sequence} 组织中间产物（如向量、图谱节点归属），
 * 摄入回写完成后通过本投影把 {@code sequence → 真实 chunkId} 回填到自有表，
 * 避免跨 BC 传递切片重内容（正文、多模态原始信息）。</p>
 *
 * @param sequence 块在文档内的序号（非负）
 * @param chunkId  切片真实主键（正数）
 */
public record ChunkIdentity(
        Integer sequence,
        Long chunkId
) {

    /**
     * 紧凑构造器：不变量校验。
     *
     * @throws IllegalArgumentException 序号为空或为负、切片主键为空或非正
     */
    public ChunkIdentity {
        if (ObjectUtils.isEmpty(sequence) || sequence < 0) {
            throw new IllegalArgumentException("切片序号不能为空且不能为负");
        }
        if (ObjectUtils.isEmpty(chunkId) || chunkId <= 0) {
            throw new IllegalArgumentException("切片主键不能为空且必须为正数");
        }
    }
}
