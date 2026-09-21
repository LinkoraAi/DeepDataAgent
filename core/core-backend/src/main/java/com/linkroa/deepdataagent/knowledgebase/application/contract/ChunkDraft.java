package com.linkroa.deepdataagent.knowledgebase.application.contract;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 切片回写草稿（跨 BC 契约输入，Published Language）。
 * <p>由消费方 rag BC 的摄入管线产出，作为
 * {@link com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter#replaceForDocument} 的入参元素。
 * 契约只承载「知识库侧需要落库的事实」，不复制知识库领域对象：内容形态以字符串传递，由知识库侧
 * 解析为领域枚举；来源文件名等可从文档自身派生的信息不在本契约中重复传递。</p>
 *
 * <p>向量由消费方（rag BC）计算后随草稿一并传入：知识库侧事务内禁止远程调用，
 * 因此<b>不</b>自行调用 embedding 模型。向量为空表示本条草稿暂无向量，
 * 知识库侧不生成 {@code chunk_vector} 行。</p>
 *
 * <p>全文表示（tsvector）<b>不再由消费方预先生成</b>：分词职责已移交知识库存储侧，
 * 由写入 SQL 以 {@code to_tsvector} 按中文全文检索配置对 {@code content} 原文现算，
 * 每条非空白切片必然生成 {@code chunk_tsv} 行。</p>
 *
 * @param sequence         块在文档内的序号，从 1 开始，必填且在一次回写内不得重复
 * @param content          块正文（套模板后的最终文本），必填；同时作为全文表示的原文来源
 * @param tokens           块的 token 数量，可为空（空表示未知，落库为 NULL）
 * @param metadata         多模态原始信息 JSON，可为空，原样透传落 {@code chunk.original_item}
 * @param chunkContentType 内容形态（TEXT/IMAGE/TABLE 等领域枚举名），可为空（空表示 TEXT）
 * @param embeddingVector  向量字面量（pgvector 文本格式，形如 {@code [0.1,0.2,...]}），
 *                         由消费方计算，可为空表示不回写向量
 */
public record ChunkDraft(
        Integer sequence,
        String content,
        Integer tokens,
        String metadata,
        String chunkContentType,
        String embeddingVector
) {

    /**
     * 紧凑构造器：契约边界校验。
     */
    public ChunkDraft {
        if (ObjectUtils.isEmpty(sequence)) {
            throw new IllegalArgumentException("切片序号不能为空");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("切片序号必须从1开始");
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("切片内容不能为空");
        }
        if (ObjectUtils.isNotEmpty(tokens) && tokens < 0) {
            throw new IllegalArgumentException("切片token数量不能为负数");
        }
    }
}
