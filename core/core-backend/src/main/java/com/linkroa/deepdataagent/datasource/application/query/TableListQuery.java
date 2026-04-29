package com.linkroa.deepdataagent.datasource.application.query;

/**
 * 表列表查询
 */
public record TableListQuery(
        Long connectionId,
        String keyword,
        int page,
        int size
) {
    public TableListQuery {
        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 50;
        }
    }
}
