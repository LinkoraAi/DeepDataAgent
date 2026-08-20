package com.linkroa.deepdataagent.runtime.controller.request;

import java.util.Map;

/**
 * 更新会话请求（对齐 Managed Agents {@code POST /sessions/{session_id}}）：仅改 title 与 metadata。
 */
public record UpdateSessionRequest(
        String title,
        Map<String, Object> metadata
) {
}