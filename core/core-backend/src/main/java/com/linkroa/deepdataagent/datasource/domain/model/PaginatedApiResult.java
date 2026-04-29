package com.linkroa.deepdataagent.datasource.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 分页API结果值对象
 *
 * @author system
 * @since 2026-05-15
 */
public record PaginatedApiResult(
        List<Map<String, Object>> data,
        Integer totalCount,
        String nextCursor,
        boolean hasMore
) {
}