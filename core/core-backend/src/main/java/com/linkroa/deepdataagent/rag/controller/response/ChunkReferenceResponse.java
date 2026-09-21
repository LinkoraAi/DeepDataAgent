package com.linkroa.deepdataagent.rag.controller.response;

/**
 * chunk 明细响应 DTO。
 * <p>响应体中单条命中切片的展示形态，按检索相关性序排列（切片形态端点为精排序），
 * 承载切片正文、来源文件、多模态资源可访问路径与相关性分值。多模态资源仅图片切片有值，
 * 文本切片该字段为 {@code null}，前端据此判定「有图可点击查看」；<b>不</b>外露
 * {@code original_item} 等内部原始数据结构与桶凭据明细（资源路径经后端预览代理端点收敛）。</p>
 *
 * @param chunkId      chunk 唯一标识
 * @param chunkContent chunk 正文（入库后的最终文本；切片已删除或明细回取失败时为 {@code null}）
 * @param sourceFile   来源文件名（可为 {@code null}）
 * @param modalFile    多模态资源可访问路径（后端在线预览端点相对 URL，浏览器点击可直接查看；
 *                     非图片切片为 {@code null}）
 * @param score        相关性分值（RRF 累积分或重排分，越高越相关）
 */
public record ChunkReferenceResponse(
        Long chunkId,
        String chunkContent,
        String sourceFile,
        String modalFile,
        double score
) {
}
