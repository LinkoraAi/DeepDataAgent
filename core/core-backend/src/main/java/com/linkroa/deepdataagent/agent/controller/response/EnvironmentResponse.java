package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * 运行环境响应 DTO
 */
public record EnvironmentResponse(
        String environmentId,
        String name,
        String type,
        SandboxSpecResponse sandboxSpec,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}