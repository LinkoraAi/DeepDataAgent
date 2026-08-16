package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * Agent 定义响应 DTO（列表项：定义信息 + 最新发布号）
 */
public record AgentResponse(
        String agentId,
        String name,
        String description,
        boolean archived,
        OffsetDateTime archivedAt,
        int latestVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}