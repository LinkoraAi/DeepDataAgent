package com.linkroa.deepdataagent.runtime.controller.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 链路追踪 Span 响应 DTO（OTel Span 模型）。
 */
public record RunTraceResponse(
        String traceId,
        String spanId,
        String parentSpanId,
        String roundId,
        String spanName,
        String spanKind,
        String status,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Long durationMs,
        String modelName,
        BigDecimal estimatedCost,
        String toolName,
        String toolInput,
        String toolOutput,
        String attributes
) {
}