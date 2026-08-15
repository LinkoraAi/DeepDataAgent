package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * API Schema实体类
 * 对应数据库表 api_schema，存储API接口配置信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("api_schema")
public class ApiSchemaEntity extends BaseEntity {

    private Long connectionId;
    private String name;
    private String url;
    private String method;
    /** API 配置聚合 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String config;
}