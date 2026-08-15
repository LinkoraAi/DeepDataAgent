package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("table_info")
public class TableInfoEntity extends BaseEntity {

    private Long databaseSchemaId;
    private String tableName;
    private String tableComment;
    private String tableCustomComment;
}