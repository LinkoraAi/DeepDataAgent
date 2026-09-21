package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新知识库检索策略配置请求。
 *
 * @param retrievalStrategy 检索策略 JSON
 */
public record UpdateRetrievalConfigRequest(

        @NotBlank(message = "检索策略配置不能为空")
        String retrievalStrategy
) {
}
