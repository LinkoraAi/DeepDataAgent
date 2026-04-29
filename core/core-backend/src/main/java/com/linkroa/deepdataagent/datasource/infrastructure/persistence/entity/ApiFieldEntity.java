package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("api_field")
public class ApiFieldEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long apiSchemaId;
    private String originalName;
    private String displayName;
    private String jsonPath;
    private String fieldType;
    private String description;
    private String createdAt;
    private String updatedAt;
    private Integer isDeleted;
}
