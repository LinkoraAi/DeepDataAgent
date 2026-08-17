package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 版本持久化实体（对应 agent_version 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_version")
public class AgentVersionEntity extends BaseEntity {

    /** 版本业务ID */
    private String versionId;
    /** Agent业务ID */
    private String agentId;
    /** 发布号 */
    private Integer versionNumber;
    /** 版本名称 */
    private String name;
    /** 版本描述 */
    private String description;
    /** 系统提示词 */
    private String system;
    /** 模型配置引用 */
    private String modelProfileId;
    /** 挂载技能（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String skillIds;
    /** 预留知识库引用（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String knowledgeBaseIds;
    /** 数据源引用（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String dataSourceIds;
}