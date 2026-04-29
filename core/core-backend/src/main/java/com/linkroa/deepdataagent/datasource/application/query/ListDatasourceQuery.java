package com.linkroa.deepdataagent.datasource.application.query;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;

/**
 * 数据源列表查询
 */
public record ListDatasourceQuery(
        String keyword,
        DatasourceType type,
        DatasourceStatus status,
        int page,
        int size
) {
    public ListDatasourceQuery {
        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 20;
        }
    }
}
