package com.linkroa.deepdataagent.runtime.domain.event;

/**
 * Agent 流信号类型（还原 AgentScope {@code AgentEvent} 语义的领域中性分类）。
 * <p>基础设施层将框架事件 {@code io.agentscope.core.event.AgentEvent} 映射为
 * {@link AgentStreamSignal} 后流入应用层，应用层据此编排事件持久化 / SSE 广播 /
 * 链路追踪 / 终态判定——本枚举是领域与框架之间的语义契约，不允许引入框架类型。</p>
 */
public enum AgentStreamSignalType {

    /** SDK 执行开始（已由应用层 RUN_START 承接，无视即可，仅作状态可达性参考） */
    START,
    /** 推理文本增量 */
    THINKING_DELTA,
    /** 推理块结束（空 delta + is_last 收尾标记） */
    THINKING_END,
    /** 助手文本增量 */
    TEXT_DELTA,
    /** 文本块结束（空 delta + is_last 收尾标记） */
    TEXT_END,
    /** 工具调用开始（携带 tool_call_id / 工具名） */
    TOOL_CALL_START,
    /** 工具调用入参增量（JSON 片段，需跨事件聚合） */
    TOOL_CALL_DELTA,
    /** 工具调用结束（入参聚合完成后由应用层一次发出 tool_call） */
    TOOL_CALL_END,
    /** 工具结果文本增量（应用层按 head+tail 窗口截断） */
    TOOL_RESULT_TEXT_DELTA,
    /** 工具结果结束（携带工具名与状态 OK/ERROR） */
    TOOL_RESULT_END,
    /** 模型调用开始（llm.call span 起点） */
    MODEL_CALL_START,
    /** 模型调用结束（携带 token 统计与模型名，llm.call span 终点） */
    MODEL_CALL_END,
    /** Agent 最终结果（携带最终文本） */
    AGENT_RESULT,
    /** SDK 终态：轮次正常结束（end_turn） */
    AGENT_END,
    /** SDK 终态：迭代上限触发（stop_reason=max_iterations） */
    EXCEED_MAX_ITERS
}