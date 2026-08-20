package com.linkroa.deepdataagent.runtime.controller.response;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;

import java.util.List;

/**
 * 事件列表响应（对齐 Managed Agents {@code GET /sessions/{id}/events} 的 {@code data} + {@code next_page}）。
 */
public record EventListResponse(
        List<SseEventEnvelope> data,
        String next_page
) {
}