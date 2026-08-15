package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanStatus;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 链路追踪 Span 领域模型（对应 run_trace 表，OTel Span 模型）。
 * <p>根 span（agent.run）由应用层落库；子 span（llm.call / tool.call / sandbox.exec）
 * 由 AgentScope 事件流推导。</p>
 */
public record RunTrace(
        Long id,
        String traceId,
        String spanId,
        String parentSpanId,
        String roundId,
        String spanName,
        SpanKind spanKind,
        SpanStatus status,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Long durationMs,
        Integer inputTokens,
        Integer outputTokens,
        String modelName,
        BigDecimal estimatedCost,
        String toolName,
        String toolInput,
        String toolOutput,
        String attributes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    public RunTrace {
        if (StringUtils.isBlank(traceId)) {
            throw new IllegalArgumentException("追踪ID不能为空");
        }
        if (StringUtils.isBlank(spanId)) {
            throw new IllegalArgumentException("SpanID不能为空");
        }
        if (StringUtils.isBlank(roundId)) {
            throw new IllegalArgumentException("轮次ID不能为空");
        }
        if (StringUtils.isBlank(spanName)) {
            throw new IllegalArgumentException("Span名称不能为空");
        }
        if (spanKind == null) {
            throw new IllegalArgumentException("Span类型不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("Span状态不能为空");
        }
        if (startTime == null) {
            throw new IllegalArgumentException("Span开始时间不能为空");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("Span耗时不能为负数");
        }
    }

    /**
     * 创建根 span（agent.run，INTERNAL）。
     *
     * @param traceId 追踪 ID（同一轮次共享）
     * @param roundId 轮次 ID
     * @param name    Span 名称（如 agent.run）
     */
    public static RunTrace createRoot(String traceId, String roundId, String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new RunTrace(
                null, traceId, UUID.randomUUID().toString().replace("-", ""), null, roundId,
                name, SpanKind.INTERNAL, SpanStatus.OK, now, null, null,
                null, null, null, null, null, null, null, "{}",
                now, now, null, null
        );
    }

    /**
     * 创建子 span（llm.call / tool.call / sandbox.exec，CLIENT）。
     *
     * @param traceId      追踪 ID
     * @param parentSpanId 父 span ID（根 span）
     * @param roundId      轮次 ID
     * @param name         Span 名称
     * @param toolName     工具名（仅 tool.call / sandbox.exec）
     * @param startTime    开始时间
     */
    public static RunTrace createChild(
            String traceId,
            String parentSpanId,
            String roundId,
            String name,
            String toolName,
            OffsetDateTime startTime
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new RunTrace(
                null, traceId, UUID.randomUUID().toString().replace("-", ""), parentSpanId, roundId,
                name, SpanKind.CLIENT, SpanStatus.OK, startTime, null, null,
                null, null, null, null, toolName, null, null, "{}",
                now, now, null, null
        );
    }

    /**
     * 派生结束态 span（写 endTime / durationMs）。
     */
    public RunTrace finish(OffsetDateTime endTime) {
        OffsetDateTime end = endTime != null ? endTime : OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        long duration = java.time.Duration.between(startTime, end).toMillis();
        return new RunTrace(
                id, traceId, spanId, parentSpanId, roundId, spanName, spanKind, status,
                startTime, end, Math.max(duration, 0), inputTokens, outputTokens, modelName,
                estimatedCost, toolName, toolInput, toolOutput, attributes,
                createdAt, end, createdBy, updatedBy
        );
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static RunTrace restore(
            Long id,
            String traceId,
            String spanId,
            String parentSpanId,
            String roundId,
            String spanName,
            SpanKind spanKind,
            SpanStatus status,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Long durationMs,
            Integer inputTokens,
            Integer outputTokens,
            String modelName,
            BigDecimal estimatedCost,
            String toolName,
            String toolInput,
            String toolOutput,
            String attributes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new RunTrace(
                id, traceId, spanId, parentSpanId, roundId, spanName, spanKind, status,
                startTime, endTime, durationMs, inputTokens, outputTokens, modelName, estimatedCost,
                toolName, toolInput, toolOutput, attributes, createdAt, updatedAt, createdBy, updatedBy
        );
    }
}