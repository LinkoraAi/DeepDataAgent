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
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_session")
public class AgentSessionEntity extends BaseEntity {

    private String sessionId;
    private String userId;
    private String agentId;
    private String agentVersion;
    private String status;

    /** 扩展元数据（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;

    private String sandboxId;
    private String title;
    private OffsetDateTime lastActiveAt;
}