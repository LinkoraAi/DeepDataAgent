package com.linkroa.deepdataagent.rag.application.contract;

import org.apache.commons.lang3.StringUtils;

/**
 * 检索切片明细值对象（应用契约，对外响应 chunk 明细的唯一来源）。
 * <p>由检索编排层在 Stage 4 之后按图谱有序切片组装：正文与多模态图片引用经
 * {@code KnowledgeBaseApi} 只读通道按库隔离维度批量回取（回取失败或切片已删时
 * 正文字段置空，不影响检索主产物）。多模态资源仅以对象键单分量承载（桶概念已退役），
 * 可访问的资源 URL 由协议层（controller convert）按切片 ID 拼接预览端点，
 * 应用层不感知 HTTP 路径形态。</p>
 *
 * @param chunkId        切片主键
 * @param chunkContent   切片正文（入库后的最终文本；切片已删除或回取失败时为 {@code null}）
 * @param sourceFile     来源文件名（可为 {@code null}）
 * @param mediaObjectKey 多模态图片对象键（无图片引用为 {@code null}）
 * @param score          相关性分值（RRF 累积分或重排分，越高越相关）
 */
public record RetrievalChunkView(
        Long chunkId,
        String chunkContent,
        String sourceFile,
        String mediaObjectKey,
        double score
) {

    /**
     * 紧凑构造器：媒体对象键空白即归一为 {@code null}，
     * 使协议层只需判定一个分量即可确定「有无可查看的多模态资源」。
     */
    public RetrievalChunkView {
        if (StringUtils.isBlank(mediaObjectKey)) {
            mediaObjectKey = null;
        }
    }
}
