package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("database_schema")
public class DatabaseSchemaEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long connectionId;
    private String schemaName;
    private String description;
    private String createdAt;
    private String updatedAt;
    private Integer isDeleted;
}
