package com.linkroa.deepdataagent.runtime.controller.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 发送事件请求（对齐 Managed Agents {@code POST /sessions/{session_id}/events}）。
 * <p>顶层仅 {@code input} 事件数组（长度 1-50）；每个事件至少含 {@code role} 与 {@code type}。</p>
 */
public record SendEventRequest(
        @NotEmpty(message = "事件数组不能为空")
        List<EventInput> input
) {
    /**
     * 客户端事件条目。
     *
     * @param role    事件角色（user / assistant / tool）
     * @param type    事件类型（首版支持 message）
     * @param content ContentBlock 数组
     */
    public record EventInput(
            String role,
            String type,
            List<Map<String, Object>> content
    ) {
    }
}