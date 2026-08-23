package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;
import java.util.List;

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
        /** 结构化资源：参考资料文件相对路径 */
        List<String> references,
        /** 结构化资源：脚本文件相对路径 */
        List<String> scripts,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}