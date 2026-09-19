package com.linkroa.deepdataagent.memory.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建记忆库请求
 */
public record CreateMemoryStoreRequest(

        @NotBlank(message = "记忆库名称不能为空")
        @Size(max = 64, message = "记忆库名称不能超过64个字符")
        String name,

        @Size(max = 500, message = "记忆库描述不能超过500个字符")
        String description
) {
}