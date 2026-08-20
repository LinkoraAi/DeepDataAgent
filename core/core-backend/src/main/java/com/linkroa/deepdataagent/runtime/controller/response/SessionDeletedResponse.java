package com.linkroa.deepdataagent.runtime.controller.response;

/**
 * 删除会话响应（对齐 Managed Agents {@code DELETE /sessions/{session_id}}）。
 */
public record SessionDeletedResponse(
        String id,
        String type
) {
}