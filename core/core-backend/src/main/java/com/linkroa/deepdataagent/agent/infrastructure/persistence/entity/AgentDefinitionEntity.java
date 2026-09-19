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
    /** 归档时间（NULL=未归档；归档仅以时间戳表达） */
    private OffsetDateTime archivedAt;
    /** 最新发布号 */
    private Integer latestVersion;
    /** 当前生效版本号（默认随发布同步，可回滚） */
    private Integer activeVersion;
    /** 归属用户 ID */
    private Long ownerId;
}