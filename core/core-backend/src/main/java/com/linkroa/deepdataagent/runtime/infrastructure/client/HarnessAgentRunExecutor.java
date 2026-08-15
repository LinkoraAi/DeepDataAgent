package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.service.AgentRunExecutor;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * AgentScope v2 执行器（{@link AgentRunExecutor} 实现，纯事件映射）。
 * <p>与 v3 的同步阻塞执行不同，本实现以<b>日志流模型</b>对外暴露：将
 * {@code HarnessAgent.streamEvents(...)} 的 SDK 事件流映射为领域中性的
 * {@link AgentStreamSignal} 冷流（{@link Flux} POST 订阅后才开始执行），</p>
 * <ul>
 *   <li><b>只做映射</b>：每个 SDK 事件输出一个语义等价、零框架依赖的信号；
 *       工具入参 delta / 工具结果 head+tail 截断 / 终态推导等状态累积<b>不在本层进行</b>，
 *       而是由应用层在 {@code doOnNext} 编排时借助领域模型 {@code AgentRunState} 完成；</li>
 *   <li><b>不阻塞</b>：本方法返回即返回冷流，绝不调用 {@code blockLast()}；消费线程由
 *       应用层经 {@code publishOn(虚拟线程调度器)} 托管，HTTP 请求线程永不等待 LLM 流；</li>
 *   <li><b>无持久化 / 无广播 / 无 span 决策</b>：订阅、逐事件短事务落库、SSE 广播、
 *       链路追踪与终态判定全部由应用层编排，消除基础设施层对业务编排的越权。</li>
 * </ul>
 */
@Component
public class HarnessAgentRunExecutor implements AgentRunExecutor {

    @Override
    public Flux<AgentStreamSignal> streamEvents(BuiltAgent agent,
                                                String userInput,
                                                String sessionId,
                                                String userId) {
        HarnessAgent harness = unwrap(agent);
        Msg input = Msg.builder().role(MsgRole.USER).textContent(userInput).build();
        RuntimeContext context = RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        String modelName = resolveModelName(harness);
        return harness.streamEvents(input, context)
                .handle((event, sink) -> {
                    AgentStreamSignal signal = toSignal(event, modelName);
                    // map 遇 null 会抛 NPE，handle 可在下游级别透明丢弃无语义事件
                    if (signal != null) {
                        sink.next(signal);
                    }
                });
    }

    /** 解包领域句柄为 SDK 句柄；不支持的实现类型视为装配错误直接抛出。 */
    private HarnessAgent unwrap(BuiltAgent agent) {
        if (agent instanceof HarnessBuiltAgent handle) {
            return handle.harness();
        }
        throw new IllegalArgumentException("不支持的 Agent 句柄类型: " + agent.getClass().getName());
    }

    /** 解析模型展示名（model 未装配/注册异常时降级为 null，不影响事件流）。 */
    private String resolveModelName(HarnessAgent harness) {
        try {
            if (harness.getModel() != null) {
                return harness.getModel().getModelName();
            }
        } catch (RuntimeException ignored) {
            // 模型注册异常不影响事件流，模型名以 null 落库
        }
        return null;
    }

    /**
     * SDK 事件 → 领域中性信号（无映射语义的事件返回 null，由调用方过滤）。
     * <p>每个 SDK 事件直接构造语义等价的信号（无间接层）；ToolResultState 以协议
     * 字符串透传（SUCCESS / ERROR / INTERRUPTED / DENIED / RUNNING），由应用层在
     * span 状态推导时判定。</p>
     */
    private AgentStreamSignal toSignal(AgentEvent event, String modelName) {
        return switch (event.getType()) {
            case AGENT_START -> AgentStreamSignal.of(AgentStreamSignalType.START, null, null);
            case AGENT_END -> AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null);
            case AGENT_RESULT -> AgentStreamSignal.of(AgentStreamSignalType.AGENT_RESULT, null, null)
                    .withResultText(resultText((AgentResultEvent) event));
            case THINKING_BLOCK_DELTA -> AgentStreamSignal.of(AgentStreamSignalType.THINKING_DELTA,
                    ((ThinkingBlockDeltaEvent) event).getDelta(),
                    ((ThinkingBlockDeltaEvent) event).getBlockId());
            case THINKING_BLOCK_END -> AgentStreamSignal.of(AgentStreamSignalType.THINKING_END, null,
                    ((ThinkingBlockEndEvent) event).getBlockId());
            case TEXT_BLOCK_DELTA -> AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA,
                    ((TextBlockDeltaEvent) event).getDelta(),
                    ((TextBlockDeltaEvent) event).getBlockId());
            case TEXT_BLOCK_END -> AgentStreamSignal.of(AgentStreamSignalType.TEXT_END, null,
                    ((TextBlockEndEvent) event).getBlockId());
            case TOOL_CALL_START -> AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START,
                    ((ToolCallStartEvent) event).getToolCallId(),
                    ((ToolCallStartEvent) event).getToolCallName(), null, null);
            case TOOL_CALL_DELTA -> AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA,
                    ((ToolCallDeltaEvent) event).getToolCallId(), null,
                    ((ToolCallDeltaEvent) event).getDelta(), null);
            case TOOL_CALL_END -> AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END,
                    ((ToolCallEndEvent) event).getToolCallId(),
                    ((ToolCallEndEvent) event).getToolCallName(), null, null);
            case TOOL_RESULT_TEXT_DELTA -> AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA,
                    ((ToolResultTextDeltaEvent) event).getToolCallId(),
                    ((ToolResultTextDeltaEvent) event).getToolCallName(),
                    ((ToolResultTextDeltaEvent) event).getDelta(), null);
            case TOOL_RESULT_END -> AgentStreamSignal.tool(AgentStreamSignalType.TOOL_RESULT_END,
                    ((ToolResultEndEvent) event).getToolCallId(),
                    ((ToolResultEndEvent) event).getToolCallName(), null,
                    toolState((ToolResultEndEvent) event));
            case MODEL_CALL_START -> AgentStreamSignal.of(AgentStreamSignalType.MODEL_CALL_START, null, null);
            case MODEL_CALL_END -> new AgentStreamSignal(AgentStreamSignalType.MODEL_CALL_END, null, null,
                    null, null, null, null,
                    inputTokens((ModelCallEndEvent) event), outputTokens((ModelCallEndEvent) event), modelName);
            case EXCEED_MAX_ITERS -> AgentStreamSignal.of(AgentStreamSignalType.EXCEED_MAX_ITERS, null, null);
            // TEXT/THINKING/DATA 块 Start、REQUIRE_*/USER_CONFIRM_RESULT/REQUEST_STOP/CUSTOM 等
            // 不属于 chat 事件流语义：直接丢弃（filter 过滤）
            default -> null;
        };
    }

    private static String resultText(AgentResultEvent event) {
        Msg result = event.getResult();
        return result != null ? result.getTextContent() : "";
    }

    private static String toolState(ToolResultEndEvent event) {
        return event.getState() != null ? event.getState().getValue() : null;
    }

    private static Integer inputTokens(ModelCallEndEvent event) {
        return event.getUsage() != null ? event.getUsage().getInputTokens() : null;
    }

    private static Integer outputTokens(ModelCallEndEvent event) {
        return event.getUsage() != null ? event.getUsage().getOutputTokens() : null;
    }
}