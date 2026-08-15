package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("api_field")
public class ApiFieldEntity extends BaseEntity {

    private Long apiSchemaId;
    private String originalName;
    private String displayName;
    private String jsonPath;
    private String fieldType;
    private String description;
}