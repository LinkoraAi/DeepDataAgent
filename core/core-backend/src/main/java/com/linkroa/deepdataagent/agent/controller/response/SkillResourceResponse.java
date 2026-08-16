package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * 技能资源响应 DTO（不返回二进制内容）
 */
public record SkillResourceResponse(
        String skillId,
        int versionNumber,
        String name,
        String description,
        Integer skillType,
        String storageType,
        String contentSha256,
        long contentSize,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}