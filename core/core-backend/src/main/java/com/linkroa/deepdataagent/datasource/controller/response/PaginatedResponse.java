package com.linkroa.deepdataagent.datasource.controller.response;

import java.util.List;

/**
 * 分页响应
 */
public record PaginatedResponse<T>(
        List<T> data,
        long total,
        int page,
        int size
) {
}
