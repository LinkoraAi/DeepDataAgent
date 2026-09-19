package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Agent 版本领域模型（对应 agent_version 表，每次发布生成一行快照）
 *
 * @param id                数据库主键
 * @param versionId         版本业务唯一ID
 * @param agentId           Agent业务ID
 * @param versionNumber     发布号（同一 Agent 内递增，MAX+1，无乐观锁）
 * @param name              版本名称（发布时从 Agent 定义复制，无独立发布标签入参）
 * @param description       版本描述（发布时从 Agent 定义复制）
 * @param systemPrompt      系统提示词（对外字段名 {@code system}；版本快照唯一指令载体）
 * @param modelProfileId    内部模型供应商配置引用（可空 = 目录模型未配置映射；不进入对外契约）
 * @param modelJson         模型引用（JSONB：目录模型 id 字符串简写或 {id, effort?, context_window?} 对象，见 {@link ModelRef}）
 * @param toolsJson         内联工具配方（JSONB：{@code [{type, ...}]}，见 {@link AgentTool}）
 * @param mcpServersJson    内联外部 MCP 工具源配方（JSONB：{@code [{name, type:"url", url}]}，见 {@link McpServer}）
 * @param skillsJson        技能绑定配方（{@code [{type, skill_id, version}]}，type 取 catalog / custom，见 {@link SkillBinding}）
 * @param multiagent        多智能体编排配置（JSONB；本期非空提交 400、响应恒 null）
 * @param metadataJson      业务自定义元数据（JSONB 键值对象，可空）
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 * @param createdBy         创建人
 * @param updatedBy         更新人
 */
public record AgentVersion(
        Long id,
        String versionId,
        String agentId,
        int versionNumber,
        String name,
        String description,
        String systemPrompt,
        String modelProfileId,
        String modelJson,
        String toolsJson,
        String mcpServersJson,
        String skillsJson,
        String multiagent,
        String metadataJson,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验
     */
    public AgentVersion {
        if (StringUtils.isBlank(versionId)) {
            throw new IllegalArgumentException("版本ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("发布版本号必须大于0");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("版本名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("版本名称长度不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        if (StringUtils.isBlank(modelProfileId) && StringUtils.isBlank(modelJson)) {
            throw new IllegalArgumentException("模型引用不能为空");
        }
        if (StringUtils.isNotEmpty(description) && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("版本描述不能超过" + MAX_DESCRIPTION_LENGTH + "个字符");
        }
        if (systemPrompt != null && systemPrompt.length() > MAX_SYS_PROMPT_LENGTH) {
            throw new IllegalArgumentException("系统提示词长度不能超过" + MAX_SYS_PROMPT_LENGTH + "个字符");
        }
    }

    /** 系统提示词长度上限（{@code system} 字段，100000 字符）。 */
    public static final int MAX_SYS_PROMPT_LENGTH = 100000;

    /** 版本名称长度上限（与公开契约 1-256 及 V1 列宽 {@code VARCHAR(256)} 一致）。 */
    public static final int MAX_NAME_LENGTH = 256;

    /** 版本描述长度上限（与公开契约 ≤2048 及 V1 列宽 {@code VARCHAR(2048)} 一致）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 2048;

    /**
     * 创建新的 Agent 版本快照
     */
    public static AgentVersion create(
            String versionId,
            String agentId,
            int versionNumber,
            String name,
            String description,
            String systemPrompt,
            String modelProfileId,
            String modelJson,
            String toolsJson,
            String mcpServersJson,
            String skillsJson,
            String multiagent,
            String metadataJson
    ) {
        return new AgentVersion(
                null, versionId, agentId, versionNumber, name, description,
                systemPrompt != null ? systemPrompt : "", modelProfileId, modelJson,
                toolsJson, mcpServersJson, skillsJson,
                multiagent, metadataJson,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static AgentVersion restore(
            Long id,
            String versionId,
            String agentId,
            int versionNumber,
            String name,
            String description,
            String systemPrompt,
            String modelProfileId,
            String modelJson,
            String toolsJson,
            String mcpServersJson,
            String skillsJson,
            String multiagent,
            String metadataJson,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new AgentVersion(
                id, versionId, agentId, versionNumber, name, description,
                systemPrompt, modelProfileId, modelJson,
                toolsJson, mcpServersJson, skillsJson,
                multiagent, metadataJson,
                createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 解析本版本挂载的技能绑定列表（{@code [{type, skill_id, version}]}，type 取 catalog / custom）。
     */
    public List<SkillBinding> parseSkills() {
        return SkillBinding.parse(skillsJson);
    }

    /**
     * 静态解析技能绑定配方（供发布完整性校验 / 运行装配复用）。
     */
    public static List<SkillBinding> parseSkills(String skillsJson) {
        return SkillBinding.parse(skillsJson);
    }

    /**
     * 解析本版本的模型引用（字符串简写或对象形态；未配置返回 {@code null}）。
     */
    public ModelRef parseModel() {
        return ModelRef.parse(modelJson);
    }

    /**
     * 解析本版本挂载的工具配置列表（{@code [{type, ...}]}，四类型见 {@link AgentTool}）。
     */
    public List<AgentTool> parseTools() {
        return AgentTool.parse(toolsJson);
    }

    /**
     * 解析本版本声明的 MCP 服务器列表（{@code [{name, type:"url", url}]}）。
     */
    public List<McpServer> parseMcpServers() {
        return McpServer.parse(mcpServersJson);
    }

    /**
     * 解析业务自定义元数据键值对象（空白配方返回空 Map）。
     */
    public Map<String, Object> parseMetadata() {
        return parseMetadata(metadataJson);
    }

    /**
     * 静态解析元数据配方（供发布校验 / 协议转换复用）。
     */
    public static Map<String, Object> parseMetadata(String metadataJson) {
        if (StringUtils.isBlank(metadataJson)) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("元数据JSON解析失败", e);
        }
    }

}