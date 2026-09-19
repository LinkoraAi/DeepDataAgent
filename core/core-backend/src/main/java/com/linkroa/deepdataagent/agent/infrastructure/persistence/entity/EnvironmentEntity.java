package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 运行环境持久化实体（对应 environment 表，config 契约结构）。
 * <p>无冗余 {@code type} 列（环境类型权威形状在 {@code config} JSONB 内）；
 * 归档以 {@code archived_at} 时间戳单列表达。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("environment")
public class EnvironmentEntity extends BaseEntity {

    /** 运行环境业务ID */
    private String environmentId;
    /** 运行环境名称 */
    private String name;
    /** 环境描述 */
    private String description;
    /** 环境配置（JSONB：{"type","packages":{六类},"setup_script"}） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String config;
    /** 自定义元数据（JSONB key/value） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    /** 归属用户 ID */
    private Long ownerId;
    /** 归档时间（NULL=未归档） */
    private OffsetDateTime archivedAt;
}