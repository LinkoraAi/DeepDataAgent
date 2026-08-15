package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 聊天事件领域模型（对应 chat_event 表）。
 * <p>事件流存储：SSE 推送与持久化为同一事件对象；{@code sequenceNum} 会话内严格递增，
 * 由仓储在事务内分配，保证回放（after_sequence_num）与实时订阅一致。</p>
 */
public record ChatEvent(
        Long id,
        String eventId,
        String sessionId,
        String roundId,
        ChatEventType eventType,
        String payload,
        long sequenceNum,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    public ChatEvent {
        if (StringUtils.isBlank(eventId)) {
            throw new IllegalArgumentException("事件ID不能为空");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (StringUtils.isBlank(roundId)) {
            throw new IllegalArgumentException("轮次ID不能为空");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        if (sequenceNum < 1) {
            throw new IllegalArgumentException("事件序列号必须为正数");
        }
    }

    /**
     * 创建聊天事件（不含审计与落库 ID，由持久化层补全）。
     */
    public static ChatEvent create(
            String sessionId,
            String roundId,
            ChatEventType eventType,
            String payload,
            long sequenceNum
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new ChatEvent(
                null,
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                roundId,
                eventType,
                payload == null ? "{}" : payload,
                sequenceNum,
                now,
                now,
                null,
                null
        );
    }

    /**
     * 从数据库恢复（查询/回放场景）。
     */
    public static ChatEvent restore(
            Long id,
            String eventId,
            String sessionId,
            String roundId,
            ChatEventType eventType,
            String payload,
            long sequenceNum,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new ChatEvent(
                id, eventId, sessionId, roundId, eventType, payload,
                sequenceNum, createdAt, updatedAt, createdBy, updatedBy
        );
    }
}