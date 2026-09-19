package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 聊天事件领域模型（对应 chat_event 表，持久化事件信封）。
 * <p>事件溯源模型下，轮次分组与执行轨迹完全由本事件流承载（已删除
 * {@code execution_round} / {@code run_trace} 物化表）；事件信封对外恒为
 * {@code id / sessionId / seq / type / payload / processedAt / createdAt}：</p>
 * <ul>
 *   <li>{@code eventId}：业务幂等键，统一 {@code evt_} 前缀，回放与实时订阅重合窗口按此去重；</li>
 *   <li>{@code seq}：会话内单调游标（DB 唯一索引兜底，回放按 {@code after=seq} 定位）；</li>
 *   <li>{@code type}：源码事件类型（{@code {域}.{动作}}，见 {@link ChatEventType}）；</li>
 *   <li>{@code payload}：类型特化 JSON（事件身份由 {@code type} + {@code payload} 表达，不含
 *       roundId / eventType / sequenceNum 等旧轮次字段）。</li>
 *   <li>{@code sessionThreadId}：线程归属（{@code sthr_} 前缀，可空 = 会话级 / 归属未知事件），
 *       线程维度的事件过滤与回放以本字段为归属键。</li>
 * </ul>
 */
public record ChatEvent(
        Long id,
        String eventId,
        String sessionId,
        long seq,
        ChatEventType type,
        String payload,
        OffsetDateTime processedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy,
        String sessionThreadId
) {

    public ChatEvent {
        if (StringUtils.isBlank(eventId) || !eventId.startsWith(EVENT_ID_PREFIX)) {
            throw new IllegalArgumentException("事件ID不能为空且必须带 evt_ 前缀");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("事件序列号必须为正数");
        }
        // processedAt 可空：仅由事件生产方（执行侧处理完成）回填，入站等尚未处理的事件为 null
        // 线程归属可空（null = 会话级 / 归属未知事件）；非空时必须带 sthr_ 前缀，空白收敛为 null
        if (StringUtils.isBlank(sessionThreadId)) {
            sessionThreadId = null;
        } else if (!sessionThreadId.startsWith(THREAD_ID_PREFIX)) {
            throw new IllegalArgumentException("线程归属ID必须带 sthr_ 前缀");
        }
    }

    /** 事件 ID 统一前缀（业务幂等键约定）。 */
    public static final String EVENT_ID_PREFIX = "evt_";

    /** 会话线程 ID 统一前缀（线程归属外键约定，与 {@link SessionThread#THREAD_ID_PREFIX} 一致）。 */
    public static final String THREAD_ID_PREFIX = "sthr_";

    /**
     * 创建聊天事件（不含审计与落库 ID，由持久化层补全）。
     *
     * @param sessionId 会话 ID
     * @param seq       会话内单调序列号（游标）
     * @param type      源码事件类型
     * @param payload   类型特化 JSON（null / 空白收敛为空对象）
     */
    public static ChatEvent create(
            String sessionId,
            ChatEventType type,
            String payload,
            long seq
    ) {
        return create(sessionId, type, payload, seq,
                EVENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""), null);
    }

    /**
     * 创建聊天事件并指定事件 ID（流式块最终事件复用帧的 {@code evt_} ID，
     * 保证 {@code event_delta} 实时帧与落库最终事件在回放 + 实时订阅重合窗口可关联 / 去重）。
     *
     * @param sessionId 会话 ID
     * @param seq       会话内单调序列号（游标）
     * @param type      源码事件类型
     * @param payload   类型特化 JSON
     * @param eventId   事件业务 ID（必须带 {@code evt_} 前缀）
     */
    public static ChatEvent create(
            String sessionId,
            ChatEventType type,
            String payload,
            long seq,
            String eventId
    ) {
        return create(sessionId, type, payload, seq, eventId, null);
    }

    /**
     * 创建聊天事件并指定事件 ID 与线程归属（权威工厂）。
     *
     * @param sessionId       会话 ID
     * @param seq             会话内单调序列号（游标）
     * @param type            源码事件类型
     * @param payload         类型特化 JSON
     * @param eventId         事件业务 ID（null / 空白时自动生成 {@code evt_} 随机 ID）
     * @param sessionThreadId 线程归属 ID（{@code sthr_} 前缀，可空 = 会话级 / 归属未知事件）
     */
    public static ChatEvent create(
            String sessionId,
            ChatEventType type,
            String payload,
            long seq,
            String eventId,
            String sessionThreadId
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        String resolvedEventId = StringUtils.isBlank(eventId)
                ? EVENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
                : eventId;
        return new ChatEvent(
                null,
                resolvedEventId,
                sessionId,
                seq,
                type,
                payload == null ? "{}" : payload,
                now,
                now,
                now,
                null,
                null,
                sessionThreadId
        );
    }

    /**
     * 从数据库恢复（查询 / 回放场景）。
     */
    public static ChatEvent restore(
            Long id,
            String eventId,
            String sessionId,
            long seq,
            ChatEventType type,
            String payload,
            OffsetDateTime processedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy,
            String sessionThreadId
    ) {
        return new ChatEvent(
                id, eventId, sessionId, seq, type, payload,
                processedAt, createdAt, updatedAt, createdBy, updatedBy, sessionThreadId
        );
    }
}