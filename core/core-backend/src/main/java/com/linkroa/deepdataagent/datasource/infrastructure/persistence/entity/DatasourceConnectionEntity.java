package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_connection")
public class DatasourceConnectionEntity extends BaseEntity {

    private String name;
    private String type;
    private String subType;
    private String status;
    /** JDBC 连接配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String jdbcConnectionConfig;
    private String description;
}