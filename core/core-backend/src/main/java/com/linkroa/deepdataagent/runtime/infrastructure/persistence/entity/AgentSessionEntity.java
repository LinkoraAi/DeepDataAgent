package com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Agent 会话持久化实体（agent_session）。
 * <p>双列模型：{@code status}（对外四态 idle/running/rescheduling/terminated）+
 * {@code turnPhase}（内部相位 idle/running/awaiting_confirmation/cancelling，永不外显）；
 * 挂载列：environment_id / vault_ids / memory_store_ids / environment_variables / resources。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_session")
public class AgentSessionEntity extends BaseEntity {

    private String sessionId;
    private String userId;
    private String agentId;
    private String agentVersion;

    /** 会话级状态（对外四态小写规范值：idle/running/rescheduling/terminated） */
    private String status;

    /** 内部执行相位（idle/running/awaiting_confirmation/cancelling，永不外显；列名 turn_phase） */
    private String turnPhase;

    /** 扩展元数据（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;

    /** 会话挂载执行环境业务ID（env_ 前缀，可空） */
    private String environmentId;

    /** 保管库业务ID列表 jsonb 数组（["vault_xxx"]） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String vaultIds;

    /** 记忆库业务ID列表 jsonb 数组（["ms_xxx"]） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String memoryStoreIds;

    /** 会话级环境变量 jsonb 对象（装配时注入 Harness） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String environmentVariables;

    /** 会话挂载资源引用 jsonb 数组（三类: file/github_repository/memory_store，snake_case 键） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String resources;

    private String title;

    /** 触发来源类型（manual/webhook/cron，源码小写；null=普通用户会话） */
    private String triggerType;

    /** 触发来源调度器业务ID（deployment_id） */
    private String triggerId;

    private OffsetDateTime lastActiveAt;

    /** 归档时间（独立正交维度：归档只写本列，status 保持原值，不与 status 双写） */
    private OffsetDateTime archivedAt;
}
