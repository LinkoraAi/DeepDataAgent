package com.linkroa.deepdataagent.agent.application.command;

/**
 * 创建 Agent 命令（创建即生成 v1 快照）
 *
 * @param name               Agent 名称
 * @param description        Agent 描述
 * @param system             系统提示词
 * @param modelProfileId     模型配置引用
 * @param skillIds           挂载技能 JSON（[{skillId, version}]）
 * @param knowledgeBaseIds   预留知识库引用 JSON
 * @param dataSourceIds      数据源引用 JSON
 */
public record CreateAgentCommand(
        String name,
        String description,
        String system,
        String modelProfileId,
        String skillIds,
        String knowledgeBaseIds,
        String dataSourceIds
) {
}