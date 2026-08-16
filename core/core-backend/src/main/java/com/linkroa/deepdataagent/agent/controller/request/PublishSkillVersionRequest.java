package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.Size;

/**
 * 发布技能新版本请求（multipart 元数据字段）
 */
public record PublishSkillVersionRequest(

        @Size(max = 1000, message = "描述不能超过1000个字符")
        String description,

        /** 客户端声明的 SHA-256 校验值（可选） */
        String sha256
) {
}