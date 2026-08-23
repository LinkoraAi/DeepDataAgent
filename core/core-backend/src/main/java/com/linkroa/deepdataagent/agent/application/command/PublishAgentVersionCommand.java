package com.linkroa.deepdataagent.agent.application.command;

/**
 * 发布 Agent 新版本命令（全量替换，缺省字段视为清空）
 *
 * @param agentId            Agent 业务ID
 * @param name               版本名称（发布标签）
 * @param description        版本描述
 * @param system             系统提示词
 * @param modelProfileId     模型配置引用
 * @param skillIds           挂载技能 JSON（[{skillId, version}]）
 * @param knowledgeBaseIds   预留知识库引用 JSON
 * @param dataSourceIds      数据源引用 JSON（[数据源 id 数字数组]）
 * @param environmentId      运行环境引用环境ID（可空）
 * @param memoryStoreIds     记忆库引用 JSON（[记忆库 id 字符串数组]，可空）
 */
public record PublishAgentVersionCommand(
        String agentId,
        String name,
        String description,
        String system,
        String modelProfileId,
        String skillIds,
        String knowledgeBaseIds,
        String dataSourceIds,
        String environmentId,
        String memoryStoreIds
) {
}