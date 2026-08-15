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