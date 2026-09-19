package com.linkroa.deepdataagent.memory.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 更新记忆条目请求（OCC 乐观并发：必须携带客户端读取到的当前 version，
 * 与存储版本不符返回 409；path 更新时不可变，故不在请求中）。
 *
 * @param content 新内容（UTF-8 ≤100KB）
 * @param version 客户端持有的当前版本号（OCC 校验）
 * @param metadata 新元数据（null=沿用现有；非 null 时整体替换）
 */
public record UpdateMemoryEntryRequest(

        @NotNull(message = "记忆内容不能为null")
        String content,

        @NotNull(message = "记忆版本号不能为null")
        @Min(value = 1, message = "记忆版本号必须大于0")
        Integer version,

        Map<String, String> metadata
) {
}
