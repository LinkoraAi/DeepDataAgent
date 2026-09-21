package com.linkroa.deepdataagent.knowledgebase.controller.response;

import java.time.OffsetDateTime;

/**
 * 切片响应 DTO。
 *
 * @param id               主键
 * @param kbId             所属知识库ID
 * @param documentId       所属文档ID
 * @param sequence         切片在文档内的序号
 * @param tokens           切片 token 数量
 * @param chunkContent     切片内容
 * @param originalItem     多模态原始信息 JSON
 * @param chunkContentType 内容形态（枚举名）
 * @param sourceFileName   来源文件名
 * @param sourceType       分块来源标识（ChunkSource 枚举名：PARSED=解析产生 / MANUAL=人工新增；
 *                         管理侧据此区分「可删除」与「仅可编辑」的切片）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 */
public record ChunkResponse(
        Long id,
        Long kbId,
        Long documentId,
        Integer sequence,
        Integer tokens,
        String chunkContent,
        String originalItem,
        String chunkContentType,
        String sourceFileName,
        String sourceType,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
