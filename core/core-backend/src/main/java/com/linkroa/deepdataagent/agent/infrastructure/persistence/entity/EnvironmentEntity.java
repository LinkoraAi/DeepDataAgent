package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运行环境持久化实体（对应 environment 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("environment")
public class EnvironmentEntity extends BaseEntity {

    /** 运行环境业务ID */
    private String environmentId;
    /** 运行环境名称 */
    private String name;
    /** 环境类型（本期仅 LOCAL） */
    private String type;
    /** 沙箱规格（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String sandboxSpec;
    /** 工作空间ID（占位） */
    private String workspaceId;
}