package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部署持久化实体（对应 deployment 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("deployment")
public class DeploymentEntity extends BaseEntity {

    /** 部署业务ID */
    private String deploymentId;
    /** 所属Agent业务ID */
    private String agentId;
    /** 激活 / 回滚的目标版本号 */
    private Integer versionNumber;
    /** 工作空间ID（占位） */
    private String workspaceId;
}