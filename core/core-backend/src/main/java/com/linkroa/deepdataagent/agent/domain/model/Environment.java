package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 运行环境领域模型（对应 environment 表，Environment 原语）。
 *
 * <p>环境规格由结构化配置 {@link EnvironmentConfig}（type / packages / setup_script）承载，
 * 旧自建沙箱明细（镜像 / 内存 / CPU / 工作目录模式 / 超时）随 config 结构化改造移除；
 * 自定义 {@code metadata} 为 key/value JSON 文本（空白归一为 {@code "{}"}）。
 * 归档以 {@code archived_at} 时间戳单列表达（无独立布尔状态），已归档环境不可被新
 * Session 引用（{@code agent.api.EnvironmentApi} 解析返回 null → 消费方 404）。</p>
 *
 * @param id            数据库主键
 * @param environmentId 运行环境业务唯一ID
 * @param name          名称（仅非空校验，公开契约不设长度上限）
 * @param description   描述（≤2048字符，缺省归一为空串）
 * @param config        环境配置（值对象，缺省 {@code {"type":"cloud"}}）
 * @param metadata      自定义元数据 JSON 文本（空白归一为 {@code "{}"}）
 * @param ownerId       归属用户 ID（数字）
 * @param archivedAt    归档时间（NULL=未归档）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @param createdBy     创建人
 * @param updatedBy     更新人
 */
public record Environment(
        Long id,
        String environmentId,
        String name,
        String description,
        EnvironmentConfig config,
        String metadata,
        Long ownerId,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /** 描述长度上限（与公开契约及 V1 列宽 {@code VARCHAR(2048)} 一致）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 2048;

    /**
     * 紧凑构造器：不变量校验与归一
     */
    public Environment {
        if (StringUtils.isBlank(environmentId)) {
            throw new IllegalArgumentException("运行环境ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("运行环境名称不能为空");
        }
        if (StringUtils.isNotEmpty(description) && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("描述不能超过" + MAX_DESCRIPTION_LENGTH + "个字符");
        }
        if (config == null) {
            throw new IllegalArgumentException("环境配置不能为空");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("运行环境归属用户不能为空");
        }
        description = description == null ? "" : description;
        metadata = StringUtils.isBlank(metadata) ? "{}" : metadata;
    }

    /** 运行环境业务 ID 前缀（shared/api-conventions：资源 ID 语义前缀，应用层创建时装配）。 */
    public static final String ENVIRONMENT_ID_PREFIX = "env_";

    /**
     * 创建新的运行环境
     */
    public static Environment create(String environmentId, String name, String description,
                                     EnvironmentConfig config, String metadata, Long ownerId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new Environment(
                null, environmentId, name, description,
                config == null ? EnvironmentConfig.cloudDefault() : config,
                metadata, ownerId, null, now, now, null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static Environment restore(
            Long id,
            String environmentId,
            String name,
            String description,
            EnvironmentConfig config,
            String metadata,
            Long ownerId,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new Environment(id, environmentId, name, description,
                config == null ? EnvironmentConfig.cloudDefault() : config,
                metadata, ownerId, archivedAt, createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 是否已归档（归档以时间戳表达，无独立布尔状态）。
     */
    public boolean isArchived() {
        return archivedAt != null;
    }

    /**
     * 设置归档时间后返回新快照（{@code null} = 取消归档）。
     *
     * @param archivedAt 归档时间（NULL = 未归档）
     * @return 归档状态更新后的新快照（不可变派生）
     */
    public Environment withArchivedAt(OffsetDateTime archivedAt) {
        return new Environment(
                id, environmentId, name, description, config, metadata, ownerId, archivedAt,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), createdBy, updatedBy
        );
    }
}