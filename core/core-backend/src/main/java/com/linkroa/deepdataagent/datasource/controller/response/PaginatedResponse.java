package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;

/**
 * 分页响应
 * <p>使用 list 而非 data 作为字段名，避免与 ApiResponse.data 双层嵌套。</p>
 */
public record PaginatedResponse<T>(
        List<T> list,
        long total,
        int page,
        int size
) {
}
