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
    /** 系统提示词（对外字段名 system；版本快照唯一指令载体） */
    private String systemPrompt;
    /** 模型配置引用 */
    private String modelProfileId;
    /** 模型引用（JSONB：目录模型 id 字符串简写或 {id, effort?, context_window?} 对象，可空） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String modelJson;
    /** 内联工具配方（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String toolsJson;
    /** 内联外部 MCP 工具源配方（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String mcpServersJson;
    /** 技能引用配方（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String skillsJson;
    /** 多智能体编排配置（JSONB） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String multiagent;
    /** 业务自定义元数据（JSONB 键值对象，可空） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadataJson;
}