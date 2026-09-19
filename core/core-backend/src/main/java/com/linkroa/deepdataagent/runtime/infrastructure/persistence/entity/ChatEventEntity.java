package com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 聊天事件持久化实体（chat_event，payload 信封结构）。
 * <p>{@code type} 为源码事件类型（{@code {域}.{动作}} 小写字符串）、{@code seq} 为会话内单调游标、
 * {@code processed_at} 为事件处理时间；列名与领域模型 {@code ChatEvent} 一一对应。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_event")
public class ChatEventEntity extends BaseEntity {

    /** 事件业务 ID（evt_ 前缀，幂等键） */
    private String eventId;

    /** 会话 ID */
    private String sessionId;

    /** 事件线程归属（sthr_，可空=会话级事件；线程事件历史过滤键，写入随执行面接入） */
    private String sessionThreadId;

    /** 源码事件类型（如 agent.message / session.status_idle） */
    private String type;

    /** 事件数据（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String payload;

    /** 会话内单调序列号（游标，唯一索引 (session_id, seq) 兜底） */
    private Long seq;

    /** 事件处理时间 */
    private OffsetDateTime processedAt;
}