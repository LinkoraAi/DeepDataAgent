package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 链路追踪 Span 类型（对应 run_trace.span_kind）。
 */
public enum SpanKind {
    /** 应用内部（agent.run 根 span） */
    INTERNAL,
    /** 客户端调用（llm.call / tool.call / sandbox.exec） */
    CLIENT,
    /** 服务端 */
    SERVER
}