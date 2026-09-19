package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 调度运行记录持久化实体（对应 deployment_run 表，触发落行 + CAS 就地终态化）。
 *
 * <p>对应领域模型 {@link com.linkroa.deepdataagent.agent.domain.model.DeploymentRun}，
 * 每次触发落一行（触发方式 / 结果状态 / 关联会话）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("deployment_run")
public class DeploymentRunEntity extends BaseEntity {

    /** 运行记录业务ID（drun_ 前缀） */
    private String runId;
    /** 所属调度器业务ID */
    private String deploymentId;
    /** 本次触发新建的会话ID（可空=启动失败无会话） */
    private String sessionId;
    /** 触发方式（cron / manual / webhook，源码小写值域） */
    private String triggerKind;
    /** 运行状态（running / succeeded / failed / terminated） */
    private String status;
    /** 触发时间 */
    private OffsetDateTime startedAt;
    /** 结束时间（终态回写后填充） */
    private OffsetDateTime finishedAt;
}
