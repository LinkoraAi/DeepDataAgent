package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * 部署响应 DTO（审计记录）
 */
public record DeploymentResponse(
        String deploymentId,
        String agentId,
        int versionNumber,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}