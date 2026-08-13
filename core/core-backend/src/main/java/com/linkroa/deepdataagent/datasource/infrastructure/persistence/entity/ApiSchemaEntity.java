package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;

/**
 * API Schema实体类
 * 对应数据库表 api_schema，存储API接口配置信息
 */
@Data
@TableName("api_schema")
public class ApiSchemaEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long connectionId;
    private String name;
    private String url;
    private String method;
    /** API 配置聚合 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String config;
    private String createdAt;
    private String updatedAt;
    private String createdBy;
    private String updatedBy;
    private Integer isDeleted;
}
