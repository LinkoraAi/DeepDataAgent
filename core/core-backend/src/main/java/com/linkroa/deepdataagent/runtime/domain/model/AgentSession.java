package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Agent 会话领域模型（对应 agent_session 表）。
 * <p>状态机：IDLE → RUNNING → IDLE / TERMINATED；RUNNING 表示正在执行，
 * 且同一会话同一时刻仅允许一个执行，由仓储 {@code tryMarkRunning} 原子 CAS 保证并发安全。</p>
 */
public record AgentSession(
        Long id,
        String sessionId,
        String userId,
        String agentId,
        String agentVersion,
        AgentSessionStatus status,
        String metadata,
        String sandboxId,
        String title,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    public AgentSession {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("AgentID不能为空");
        }
        if (StringUtils.isBlank(agentVersion)) {
            throw new IllegalArgumentException("Agent版本不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("会话状态不能为空");
        }
        if (title != null && title.length() > 255) {
            throw new IllegalArgumentException("会话标题长度不能超过255");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("会话元数据不能为空");
        }
    }

    /**
     * 创建新会话（IDLE 初始态）。
     */
    public static AgentSession create(
            String userId,
            String agentId,
            String agentVersion,
            String metadata,
            String title
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new AgentSession(
                null,
                UUID.randomUUID().toString().replace("-", ""),
                userId,
                agentId,
                agentVersion,
                AgentSessionStatus.IDLE,
                metadata == null || metadata.isBlank() ? "{}" : metadata,
                null,
                title,
                now,
                now,
                now,
                null,
                null
        );
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static AgentSession restore(
            Long id,
            String sessionId,
            String userId,
            String agentId,
            String agentVersion,
            AgentSessionStatus status,
            String metadata,
            String sandboxId,
            String title,
            OffsetDateTime lastActiveAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, status,
                metadata == null ? "{}" : metadata,
                sandboxId, title, lastActiveAt, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * 派生 RUNNING 态会话（CAS 后用于回填内存视图）。
     */
    public AgentSession withStatus(AgentSessionStatus nextStatus) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new AgentSession(
                id, sessionId, userId, agentId, agentVersion, nextStatus,
                metadata, sandboxId, title, now, createdAt, now, createdBy, updatedBy
        );
    }
}