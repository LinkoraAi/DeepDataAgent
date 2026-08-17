package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Agent 配置请求（创建与发布版本复用同一请求体「配置即版本」；发布时缺省字段视为清空）
 */
public record AgentConfigRequest(

        /** Agent/版本名称（发布时作为发布标签） */
        @NotBlank(message = "名称不能为空")
        @Size(max = 64, message = "名称不能超过64个字符")
        String name,

        @Size(max = 500, message = "描述不能超过500个字符")
        String description,

        /** 系统提示词 */
        String system,

        /** 模型配置引用（须存在且启用） */
        @NotBlank(message = "模型配置引用不能为空")
        String modelProfileId,

        /** 挂载技能 JSON（[{skillId, version}]） */
        String skillIds,

        /** 预留知识库引用 JSON */
        String knowledgeBaseIds,

        /** 数据源引用 JSON */
        String dataSourceIds
) {
}