package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 重新解析文档命令（重置文档状态为 PENDING，由 RAG 上下文的摄入任务重新解析）。
 *
 * @param id                   文档ID
 * @param chunkStrategyOverride 本次重建使用的分块策略 JSON 覆盖值；为空表示沿用知识库当前分块配置快照
 */
public record ReparseDocumentCommand(
        Long id,
        String chunkStrategyOverride
) {
}
