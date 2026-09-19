package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 调度器持久化实体（对应 deployment 表）。
 *
 * <p>对应领域模型 {@link com.linkroa.deepdataagent.agent.domain.model.Deployment}：
 * 调度配置（schedule）与触发会话挂载材料（environment_variables / resources / vault_ids /
 * initial_events / metadata）以 JSONB 文本承载，由
 * {@link com.linkroa.deepdataagent.agent.infrastructure.convert.DeploymentPersistenceConvert}
 * 在 Entity ⇄ Domain 间转换（schedule ⇄ {@code DeploymentSchedule} VO、status ⇄ 枚举）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("deployment")
public class DeploymentEntity extends BaseEntity {

    /** 调度器业务ID */
    private String deploymentId;
    /** 调度器名称 */
    private String name;
    /** 描述 */
    private String description;
    /** 所属Agent业务ID */
    private String agentId;
    /** 创建时固定的 Agent 版本号（≥1，触发不漂移） */
    private Integer agentVersion;
    /** 指向的 Environment 业务ID（可空=默认环境） */
    private String environmentId;
    /** 环境变量（JSONB 对象文本，触发时透传 runtime） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String environmentVariables;
    /** 挂载资源（JSONB 数组文本，格式对齐 session resources） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String resources;
    /** 保管库业务ID列表（JSONB 数组文本） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String vaultIds;
    /** 首批用户消息事件（JSONB 数组文本） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String initialEvents;
    /** 扩展元数据（JSONB 对象文本） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    /** 调度配置（JSONB {cron,timezone} 文本；null=仅手动/webhook 触发） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String schedule;
    /** 下次到期触发时间（轮询领取依据） */
    private OffsetDateTime nextRunAt;
    /** webhook 触发密钥（null=未开放） */
    private String webhookToken;
    /** 状态（active / paused，源码小写值域） */
    private String status;
    /** 暂停原因（恢复时需清除为 null，强制参与更新） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pausedReason;
    /** 最近一次触发时间 */
    private OffsetDateTime lastRunAt;
    /** 最近一次触发新建的会话ID */
    private String lastSessionId;
    /** 最近一次触发执行结果状态 */
    private String lastStatus;
    /** 归属用户 ID */
    private Long ownerId;
    /** 归档时间（归档 = archived_at + status=paused 双写） */
    private OffsetDateTime archivedAt;
}
