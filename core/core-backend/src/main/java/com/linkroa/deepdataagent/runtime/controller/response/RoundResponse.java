package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;

/**
 * 执行轮次响应 DTO。
 */
public record RoundResponse(
        String roundId,
        String sessionId,
        String runId,
        int roundNumber,
        String input,
        String output,
        String status,
        String replayedFromRoundId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}