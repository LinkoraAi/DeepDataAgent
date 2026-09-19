package com.linkroa.deepdataagent.agent.application.command;

/**
 * 创建 Agent 命令（创建即首版：definition + version=1 快照单事务落库）
 *
 * @param name               Agent 名称（1-256 长度校验）
 * @param description        Agent 描述（≤2048，可空）
 * @param systemPrompt       系统提示词（对外字段名 {@code system}，≤100000）
 * @param modelJson          模型引用 JSON（目录模型 id 字符串或 {id, effort?, context_window?} 对象序列化结果）
 * @param toolsJson          内联工具配方 JSON（可空）
 * @param mcpServersJson     内联外部 MCP 工具源配方 JSON（可空）
 * @param skillsJson         技能绑定配方 JSON（[{type, skill_id, version?}]）
 * @param multiagent         多智能体编排配置 JSON（本期非空一律 400）
 * @param metadataJson       业务自定义元数据 JSON（可空）
 */
public record CreateAgentCommand(
        String name,
        String description,
        String systemPrompt,
        String modelJson,
        String toolsJson,
        String mcpServersJson,
        String skillsJson,
        String multiagent,
        String metadataJson
) {
}