package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("api_schema")
public class ApiSchemaEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long connectionId;
    private String name;
    private String path;
    private String method;
    private String jsonPathConfig;
    private String createdAt;
    private String updatedAt;
    private String createdBy;
    private String updatedBy;
    private Integer isDeleted;
}
