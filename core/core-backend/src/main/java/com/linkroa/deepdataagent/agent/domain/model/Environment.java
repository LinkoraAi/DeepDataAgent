package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 运行环境领域模型（对应 environment 表）。
 *
 * <p>运行环境是 Agent 版本的 Hands 执行规格（per-Agent 沙箱），本期仅支持 {@code LOCAL}
 * 本地 Docker 服务；沙箱规格封装为值对象 {@link SandboxSpec}。</p>
 *
 * @param id            数据库主键
 * @param environmentId 运行环境业务唯一ID
 * @param name          名称（≤64字符，唯一）
 * @param type          环境类型（本期仅 LOCAL）
 * @param sandboxSpec   沙箱执行规格（值对象）
 * @param workspaceId   工作空间归属（本期占位，默认值兜底，不做边界校验）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @param createdBy     创建人
 * @param updatedBy     更新人
 */
public record Environment(
        Long id,
        String environmentId,
        String name,
        EnvironmentType type,
        SandboxSpec sandboxSpec,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,63}$");

    /**
     * 紧凑构造器：不变量校验
     */
    public Environment {
        if (StringUtils.isBlank(environmentId)) {
            throw new IllegalArgumentException("运行环境ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("运行环境名称不能为空");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("运行环境名称长度不能超过64个字符");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("运行环境名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        if (type == null) {
            throw new IllegalArgumentException("环境类型不能为空");
        }
        if (sandboxSpec == null) {
            throw new IllegalArgumentException("沙箱规格不能为空");
        }
    }

    /**
     * 创建新的运行环境
     */
    public static Environment create(String environmentId, String name, EnvironmentType type,
                                     SandboxSpec sandboxSpec, String workspaceId) {
        return new Environment(
                null, environmentId, name, type, sandboxSpec, workspaceId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static Environment restore(
            Long id,
            String environmentId,
            String name,
            EnvironmentType type,
            SandboxSpec sandboxSpec,
            String workspaceId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new Environment(id, environmentId, name, type, sandboxSpec, workspaceId,
                createdAt, updatedAt, createdBy, updatedBy);
    }
}