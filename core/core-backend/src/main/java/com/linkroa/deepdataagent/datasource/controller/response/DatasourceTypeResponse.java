package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * 数据源类型响应
 * <p>平铺展示所有可选的具体数据源类型，通过 category 区分大类。</p>
 *
 * @param type     数据源类型标识（小写，如 JBDC、API）
 * @param subType  数据源子类型（大写，如 MYSQL、CLICKHOUSE、API）
 * @param name     数据源类型展示名称（如 MySQL、ClickHouse、API）
 * @param category 数据源分类（OLTP、OLAP、API）
 */
public record DatasourceTypeResponse(
        String type,
        String subType,
        String name,
        String category
) {
}
