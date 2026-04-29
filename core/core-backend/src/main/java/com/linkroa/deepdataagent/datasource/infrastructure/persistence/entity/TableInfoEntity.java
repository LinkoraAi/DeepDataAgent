package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("table_info")
public class TableInfoEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long databaseSchemaId;
    private String tableName;
    private String tableComment;
    private String tableCustomComment;
    private String createdAt;
    private String updatedAt;
    private Integer isDeleted;
}
