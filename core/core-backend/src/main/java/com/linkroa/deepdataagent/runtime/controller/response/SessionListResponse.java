package com.linkroa.deepdataagent.runtime.controller.response;

import java.util.List;

/**
 * 会话列表响应（对齐 Managed Agents {@code GET /sessions} 的 {@code data} + {@code next_page}）。
 */
public record SessionListResponse(
        List<SessionResponse> data,
        String next_page
) {
}