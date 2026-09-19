package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;

/**
 * 流式增量帧（{@code event_start} / {@code event_delta}）—— 仅实时推送、不落库不回放
 * 的领域瞬时载体（增量流式帧契约）。
 * <p>帧不是持久化事件 {@link ChatEvent}：MUST NOT 占用会话 seq、MUST NOT 出现在
 * 事件 list/history 响应中；帧 data JSON 无顶层 {@code id} / {@code processed_at}
 * （由 {@code payloadJson} 承载类型特化 payload，帧信封装配在基础设施编解码层）。</p>
 * <p>同一逻辑增量事件的 SSE {@code id:}、{@code event_start} 的 {@code event.id}、
 * 各 {@code event_delta} 的 {@code event_id} 与最终 buffered 事件 {@code id}
 * MUST 完全一致——本载体以 {@link #eventId()} 统一携带该共享 ID。</p>
 *
 * @param frameType   帧类型（{@code EVENT_START} / {@code EVENT_DELTA}）
 * @param eventId     关联目标事件 ID（evt_ 前缀，与最终 buffered 事件同 ID）
 * @param targetType  目标事件类型（增量协商对象：{@code agent.message} / {@code agent.thinking}）
 * @param payloadJson 类型特化帧 payload JSON（{@code event{id,type}} 或 {@code event_id + delta{…}}）
 */
public record StreamFrame(ChatEventType frameType, String eventId,
                          ChatEventType targetType, String payloadJson) {

    public StreamFrame {
        if (frameType == null || targetType == null) {
            throw new IllegalArgumentException("流式帧类型不能为空");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("流式帧关联事件 ID 不能为空");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("流式帧 payload 不能为空");
        }
    }
}
