package com.linkroa.deepdataagent.datasource.domain.model.enums;

import lombok.Getter;

/**
 * 数据源类型枚举（平铺展示）
 * <p>将所有数据源类型平铺列举，不区分 JDBC 大类和子类型，便于前端展示和选择。</p>
 */
@Getter
public enum DatasourceTypeEnum {

    MYSQL("JDBC", "MYSQL","MySQL", "OLTP"),
    CLICKHOUSE("JDBC", "CLICKHOUSE", "ClickHouse", "OLAP"),
    API("API", "API","API", "API");

    private final String type;
    private final String subType;
    private final String name;
    private final String category;

    DatasourceTypeEnum(String type, String subType, String name, String category) {
        this.type = type;
        this.subType = subType;
        this.name = name;
        this.category = category;
    }
}
