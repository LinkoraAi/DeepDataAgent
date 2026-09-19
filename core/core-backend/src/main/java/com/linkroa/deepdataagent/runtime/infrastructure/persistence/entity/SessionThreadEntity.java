package com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Session 线程持久化实体（session_thread，协调器多线程场景，6.3）。
 * <p>{@code agent} 为线程 Agent 快照 JSONB（Session 嵌入快照裁剪规则再去 multiagent）；
 * {@code status} 复用会话对外四态小写规范值（idle / running / rescheduling / terminated，
 * 归档以 {@code archived_at} 正交表达，非状态取值）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("session_thread")
public class SessionThreadEntity extends BaseEntity {

    /** 线程业务 ID（sthr_ 前缀） */
    private String threadId;

    /** 所属会话 ID */
    private String sessionId;

    /** 父线程 ID（协调器主线程为 null） */
    private String parentThreadId;

    /** 线程 Agent 快照（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String agent;

    /** 线程状态（对外六态小写规范值） */
    private String status;

    /** 归档时间（status=archived 双写） */
    private OffsetDateTime archivedAt;
}
