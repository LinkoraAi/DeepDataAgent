package com.linkroa.deepdataagent.datasource.domain.model.enums;

/**
 * 数据源主类型枚举
 *
 * <p>数据源类型采用两级分类：
 * <ul>
 *   <li>{@link #JDBC} — 关系型数据库，具体数据库类型（MySQL、ClickHouse 等）由 {@link JdbcType} 子类型区分</li>
 *   <li>{@link #API} — API 接口数据源，无子类型</li>
 * </ul>
 */
public enum DatasourceType {

    JDBC,
    API
}
