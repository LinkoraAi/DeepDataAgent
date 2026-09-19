package com.linkroa.deepdataagent.memory.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 创建记忆条目请求（记忆库内以相对 path 首写，OCC 版本从 1 起）。
 *
 * @param path     记忆路径（相对，不以 / 开头；内容字节上限由领域校验）
 * @param content  文本内容（UTF-8 ≤100KB，空串合法）
 * @param metadata 自定义元数据（可空，≤16 对，key 1-64 / value ≤512 字符）
 */
public record CreateMemoryEntryRequest(

        @NotBlank(message = "记忆路径不能为空")
        @Size(max = 512, message = "记忆路径不能超过512个字符")
        String path,

        @NotNull(message = "记忆内容不能为null")
        String content,

        Map<String, String> metadata
) {
}
