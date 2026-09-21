package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 更新知识库实体类型配置命令。
 * <p>实体类型用于图谱构建与元数据抽取，配置变更后需重解析文档方能生效。</p>
 *
 * @param kbId            知识库ID
 * @param entityTypeConfig 实体类型配置 JSON
 */
public record UpdateEntityTypeConfigCommand(
        Long kbId,
        String entityTypeConfig
) {
}
