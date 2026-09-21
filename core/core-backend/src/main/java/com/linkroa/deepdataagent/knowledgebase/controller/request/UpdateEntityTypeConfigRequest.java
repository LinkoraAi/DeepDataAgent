package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新知识库实体类型配置请求。
 *
 * @param entityTypeConfig 实体类型自定义配置 JSON
 */
public record UpdateEntityTypeConfigRequest(

        @NotBlank(message = "实体类型配置不能为空")
        String entityTypeConfig
) {
}
