package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * Agent 定义领域模型（对应 agent_definition 表）
 *
 * @param id            数据库主键
 * @param agentId       业务唯一ID
 * @param name          名称（≤64字符，唯一）
 * @param description   描述
 * @param archived      是否归档
 * @param archivedAt    归档时间
 * @param latestVersion 最新发布号
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @param createdBy     创建人
 * @param updatedBy     更新人
 */
public record AgentDefinition(
        Long id,
        String agentId,
        String name,
        String description,
        boolean archived,
        OffsetDateTime archivedAt,
        int latestVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,63}$");

    /**
     * 紧凑构造器：不变量校验
     */
    public AgentDefinition {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Agent名称不能为空");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("Agent名称长度不能超过64个字符");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Agent名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        if (StringUtils.isNotEmpty(description) && description.length() > 500) {
            throw new IllegalArgumentException("描述不能超过500个字符");
        }
        if (latestVersion < 0) {
            throw new IllegalArgumentException("最新版本号不能为负数");
        }
    }

    /**
     * 创建新的 Agent 定义（默认 latest_version = 0，创建后将随首个版本发布置 1）
     */
    public static AgentDefinition create(String agentId, String name, String description) {
        return new AgentDefinition(
                null, agentId, name, description, false, null, 0,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static AgentDefinition restore(
            Long id,
            String agentId,
            String name,
            String description,
            boolean archived,
            OffsetDateTime archivedAt,
            int latestVersion,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new AgentDefinition(
                id, agentId, name, description, archived, archivedAt, latestVersion,
                createdAt, updatedAt, createdBy, updatedBy
        );
    }
}