package com.linkroa.deepdataagent.runtime.application.command;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.apache.commons.lang3.StringUtils;

/**
 * 入站事件草案（接口层解析后的待落库事件，供应用服务批量摄取）。
 * <p>事件身份由 {@code type} + {@code payload} 表达（对齐事件溯源契约，已丢弃
 * {@code role} 信封）；{@code payloadJson} 为类型特化 JSON（null / 空白收敛为空对象）。
 * 批量摄取语义为「全有或全无」：全量校验先行（未知 / 非入站类型拒绝），
 * 随后单事务逐事件分配会话级 {@code seq} 落库。</p>
 *
 * @param type        源码事件类型（入站白名单成员）
 * @param payloadJson 类型特化 JSON 文本（可空）
 */
public record InboundEventDraft(ChatEventType type, String payloadJson) {

    public InboundEventDraft {
        if (type == null) {
            throw new IllegalArgumentException("入站事件类型不能为空");
        }
    }

    /** 类型特化 JSON（null / 空白收敛为空对象）。 */
    public String payloadJson() {
        return StringUtils.isBlank(payloadJson) ? "{}" : payloadJson;
    }
}