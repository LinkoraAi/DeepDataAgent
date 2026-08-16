package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Agent 定义持久化实体（对应 agent_definition 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_definition")
public class AgentDefinitionEntity extends BaseEntity {

    /** 业务ID */
    private String agentId;
    /** 名称 */
    private String name;
    /** 描述 */
    private String description;
    /** 是否归档 */
    private Boolean archived;
    /** 归档时间 */
    private OffsetDateTime archivedAt;
    /** 最新发布号 */
    private Integer latestVersion;
}