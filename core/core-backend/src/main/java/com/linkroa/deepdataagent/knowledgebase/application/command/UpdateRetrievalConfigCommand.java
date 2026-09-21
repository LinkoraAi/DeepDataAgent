package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 更新知识库检索策略配置命令。
 * <p>检索配置影响查询侧行为，保存后对新会话生效，无需重解析文档。</p>
 *
 * @param kbId              知识库ID
 * @param retrievalStrategy 检索策略配置 JSON
 */
public record UpdateRetrievalConfigCommand(
        Long kbId,
        String retrievalStrategy
) {
}
