package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新切片请求（仅支持修改切片内容与 token 数）。
 *
 * @param chunkContent 切片内容
 * @param tokens       切片 token 数量
 */
public record UpdateChunkRequest(

        @NotBlank(message = "切片内容不能为空")
        String chunkContent,

        Integer tokens
) {
}
