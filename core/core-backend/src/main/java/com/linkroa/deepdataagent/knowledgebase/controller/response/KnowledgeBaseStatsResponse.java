package com.linkroa.deepdataagent.knowledgebase.controller.response;

/**
 * 知识库总览统计响应 DTO。
 *
 * @param totalKnowledgeBases 知识库总数
 * @param totalDocuments      文档总数
 * @param totalChunks         切片总数
 */
public record KnowledgeBaseStatsResponse(
        long totalKnowledgeBases,
        long totalDocuments,
        long totalChunks
) {
}
