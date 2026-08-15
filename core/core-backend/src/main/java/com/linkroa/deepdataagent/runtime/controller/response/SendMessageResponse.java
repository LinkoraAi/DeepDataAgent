package com.linkroa.deepdataagent.runtime.controller.response;

/**
 * 发送消息响应（202 Accepted / SSE 直连场景的 run_id 回执）。
 */
public record SendMessageResponse(
        String roundId,
        String runId,
        String stopReason
) {
}