package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanStatus;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Agent 执行出向端口（应用层出向端口，供应商实现位于 infrastructure.client）。
 * <p>以日志流模型替代 v3 的同步阻塞返回：供应商实现仅负责将框架事件流
 * {@code HarnessAgent.streamEvents(...)} 映射为领域中性的 {@link AgentStreamSignal} 流，
 * <b>不做任何持久化 / 广播 / 状态累积决策</b>——订阅、逐事件持久化、SSE 广播、
 * 链路追踪与终态判定全部由应用层在 {@code doOnNext} 中编排，
 * 从而消除基础设施层对业务编排的越权。</p>
 * <p>流式输出语义：{@link Flux} 为冷流，应用层经 {@code publishOn(虚拟线程调度器)}
 * 订阅后逐信号实时处理，HTTP 请求线程永不等待 LLM 流。</p>
 */
public interface AgentRunExecutor {

    /**
     * 订阅一轮 agent 事件流（冷流，订阅后才开始执行）。
     *
     * @param agent     已装配的 Agent 句柄
     * @param userInput 用户消息
     * @param sessionId 会话 ID（框架状态/沙箱隔离键）
     * @param userId    用户 ID（框架状态隔离键）
     * @return 领域中性的事件信号流（按 SDK 产出顺序，串行发射）
     */
    Flux<AgentStreamSignal> streamEvents(
            BuiltAgent agent,
            String userInput,
            String sessionId,
            String userId);

    /**
     * 以携带确认结果元数据的新用户消息重新驱动一轮 agent 事件流（HITL 续流，确认语义）。
     * <p>AgentScope v2 的 HITL 恢复不是通过外部事件注入，而是以「携带
     * {@code agentscope_confirm_results} 元数据的新用户消息」重新驱动 {@code streamEvents}。
     * 待确认的 {@code ToolUseBlock} 属 SDK 类型、由基础设施层按 {@code replyId} 暂存，
     * 本方法据此构造确认结果并重新驱动流；领域 / 应用层不感知 SDK 类型。</p>
     *
     * @param agent     已装配的 Agent 句柄（与触发确认请求的同一会话 / 句柄）
     * @param replyId   待确认项关联的回复 ID（基础设施层据此定位暂存的 toolCalls）
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @return 确认后继续执行的领域中性事件信号流（冷流）
     */
    Flux<AgentStreamSignal> resumeWithConfirmation(
            BuiltAgent agent,
            String replyId,
            String sessionId,
            String userId);

    /**
     * 丢弃基础设施层按 {@code replyId} 暂存的待确认工具调用（拒绝 / 终止路径专用，不续流）。
     * <p>HITL 拒绝 / 会话终止不重新驱动流，需显式释放基础设施层暂存的 SDK
     * {@code ToolUseBlock}，避免残留导致的内存泄漏。</p>
     *
     * @param replyId 待确认项关联的回复 ID
     */
    void discardPendingToolCalls(String replyId);

    /**
     * 由事件流推导的 span 草案（应用层落库为 RunTrace）。
     *
     * @param spanName     span 名称（llm.call / tool.call / sandbox.exec）
     * @param spanKind     类型
     * @param toolName     工具名（工具类 span）
     * @param toolInput    脱敏后的工具入参
     * @param toolOutput   脱敏后的工具出参
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @param status       状态
     * @param inputTokens  输入 token 数（仅 llm.call）
     * @param outputTokens 输出 token 数（仅 llm.call）
     * @param modelName    模型名称（仅 llm.call）
     * @param estimatedCost 预估费用（仅 llm.call）
     */
    record TraceSpanDraft(
            String spanName,
            SpanKind spanKind,
            String toolName,
            String toolInput,
            String toolOutput,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            SpanStatus status,
            Integer inputTokens,
            Integer outputTokens,
            String modelName,
            BigDecimal estimatedCost
    ) {

        /**
         * 便捷构造：非 llm.call（token/模型/费用为 null）。
         */
        public TraceSpanDraft(
                String spanName,
                SpanKind spanKind,
                String toolName,
                String toolInput,
                String toolOutput,
                OffsetDateTime startTime,
                OffsetDateTime endTime,
                SpanStatus status
        ) {
            this(spanName, spanKind, toolName, toolInput, toolOutput, startTime, endTime, status,
                    null, null, null, null);
        }
    }
}