package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import org.apache.commons.lang3.StringUtils;

/**
 * API分页配置值对象
 */
public record ApiPaginationConfig(
        ApiPaginationType paginationType,
        String pageParamName,
        String sizeParamName,
        String cursorParamName,
        String cursorJsonPath,
        String totalCountJsonPath,
        Integer pageSize,
        Integer maxPages
) {
    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 500;
    private static final int MAX_PAGES_MIN = 1;
    private static final int MAX_PAGES_MAX = 10000;

    public ApiPaginationConfig {
        if (paginationType == null) {
            throw new IllegalArgumentException("分页类型不能为空");
        }
        if (pageSize != null && (pageSize < PAGE_SIZE_MIN || pageSize > PAGE_SIZE_MAX)) {
            throw new IllegalArgumentException("每页大小必须在" + PAGE_SIZE_MIN + "到" + PAGE_SIZE_MAX + "之间");
        }
        if (maxPages != null && (maxPages < MAX_PAGES_MIN || maxPages > MAX_PAGES_MAX)) {
            throw new IllegalArgumentException("最大页数必须在" + MAX_PAGES_MIN + "到" + MAX_PAGES_MAX + "之间");
        }
        if (paginationType == ApiPaginationType.PAGE_BASED) {
            if (StringUtils.isBlank(pageParamName)) {
                throw new IllegalArgumentException("页码参数名不能为空");
            }
            if (StringUtils.isBlank(sizeParamName)) {
                throw new IllegalArgumentException("页大小参数名不能为空");
            }
        }
        if (paginationType == ApiPaginationType.CURSOR_BASED) {
            if (StringUtils.isBlank(cursorParamName)) {
                throw new IllegalArgumentException("游标参数名不能为空");
            }
            if (StringUtils.isBlank(cursorJsonPath)) {
                throw new IllegalArgumentException("游标JSON路径不能为空");
            }
        }
    }
}
