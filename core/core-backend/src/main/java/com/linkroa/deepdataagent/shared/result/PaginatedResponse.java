package com.linkroa.deepdataagent.shared.result;

import java.util.List;

/**
 * 分页响应（跨上下文共享载体）。
 * <p>使用 list 而非 data 作为字段名，避免与 ApiResponse.data 双层嵌套。
 * 归属 shared 层作为通用协议对象，禁止各限界上下文重复维护同构副本。</p>
 */
public record PaginatedResponse<T>(
        List<T> list,
        long total,
        int page,
        int size
) {
}