package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("database_schema")
public class DatabaseSchemaEntity extends BaseEntity {

    private Long connectionId;
    private String schemaName;
    private String description;
}