package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新模型配置请求（全量替换；credential 为 null 时保留原凭证，空串时清空凭证）
 */
public record UpdateModelProfileRequest(

        @NotBlank(message = "显示名称不能为空")
        @Size(max = 32, message = "显示名称不能超过32个字符")
        String displayName,

        @Size(max = 500, message = "描述不能超过500个字符")
        String description,

        @NotBlank(message = "API格式不能为空")
        String apiFormat,

        @NotBlank(message = "API端点URL不能为空")
        @Size(max = 512, message = "API端点URL不能超过512个字符")
        String apiEndpointUrl,

        @NotBlank(message = "模型名称不能为空")
        @Size(max = 128, message = "模型名称不能超过128个字符")
        String modelName,

        /** 凭证：null 表示保留原值，空串表示清空，其他表示更新 */
        String credential,

        @Size(max = 64, message = "模型系列不能超过64个字符")
        String modelSeries,

        Integer contextWindowInput,

        Integer contextWindowOutput,

        Integer toolCallRounds,

        /** 模型类型（1=CHAT 2=EMBEDDING） */
        Integer modelType,

        Integer vectorDimension
) {
}