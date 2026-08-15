package com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 链路追踪 Span 持久化实体（run_trace）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_trace")
public class RunTraceEntity extends BaseEntity {

    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String roundId;
    private String spanName;
    private String spanKind;
    private String status;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Long durationMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private String modelName;
    private BigDecimal estimatedCost;
    private String toolName;
    private String toolInput;
    private String toolOutput;

    /** 扩展属性（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String attributes;
}