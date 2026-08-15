package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("column_info")
public class ColumnInfoEntity extends BaseEntity {

    private Long tableId;
    private String columnName;
    private String dataType;
    private String columnComment;
    private String columnCustomComment;
}