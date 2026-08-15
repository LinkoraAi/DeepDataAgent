package com.linkroa.deepdataagent.runtime.controller.response;

import java.util.List;

/**
 * 分页响应（使用 list 而非 data，避免与 ApiResponse.data 双层嵌套）。
 */
public record PaginatedResponse<T>(
        List<T> list,
        long total,
        int page,
        int size
) {
}