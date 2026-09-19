package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 信号策略注册表（变更 R11 / design D2）：显式构造 {@code AgentStreamSignalType → SignalHandler} 映射，
 * 无 Spring 扫描 / 反射魔法。分发器退化为 {@code getOrDefault(warnUnknown)}——新增信号类型 = 新增一个
 * {@link SignalHandler} 并在此登记一行，命令服务的 {@code handleSignal} 骨架零修改。
 * <p>覆盖 {@link AgentStreamSignalType} 全部 16 个信号类型（17 路含 default 的旧 switch 中 START 已由
 * slim 变更删除），未登记类型走 {@link #WARN_UNKNOWN}（记 WARN 不抛，保持现状语义）。</p>
 * <p>策略无状态（可变面全在 {@code TurnRunState} 五组件上），故注册表可作为执行链服务的静态单例复用，
 * 跨会话 / 跨轮次共享。</p>
 * <p><b>可见性</b>：包私有——分发方 {@code handleSignal} 与静态单例持有者均在同包的
 * {@code TurnExecutionService} 内（策略接口 {@link SignalHandler} 与其实现亦包私有，
 * 不出现在对外签名上；5.1 归位复核后由 public 收紧回包私有）。</p>
 */
final class SignalHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(SignalHandlerRegistry.class);

    /** 未识别信号兜底：记 WARN 不抛（现状 default 语义）。 */
    private static final SignalHandler WARN_UNKNOWN = ctx ->
            log.warn("未处理的信号类型: sessionId={}, type={}", ctx.sessionId(), ctx.signal().type());

    private final Map<AgentStreamSignalType, SignalHandler> handlers;

    /**
     * 构造注册表：将 {@link AgentStreamSignalType} 全部信号类型登记到对应策略，未登记类型走 WARN 兜底。
     */
    public SignalHandlerRegistry() {
        SignalHandler text = new TextSignalHandler();
        SignalHandler thinking = new ThinkingSignalHandler();
        SignalHandler toolCall = new ToolCallSignalHandler();
        SignalHandler toolResult = new ToolResultSignalHandler();
        SignalHandler modelSpan = new ModelSpanSignalHandler();
        SignalHandler lifecycle = new LifecycleSignalHandler();
        SignalHandler hitl = new HitlSignalHandler();
        Map<AgentStreamSignalType, SignalHandler> map = new EnumMap<>(AgentStreamSignalType.class);
        map.put(AgentStreamSignalType.TEXT_DELTA, text);
        map.put(AgentStreamSignalType.TEXT_END, text);
        map.put(AgentStreamSignalType.THINKING_DELTA, thinking);
        map.put(AgentStreamSignalType.THINKING_END, thinking);
        map.put(AgentStreamSignalType.TOOL_CALL_START, toolCall);
        map.put(AgentStreamSignalType.TOOL_CALL_DELTA, toolCall);
        map.put(AgentStreamSignalType.TOOL_CALL_END, toolCall);
        map.put(AgentStreamSignalType.TOOL_RESULT_TEXT_DELTA, toolResult);
        map.put(AgentStreamSignalType.TOOL_RESULT_END, toolResult);
        map.put(AgentStreamSignalType.MODEL_CALL_START, modelSpan);
        map.put(AgentStreamSignalType.MODEL_CALL_END, modelSpan);
        map.put(AgentStreamSignalType.AGENT_RESULT, lifecycle);
        map.put(AgentStreamSignalType.EXCEED_MAX_ITERS, lifecycle);
        map.put(AgentStreamSignalType.AGENT_END, lifecycle);
        map.put(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, hitl);
        map.put(AgentStreamSignalType.HUMAN_CONFIRM_RESULT, hitl);
        this.handlers = map;
    }

    /**
     * 按信号类型分发到对应策略；未登记类型走 {@link #WARN_UNKNOWN}。
     *
     * @param ctx 本轮信号处理上下文
     */
    public void dispatch(SignalContext ctx) {
        handlers.getOrDefault(ctx.signal().type(), WARN_UNKNOWN).handle(ctx);
    }
}
