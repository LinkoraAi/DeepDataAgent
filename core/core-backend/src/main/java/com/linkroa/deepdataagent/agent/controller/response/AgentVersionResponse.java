package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * Agent 版本响应 DTO（版本快照，含模型配置引用与技能挂载）
 */
public record AgentVersionResponse(
        String versionId,
        String agentId,
        int versionNumber,
        String name,
        String description,
        String system,
        String modelProfileId,
        String skillIds,
        String knowledgeBaseIds,
        String dataSourceIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}