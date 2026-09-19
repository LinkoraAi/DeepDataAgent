package com.linkroa.deepdataagent.runtime.controller.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 发送事件请求（对齐公开契约 {@code POST /sessions/{session_id}/events}）。
 * <p>请求体 MUST 为 {@code {"events":[...]}}：数组非空即可（由 {@code @NotEmpty} 兜底），
 * <b>不再</b>自设 1–50 数量上限；每个事件的<b>类型自有字段直接位于事件对象顶层</b>
 * （如 {@code {"type":"user.message","content":[{"type":"text","text":"hi"}]}}），
 * 事件身份由顶层 {@code type}（{@code {域}.{动作}}）判别——MUST NOT 使用
 * 历史 {@code input[]} / {@code payload} 嵌套包装。</p>
 * <p>类型名解析（未知类型 400 {@code unknown_event_type}）与跨字段结构校验
 * （content / confirmation / tool_result / system.message 批规则）分别由控制器
 * 与 {@code InboundEventValidator} 承担；批内任一事件非法即整批 400，零部分落库。</p>
 *
 * @param events 扁平事件对象数组（非空；每项以顶层 {@code type} 判别事件类型）
 */
public record SendEventRequest(
        @NotEmpty(message = "事件数组不能为空")
        List<Map<String, Object>> events
) {
}