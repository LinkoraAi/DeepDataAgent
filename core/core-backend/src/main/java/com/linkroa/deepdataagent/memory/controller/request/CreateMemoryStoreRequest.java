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

        @NotBlank(message = "记忆类型不能为空")
        String type
) {
}