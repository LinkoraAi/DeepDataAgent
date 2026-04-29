package com.linkroa.deepdataagent.datasource.domain.model.enums;

import lombok.Getter;

/**
 * 数据源子类型枚举
 * <p>针对 JDBC 大类下的具体数据库类型，如 MySQL、ClickHouse 等。</p>
 */
@Getter
public enum JdbcType {

    MYSQL(3306, "MySQL"),
    CLICKHOUSE(8123, "ClickHouse");

    private final int defaultPort;
    private final String displayName;

    JdbcType(int defaultPort, String displayName) {
        this.defaultPort = defaultPort;
        this.displayName = displayName;
    }

}
