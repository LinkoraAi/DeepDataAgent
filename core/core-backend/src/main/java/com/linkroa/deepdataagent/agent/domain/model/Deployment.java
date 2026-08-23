package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 部署领域模型（对应 deployment 表）。
 *
 * <p>部署是 Agent 版本激活 / 回滚的审计记录，单向引用某 Agent 的目标版本号；
 * 激活后该版本成为 Agent 的 {@code active_version}，同一 Agent 同时仅有一个生效版本。</p>
 *
 * @param id            数据库主键
 * @param deploymentId  部署业务唯一ID
 * @param agentId       所属 Agent 业务ID
 * @param versionNumber 激活 / 回滚的目标版本号
 * @param workspaceId   工作空间归属（本期占位，默认值兜底，不做边界校验）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @param createdBy     创建人
 * @param updatedBy     更新人
 */
public record Deployment(
        Long id,
        String deploymentId,
        String agentId,
        int versionNumber,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /**
     * 紧凑构造器：不变量校验
     */
    public Deployment {
        if (StringUtils.isBlank(deploymentId)) {
            throw new IllegalArgumentException("部署ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("部署目标版本号必须大于0");
        }
    }

    /**
     * 创建新的部署审计记录
     */
    public static Deployment create(String deploymentId, String agentId, int versionNumber, String workspaceId) {
        return new Deployment(
                null, deploymentId, agentId, versionNumber, workspaceId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static Deployment restore(
            Long id,
            String deploymentId,
            String agentId,
            int versionNumber,
            String workspaceId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new Deployment(id, deploymentId, agentId, versionNumber, workspaceId,
                createdAt, updatedAt, createdBy, updatedBy);
    }
}