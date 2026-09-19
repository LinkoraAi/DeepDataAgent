package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Agent 定义领域模型（对应 agent_definition 表）
 *
 * @param id            数据库主键
 * @param agentId       业务唯一ID
 * @param name          名称（1-256 字符，仅长度校验；不做 owner 内唯一约束）
 * @param description   描述（≤2048 字符，可空）
 * @param archivedAt    归档时间（NULL=未归档；对外仅以 archived_at 表达，不输出 archived 布尔）
 * @param latestVersion 最新发布号
 * @param activeVersion 当前生效版本号（默认随发布同步 latestVersion，可回滚）
 * @param ownerId       归属用户 ID（数字）
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
        OffsetDateTime archivedAt,
        int latestVersion,
        int activeVersion,
        Long ownerId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /** 名称长度上限（与公开契约 1-256 及 V1 列宽 {@code VARCHAR(256)} 一致）。 */
    public static final int MAX_NAME_LENGTH = 256;

    /** 描述长度上限（与公开契约 ≤2048 及 V1 列宽 {@code VARCHAR(2048)} 一致）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 2048;

    /**
     * 紧凑构造器：不变量校验
     */
    public AgentDefinition {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Agent名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Agent名称长度不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        if (StringUtils.isNotEmpty(description) && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("描述不能超过" + MAX_DESCRIPTION_LENGTH + "个字符");
        }
        if (latestVersion < 0) {
            throw new IllegalArgumentException("最新版本号不能为负数");
        }
        if (activeVersion < 0) {
            throw new IllegalArgumentException("激活版本号不能为负数");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("Agent归属用户不能为空");
        }
    }

    /** Agent 业务 ID 前缀（shared/api-conventions：资源 ID 语义前缀，应用层创建时装配）。 */
    public static final String AGENT_ID_PREFIX = "agent_";

    /**
     * 创建新的 Agent 定义（默认 latest_version = 0，创建事务内随首个版本快照置 1）。
     */
    public static AgentDefinition create(String agentId, String name, String description, Long ownerId) {
        return new AgentDefinition(
                null, agentId, name, description, null, 0, 0, ownerId,
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
            OffsetDateTime archivedAt,
            int latestVersion,
            int activeVersion,
            Long ownerId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new AgentDefinition(
                id, agentId, name, description, archivedAt, latestVersion, activeVersion, ownerId,
                createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * 是否已归档（归档以时间戳表达，无独立布尔状态）。
     */
    public boolean isArchived() {
        return archivedAt != null;
    }

    /**
     * 推进最新发布号（active_version 默认随发布同步）后返回新快照。
     */
    public AgentDefinition withLatestVersion(int latestVersion) {
        return new AgentDefinition(
                id, agentId, name, description, archivedAt, latestVersion, latestVersion,
                ownerId, createdAt,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), createdBy, updatedBy
        );
    }

    /**
     * 更新定义属性（名称/描述）后返回新快照（OCC 更新端点专用；版本号推进由
     * {@link #withLatestVersion(int)} 随新版本发布另行派生）。
     *
     * @param name        新名称（非空，1-256 长度校验）
     * @param description 新描述（可空 = 清空）
     * @return 属性更新后的新快照（不可变派生）
     * @throws IllegalArgumentException 名称/描述违反不变量
     */
    public AgentDefinition withNameAndDescription(String name, String description) {
        return new AgentDefinition(
                id, agentId, name, description, archivedAt, latestVersion, activeVersion,
                ownerId, createdAt,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), createdBy, updatedBy
        );
    }

    /**
     * 设置归档时间后返回新快照（{@code null} = 取消归档）。
     *
     * @param archivedAt 归档时间（NULL = 未归档）
     * @return 归档状态更新后的新快照
     */
    public AgentDefinition withArchivedAt(OffsetDateTime archivedAt) {
        return new AgentDefinition(
                id, agentId, name, description, archivedAt, latestVersion, activeVersion,
                ownerId, createdAt,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), createdBy, updatedBy
        );
    }

    /**
     * 切换激活版本（发布激活 / 回滚）后返回新快照：激活号必须落在已发布台账区间
     * {@code [1, latestVersion]}，{@code latestVersion} 不变；更新时间推进为当前时刻。
     *
     * @param versionNumber 目标激活版本号
     * @return 激活版本切换后的新快照（不可变派生）
     * @throws IllegalArgumentException 激活号越出已发布台账区间
     */
    public AgentDefinition withActivatedVersion(int versionNumber) {
        if (versionNumber < 1 || versionNumber > latestVersion) {
            throw new IllegalArgumentException("激活版本号必须在已发布版本范围 [1, " + latestVersion + "] 内");
        }
        return new AgentDefinition(
                id, agentId, name, description, archivedAt, latestVersion, versionNumber,
                ownerId, createdAt,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), createdBy, updatedBy
        );
    }
}