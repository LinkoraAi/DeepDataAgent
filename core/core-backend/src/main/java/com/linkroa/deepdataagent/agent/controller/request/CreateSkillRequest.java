package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建技能请求（multipart 元数据字段）
 */
public record CreateSkillRequest(

        @NotBlank(message = "技能名称不能为空")
        @Size(max = 255, message = "技能名称不能超过255个字符")
        String name,

        @Size(max = 1000, message = "描述不能超过1000个字符")
        String description,

        /** 技能类型（1=自定义 2=官方预留，缺省 1） */
        Integer skillType,

        /** 客户端声明的 SHA-256 校验值（可选） */
        String sha256
) {
}