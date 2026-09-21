package com.linkroa.deepdataagent.knowledgebase.controller.request;

/**
 * 重新解析文档请求（请求体可为空，表示完全沿用知识库当前分块配置）。
 *
 * @param chunkStrategyOverride 本次重建使用的分块策略 JSON 覆盖值；为空表示沿用知识库当前分块配置快照
 */
public record ReparseDocumentRequest(
        String chunkStrategyOverride
) {
}
