package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;

@Data
@TableName("datasource_connection")
public class DatasourceConnectionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private String subType;
    private String status;
    /** JDBC 连接配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String jdbcConnectionConfig;
    private String description;
    private String createdAt;
    private String updatedAt;
    private String createdBy;
    private String updatedBy;
    private Integer isDeleted;
}
