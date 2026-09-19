package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.model.McpToolRuntimeName;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.shared.security.SecretMasker;

/**
 * 工具结果信号策略（变更 R11）：{@code TOOL_RESULT_TEXT_DELTA} / {@code TOOL_RESULT_END}。
 * <p>DELTA 走 head 实时窗口（未满累积、已满后溢出丢弃，不落库不发布）；END 以 head+tail 截断补发后落库
 * {@code agent.tool_result}，output 先经 {@link SecretMasker#maskSecrets} 形态脱敏（{@code sk-*} /
 * {@code Bearer} 两类），再经 {@link SecretMasker#maskExactValues} 按<b>本轮挂载保管库凭据明文</b>
 * 精确掩码（任意字节 token 的兜底，见 {@code TurnRunState#mountedVaultSecrets}），
 * 防止明文凭证落库与广播。逐字平移自 {@code handleSignal} 对应 case。</p>
 * <p><b>MCP 配对（D15）</b>：MCP 实名（{@code mcp__} 前缀）的结果落 {@code agent.mcp_tool_result}，
 * 与 {@code agent.mcp_tool_use} 按 {@code payload.tool_use_id} 成对，MUST NOT 留下悬空调用。</p>
 */
final class ToolResultSignalHandler implements SignalHandler {

    @Override
    public void handle(SignalContext ctx) {
        AgentStreamSignal signal = ctx.signal();
        TurnRunState runState = ctx.runState();
        switch (signal.type()) {
            case TOOL_RESULT_TEXT_DELTA -> runState.appendToolResult(signal.toolCallId(), signal.text());
            case TOOL_RESULT_END -> {
                String toolCallId = signal.toolCallId();
                String tail = runState.endToolResult(toolCallId);
                String headPart = SecretMasker.maskSecrets(runState.toolResultHeadText(toolCallId));
                String output = headPart + (tail != null ? SecretMasker.maskSecrets(tail) : "");
                // 形态脱敏后按本轮已知凭据明文精确掩码（MCP 工具回显自身 token 的场景）
                output = SecretMasker.maskExactValues(output, runState.mountedVaultSecrets());
                ctx.sink().persistAndBroadcast(toolResultEvent(signal, output,
                        runState.toolResultTruncated(toolCallId)));
                // 配对达成：摘除在飞登记，中断收流不再补合成结果
                runState.removePendingToolUse(toolCallId);
            }
            default -> throw new IllegalStateException("ToolResultSignalHandler 收到非工具结果信号: " + signal.type());
        }
    }

    /** 工具结果事件装配：与调用事件同口径分类（MCP 实名走 {@code agent.mcp_tool_result}，保证配对不悬空）。 */
    private static ChatEventFactory.AssembledEvent toolResultEvent(AgentStreamSignal signal, String output,
                                                                   boolean truncated) {
        String mcpServerName = McpToolRuntimeName.serverNameOf(signal.toolName());
        if (mcpServerName == null) {
            return ChatEventFactory.INSTANCE.toolResult(signal.toolCallId(), signal.toolName(),
                    signal.toolState(), output, truncated);
        }
        return ChatEventFactory.INSTANCE.mcpToolResult(signal.toolCallId(), signal.toolName(), mcpServerName,
                signal.toolState(), output, truncated);
    }
}
