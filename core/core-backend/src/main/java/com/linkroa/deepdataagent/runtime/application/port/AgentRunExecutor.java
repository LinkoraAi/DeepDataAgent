package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.PendingToolCallSpec;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Agent 执行出向端口（应用层出向端口，供应商实现位于 infrastructure.client）。
 * <p>以日志流模型替代 v3 的同步阻塞返回：供应商实现仅负责将框架事件流
 * {@code HarnessAgent.streamEvents(...)} 映射为领域中性的 {@link AgentStreamSignal} 流，
 * <b>不做任何持久化 / 广播 / 状态累积决策</b>——订阅、逐事件持久化、SSE 广播、
 * 链路追踪与终态判定全部由应用层在 {@code doOnNext} 中编排，
 * 从而消除基础设施层对业务编排的越权。</p>
 * <p>流式输出语义：{@link Flux} 为冷流，应用层经 {@code publishOn(虚拟线程调度器)}
 * 订阅后逐信号实时处理，HTTP 请求线程永不等待 LLM 流。</p>
 * <p>事件溯源模型下执行轨迹全部由 append-only 事件流承载（已删除 run_trace 物化表），
 * 模型调用计量经 {@code span.model_request_*} 事件下沉 token，成本读时派生。</p>
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
     * 以携带确认结果元数据的新用户消息重新驱动一轮 agent 事件流（HITL 续流，确认 / 拒绝语义）。
     * <p>AgentScope v2 的 HITL 恢复不是通过外部事件注入，而是以「携带
     * {@code agentscope_confirm_results} 元数据的新用户消息」重新驱动 {@code streamEvents}。
     * 待确认工具调用明细由应用层从事件账本（{@code agent.tool_use} 行）重建为领域中性的
     * {@link PendingToolCallSpec} 批次传入，基础设施层据此重建 SDK 工具调用块并构造确认
     * 结果——<b>不依赖任何按 replyId 暂存的进程内现场</b>，任意实例、任意时刻（跨重启）
     * 均可续跑同一逻辑轮。</p>
     *
     * @param agent       已装配的 Agent 句柄（重建后与挂起会话同 sessionId/userId 槽位）
     * @param toolCalls   待确认工具调用批次明细（同 reply 整批，逐调用注入同一裁决）
     * @param sessionId   会话 ID
     * @param userId      用户 ID
     * @param allow       true=注入允许结果继续执行；false=注入拒绝结果（工具不执行，按拒绝结果续跑）
     * @param denyMessage 拒绝说明（仅 allow=false 时有值，作为用户消息文本随结果注入）
     * @return 确认后继续执行的领域中性事件信号流（冷流）
     */
    Flux<AgentStreamSignal> resumeConfirmation(
            BuiltAgent agent,
            List<PendingToolCallSpec> toolCalls,
            String sessionId,
            String userId,
            boolean allow,
            String denyMessage);
}