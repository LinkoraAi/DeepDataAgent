package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("column_info")
public class ColumnInfoEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long tableId;
    private String columnName;
    private String dataType;
    private String columnComment;
    private String columnCustomComment;
    private String createdAt;
    private String updatedAt;
    private Integer isDeleted;
}
