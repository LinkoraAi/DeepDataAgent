package com.linkroa.deepdataagent.agent.application.command;

/**
 * 发布 Agent 新版本命令（全量替换；版本快照 name/description 为发布时刻定义属性复制）。
 * <p>{@code name}/{@code description} 为可选的<b>定义属性</b>更新入参（与创建请求共用全配置形状），
 * 应用服务在同事务内同步定义属性并复制进版本快照；不传则沿用当前定义属性。</p>
 *
 * @param agentId            Agent 业务ID
 * @param name               可选的定义名称更新（null = 沿用现名）
 * @param description        可选的定义描述更新（null = 沿用现描述）
 * @param systemPrompt       系统提示词（对外字段名 {@code system}，≤100000）
 * @param modelJson          模型引用 JSON（目录模型 id 字符串或 {id, effort?, context_window?} 对象序列化结果）
 * @param toolsJson          内联工具配方 JSON（可空）
 * @param mcpServersJson     内联外部 MCP 工具源配方 JSON（可空）
 * @param skillsJson         技能绑定配方 JSON（[{type, skill_id, version?}]）
 * @param multiagent         多智能体编排配置 JSON（本期非空一律 400）
 * @param metadataJson       业务自定义元数据 JSON（可空）
 */
public record PublishAgentVersionCommand(
        String agentId,
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