package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 链路追踪 Span 状态（对应 run_trace.status）。
 */
public enum SpanStatus {
    /** 成功 */
    OK,
    /** 异常 */
    ERROR
}