package com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天事件持久化实体（chat_event）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_event")
public class ChatEventEntity extends BaseEntity {

    private String eventId;
    private String sessionId;
    private String roundId;
    private String eventType;

    /** 事件数据（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String payload;

    private Long sequenceNum;
}