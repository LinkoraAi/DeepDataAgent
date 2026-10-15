package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * 受支持的数据源类型响应体（用于前端类型选择卡片）。
 *
 * @param type        主类型（{@code JDBC} / {@code API}）
 * @param subType     子类型（{@code MYSQL} / {@code CLICKHOUSE} / {@code POSTGRESQL} / {@code API}）
 * @param name        展示名
 * @param category    分类（{@code OLTP} / {@code OLAP} / {@code API}）
 * @param defaultPort 默认端口（API 类型为 null）
 * @author DeepDataAgent
 */
public record DatasourceTypeResponse(
        String type,
        String subType,
        String name,
        String category,
        Integer defaultPort
) {
}