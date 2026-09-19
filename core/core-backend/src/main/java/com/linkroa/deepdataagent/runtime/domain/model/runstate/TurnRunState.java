package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.McpToolRuntimeName;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单轮运行态门面（组合五件并发组件 + 散点字段），替代原 785 行 {@code AgentRunState} 大类。
 * <p>本类只承载<b>单轮</b>内可变状态，会话级跨轮状态（身份 / 事件序列号计数器 / 在跑执行句柄）
 * 收敛于 {@code AgentSessionContext}，每轮经 {@code AgentSessionContext.beginRound} 新建本门面：</p>
 * <table>
 *   <caption>五组件 + 散点分工（变更 decompose-turn-pipeline D3）</caption>
 *   <tr><th>组件</th><th>吸收</th></tr>
 *   <tr><td>{@link TextBlockAccumulator}</td><td>文本 / 思考 map、eventId、started、inFlight、delta 缓冲、轮次 output、finalResultText</td></tr>
 *   <tr><td>{@link ToolCallAggregator}</td><td>工具名 / 入参 / 快照 / span 起点 / 事件 id</td></tr>
 *   <tr><td>{@link ToolResultWindow}</td><td>head+tail 截断（16KB）与 tool_result 缓冲</td></tr>
 *   <tr><td>{@link ConfirmCandidateBatch}</td><td>HITL 待确认候选登记与批次匹配</td></tr>
 *   <tr><td>{@link RoundGuard}</td><td>中断 / 租约丢失 / 终态 / 挂起四位 + exceedMaxIters + abort 句柄</td></tr>
 *   <tr><td>门面散点</td><td>modelName / textPersisted / modelSpan / mountedVaultSecrets / mcpToolPermissions</td></tr>
 * </table>
 * <p>方法签名与原 {@code AgentRunState} 逐一对应（委托到底层组件），保证调用点行为等价；
 * 跨组件协调仅 {@link #tryFinalize()}（领取资格后清进行中流 / 聚合缓冲）。</p>
 * <p>原子字段基数 7（AtomicBoolean×5 + AtomicReference×2）：五位 AtomicBoolean 全落 {@link RoundGuard}
 * （interrupted / leaseLost / finalized / confirmationPending / exceedMaxIters），
 * AtomicReference finalResultText 落 {@link TextBlockAccumulator}、leaseLostAbort 落 {@link RoundGuard}。
 * 各 map / set / builder / volatile 字段落点见各组件类 Javadoc。</p>
 */
public final class TurnRunState {

    private final TextBlockAccumulator text = new TextBlockAccumulator();
    private final ToolCallAggregator toolCalls = new ToolCallAggregator();
    private final ToolResultWindow toolResults = new ToolResultWindow();
    private final ConfirmCandidateBatch confirmBatch = new ConfirmCandidateBatch();
    private final RoundGuard guard = new RoundGuard();

    /** 最近一次模型调用携带的模型名（span.model_request_start 的 model 字段兜底） */
    private String lastModelName;
    /** 本轮是否已落库 agent.message（缺失文本块时以 AGENT_RESULT 兜底） */
    private boolean textMessagePersisted = false;
    /** 模型调用开始时间（llm.call span 起点，单模型并发场景取最近一次） */
    private OffsetDateTime modelSpanStart;
    /** 本轮模型调用开始事件的 evt_ ID（span.model_request_end 的配对键；惰性生成） */
    private String modelStartEventId;
    /**
     * 本轮挂载保管库凭证的解密明文集合（开跑前装配一次性登记，信号线程只读；并发集合保证安全发布）。
     * <p>唯一用途：工具结果落库 / SSE 广播与错误终态前，经 {@code SecretMasker.maskExactValues}
     * 把已知秘密的回显精确掩码（形态正则覆盖不了任意字节 token 的兜底）。
     * <b>MUST NOT 进日志 / toString / 任何序列化面</b>——仅内存瞬态，随本轮对象作废。</p>
     */
    private final Set<String> mountedVaultSecrets = ConcurrentHashMap.newKeySet();

    /**
     * 登记本轮挂载的保管库凭据明文（装配产出规格后、构建 Agent 前调用；空白 token 跳过）。
     *
     * @param credentials 本轮材料化的保管库凭证引用（可空 = 无挂载）
     */
    public void registerMountedVaultSecrets(List<AgentAssemblySpec.VaultCredentialRef> credentials) {
        if (credentials == null) {
            return;
        }
        for (AgentAssemblySpec.VaultCredentialRef ref : credentials) {
            if (ref != null && ref.token() != null && !ref.token().isBlank()) {
                mountedVaultSecrets.add(ref.token());
            }
        }
    }

    /** 本轮已知凭据明文快照（精确掩码输入；无挂载时为空列表）。 */
    public List<String> mountedVaultSecrets() {
        return List.copyOf(mountedVaultSecrets);
    }

    // ==================== MCP 工具权限求值（D15 事件投影） ====================

    /**
     * 本轮版本策略中 MCP 工具命中的权限规则（运行时实名 → evaluated_permission）。
     * <p>装配后一次性登记，信号线程只读；未登记的工具按平台默认放行路径求值为 {@code allow}。
     * 与框架权限引擎同源（同一份 {@code mcp_toolset} 策略、同一实名约定），用于事件载荷
     * {@code evaluated_permission} 的可归属表达。</p>
     */
    private final Map<String, String> mcpToolPermissions = new ConcurrentHashMap<>();

    /**
     * 登记本轮 MCP 工具权限策略（装配产出规格后、构建 Agent 前调用一次）。
     * <p>仅 MCP 来源的策略项（{@code mcpServerName} 非空）登记；含 MCP 服务器名与工具名的
     * 运行时实名经 {@link McpToolRuntimeName#of} 派生，与工厂下发框架的权限规则键完全一致。</p>
     *
     * @param policies 本轮逐工具权限策略（可空 = 无策略）
     */
    public void registerMcpToolPolicies(List<AgentAssemblySpec.ToolExecutionPolicy> policies) {
        if (policies == null) {
            return;
        }
        for (AgentAssemblySpec.ToolExecutionPolicy policy : policies) {
            if (policy == null || policy.mcpServerName() == null || policy.mcpServerName().isBlank()) {
                continue;
            }
            mcpToolPermissions.put(
                    McpToolRuntimeName.of(policy.mcpServerName(), policy.name()), policy.permissionPolicy());
        }
    }

    /**
     * 工具调用求值结果（{@code allow} / {@code ask} / {@code deny}）。
     * <p>{@code always_allow} 与未配置策略的工具均为 {@code allow}（框架默认放行路径）；
     * {@code always_ask} 触发 HITL 暂停、{@code always_deny} 由平台拒绝执行。</p>
     *
     * @param runtimeToolName 运行时工具实名（可空）
     * @return 求值结果（缺省 {@code allow}）
     */
    public String evaluatedPermissionOf(String runtimeToolName) {
        String policy = runtimeToolName == null ? null : mcpToolPermissions.get(runtimeToolName);
        return AgentAssemblySpec.ToolExecutionPolicy.evaluatedPermissionOf(policy);
    }

    // ==================== 文本输出累积（TextBlockAccumulator） ====================

    /**
     * 追加轮次最终输出增量（TEXT 增量）。
     * <p>底层为普通 {@code StringBuilder}，仅 Reactor 串行事件流单线程独占写，禁止并发调用。</p>
     *
     * @param delta 文本增量
     */
    public void appendOutput(String delta) {
        text.appendOutput(delta);
    }

    /**
     * 记录 AGENT_RESULT 最终文本，与增量累积互相兜底（空串不覆盖）。
     *
     * @param finalText 最终结果文本
     */
    public void setFinalResultText(String finalText) {
        text.setFinalResultText(finalText);
    }

    /**
     * 轮次最终输出：增量累积优先，为空时回退 AGENT_RESULT 文本。
     *
     * @return 轮次最终输出文本
     */
    public String output() {
        return text.output();
    }

    /**
     * 追加文本块增量并返回该块累积文本（TEXT_DELTA 实时帧用）；追加与只读快照同在 builder 上加锁。
     *
     * @param blockId 文本块 ID
     * @param delta   文本增量
     * @return 该块累积文本（blockId 或 delta 为空时返回空串）
     */
    public String appendText(String blockId, String delta) {
        return text.appendText(blockId, delta);
    }

    /**
     * 取出并移除文本块完整文本（TEXT_END 落库 {@code agent.message} 用）。
     *
     * @param blockId 文本块 ID
     * @return 该块完整文本（块不存在时返回空串）
     */
    public String takeText(String blockId) {
        return text.takeText(blockId);
    }

    /**
     * 惰性分配文本块事件 ID（首次调用生成，流式帧与最终事件共用同一 {@code evt_}）。
     *
     * @param blockId 文本块 ID
     * @return 该块事件 ID
     */
    public String ensureTextEventId(String blockId) {
        return text.ensureTextEventId(blockId);
    }

    /**
     * 取出并移除文本块事件 ID（TEXT_END 落库用）。
     *
     * @param blockId 文本块 ID
     * @return 该块事件 ID（未分配过时为 {@code null}）
     */
    public String takeTextEventId(String blockId) {
        return text.takeTextEventId(blockId);
    }

    /**
     * 追加思考块增量并返回该块累积文本（THINKING_DELTA 实时帧用）；与文本块同型加锁。
     *
     * @param blockId 思考块 ID
     * @param delta   思考增量
     * @return 该块累积文本（blockId 或 delta 为空时返回空串）
     */
    public String appendThinking(String blockId, String delta) {
        return text.appendThinking(blockId, delta);
    }

    /**
     * 取出并移除思考块完整文本（THINKING_END 落库 {@code agent.thinking} 用）。
     *
     * @param blockId 思考块 ID
     * @return 该块完整文本（块不存在时返回空串）
     */
    public String takeThinking(String blockId) {
        return text.takeThinking(blockId);
    }

    /**
     * 惰性分配思考块事件 ID（首次调用生成，流式帧与最终事件共用同一 {@code evt_}）。
     *
     * @param blockId 思考块 ID
     * @return 该块事件 ID
     */
    public String ensureThinkingEventId(String blockId) {
        return text.ensureThinkingEventId(blockId);
    }

    /**
     * 取出并移除思考块事件 ID（THINKING_END 落库用）。
     *
     * @param blockId 思考块 ID
     * @return 该块事件 ID（未分配过时为 {@code null}）
     */
    public String takeThinkingEventId(String blockId) {
        return text.takeThinkingEventId(blockId);
    }

    /**
     * 标记文本块开始流式推送（首个 delta 到达时调用一次）。
     *
     * @param blockId 文本块 ID
     * @return true=首次（调用方应先推 event_start 再推 event_delta）
     */
    public boolean markTextStreamStarted(String blockId) {
        return text.markTextStreamStarted(blockId);
    }

    /**
     * 标记思考块开始流式推送（首个 delta 到达时调用一次）。
     *
     * @param blockId 思考块 ID
     * @return true=首次（调用方应先推 event_start；thinking 不暴露推理内容、无 delta 帧）
     */
    public boolean markThinkingStreamStarted(String blockId) {
        return text.markThinkingStreamStarted(blockId);
    }

    /**
     * 装载进行中流快照（event_start 发出时调用；同轮单流，后装覆盖前装）。
     *
     * @param eventId    流式事件 ID
     * @param targetType 目标事件类型
     * @param blockId    源块 ID
     * @param baseSeq    流开始时的序列号基准
     */
    public void markInFlightStream(String eventId, ChatEventType targetType, String blockId, long baseSeq) {
        text.markInFlightStream(eventId, targetType, blockId, baseSeq);
    }

    /**
     * 当前进行中的流式块快照（重连三段语义回补判定依据）。
     *
     * @return 进行中流快照；无进行中流时返回 {@code null}
     */
    public TextBlockAccumulator.InFlightStream inFlightStream() {
        return text.inFlightStream();
    }

    /**
     * 清空进行中流快照：仅当 {@code eventId} 与当前进行中流一致时生效，防止误清后起的流。
     *
     * @param eventId 已收尾的流式事件 ID（为空则不清）
     */
    public void clearInFlightStream(String eventId) {
        text.clearInFlightStream(eventId);
    }

    /**
     * HITL 挂起时无条件丢弃进行中流快照与未刷完的尾部增量。
     * <p>挂起即物理轮终局：在途块不会再收到收尾事件，快照残留会导致重连回补一条永远等不到收尾的 event_start 帧。</p>
     */
    public void clearInFlightStreamOnSuspend() {
        text.clearInFlightStreamOnSuspend();
    }

    /**
     * 文本块当前累积文本（只读副本，不移除）。由 HTTP 重连线程调用，与流线程追加写并发，故在 builder 上加锁取快照。
     *
     * @param blockId 文本块 ID
     * @return 当前累积文本（块不存在时返回空串）
     */
    public String accumulatedText(String blockId) {
        return text.accumulatedText(blockId);
    }

    /**
     * 追加待刷写的 delta 片段（聚合缓冲，按 {@link #shouldFlushDelta} 节奏下发）。
     *
     * @param delta 文本增量
     */
    public void appendPendingDelta(String delta) {
        text.appendPendingDelta(delta);
    }

    /**
     * 是否到达 delta 刷写窗口（从未刷写时恒为 true，即首段立发）。
     *
     * @param intervalMs 刷写间隔毫秒（非正数视为逐段立发）
     * @return true=应刷写
     */
    public boolean shouldFlushDelta(long intervalMs) {
        return text.shouldFlushDelta(intervalMs);
    }

    /**
     * 排空聚合缓冲并刷新刷写时刻。
     *
     * @return 本次应下发的聚合 delta 文本（缓冲为空时返回空串）
     */
    public String drainPendingDelta() {
        return text.drainPendingDelta();
    }

    // ==================== 工具调用聚合（ToolCallAggregator） ====================

    /**
     * 记录工具调用开始（工具名 + {@code Asia/Shanghai} span 起点）。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名（可空）
     */
    public void startToolCall(String toolCallId, String toolName) {
        toolCalls.startToolCall(toolCallId, toolName);
    }

    /**
     * 追加工具调用入参 delta（JSON 片段，跨 TOOL_CALL_START/DELTA 聚合）。
     *
     * @param toolCallId 工具调用 ID
     * @param delta      入参 JSON 片段
     */
    public void appendToolArgs(String toolCallId, String delta) {
        toolCalls.appendToolArgs(toolCallId, delta);
    }

    /**
     * 取出聚合完成的入参 JSON 并移除（工具调用 END 时调用一次）；同时留存原始入参快照供 TOOL_RESULT_END 的 {@code tool.call} span 使用。
     *
     * @param toolCallId 工具调用 ID
     * @return 聚合后的入参 JSON；未聚合任何 delta 时返回 {@code null}
     */
    public String takeToolArgs(String toolCallId) {
        return toolCalls.takeToolArgs(toolCallId);
    }

    /**
     * 工具名（span 落库用）。
     *
     * @param toolCallId 工具调用 ID
     * @param fallback   未登记时的兜底工具名（事件携带的工具名，由调用方判断）
     * @return 工具名
     */
    public String toolName(String toolCallId, String fallback) {
        return toolCalls.toolName(toolCallId, fallback);
    }

    /**
     * 取出工具入参快照并移除（TOOL_RESULT_END 时调用一次，span 入参落库前由应用层脱敏）。
     *
     * @param toolCallId 工具调用 ID
     * @return 原始入参 JSON（无快照时返回 {@code null}）
     */
    public String takeToolInput(String toolCallId) {
        return toolCalls.takeToolInput(toolCallId);
    }

    /**
     * 取出工具调用开始时间（span 起点）并移除。
     *
     * @param toolCallId 工具调用 ID
     * @return span 起点时刻（无记录时返回 {@code null}）
     */
    public OffsetDateTime takeToolSpanStart(String toolCallId) {
        return toolCalls.takeToolSpanStart(toolCallId);
    }

    /**
     * 惰性分配工具调用事件 ID（首次调用生成，流式帧与 {@code agent.tool_use} / {@code agent.tool_result} 共用）。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具调用事件 ID（{@code evt_} 前缀）
     */
    public String ensureToolEventId(String toolCallId) {
        return toolCalls.ensureToolEventId(toolCallId);
    }

    /**
     * 取出并移除工具调用事件 ID（{@code agent.tool_use} 落库用）。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具调用事件 ID（未分配过时为 {@code null}）
     */
    public String takeToolEventId(String toolCallId) {
        return toolCalls.takeToolEventId(toolCallId);
    }

    /** 登记在飞工具调用（TOOL_CALL_END 落库 tool_use 后调用）。 */
    public void registerPendingToolUse(String toolCallId, String toolName) {
        toolCalls.registerPendingToolUse(toolCallId, toolName);
    }

    /** 摘除在飞工具调用（TOOL_RESULT_END 落库配对结果后调用）。 */
    public void removePendingToolUse(String toolCallId) {
        toolCalls.removePendingToolUse(toolCallId);
    }

    /** 在飞工具调用快照（中断配对补偿输入）。 */
    public List<ToolCallAggregator.PendingToolUse> pendingToolUses() {
        return toolCalls.pendingToolUses();
    }

    // ==================== 工具结果截断（ToolResultWindow） ====================

    /**
     * 追加工具结果 delta，返回 head 16KB 窗口内应实时处理的部分；head 已满后仅入 tail 环形缓冲（中间 delta 丢弃）。
     *
     * @param toolCallId 工具调用 ID（交错并发下发时按此隔离窗口）
     * @param delta      工具结果增量文本
     * @return head 窗口内文本；无需实时处理时返回空串
     */
    public String appendToolResult(String toolCallId, String delta) {
        return toolResults.appendToolResult(toolCallId, delta);
    }

    /**
     * 结束工具结果接收（TOOL_RESULT_END 时调用）。
     *
     * @param toolCallId 工具调用 ID
     * @return 截断补发文本（省略标记 + tail + 截断通知）；未超出窗口或未登记时返回 {@code null}
     */
    public String endToolResult(String toolCallId) {
        return toolResults.endToolResult(toolCallId);
    }

    /**
     * 指定工具调用累积的 head 文本（工具失败时起始即错误文本，供错误分类）。
     *
     * @param toolCallId 工具调用 ID
     * @return head 窗口文本（无缓冲时返回空串）
     */
    public String toolResultHeadText(String toolCallId) {
        return toolResults.toolResultHeadText(toolCallId);
    }

    /**
     * 指定工具调用是否发生截断。
     *
     * @param toolCallId 工具调用 ID
     * @return true=累计输出超过单侧 16KB 窗口
     */
    public boolean toolResultTruncated(String toolCallId) {
        return toolResults.toolResultTruncated(toolCallId);
    }

    // ==================== HITL 候选批次（ConfirmCandidateBatch） ====================

    /**
     * 登记 HITL 待确认候选（TOOL_CALL_END 时调用，按到达顺序累积成批次）。
     *
     * @param toolCallId  SDK 工具调用 ID
     * @param toolName    工具名
     * @param inputJson   聚合完成后的入参 JSON（可空）
     * @param toolEventId {@code agent.tool_use} 落库事件公开 ID（{@code evt_}，requires_action 批次锚点）
     */
    public void rememberConfirmCandidate(String toolCallId, String toolName, String inputJson, String toolEventId) {
        confirmBatch.rememberConfirmCandidate(toolCallId, toolName, inputJson, toolEventId);
    }

    /**
     * 按信号批次 id 定位待确认候选（保持批次顺序，未登记的 id 收敛跳过）。
     *
     * @param toolCallIds 信号携带的待确认工具调用 id 批次
     * @return 命中的候选明细列表（无命中返回空列表，错配判定交应用层 HITL 编排）
     */
    public List<ConfirmCandidateBatch.ConfirmCandidate> confirmBatch(Collection<String> toolCallIds) {
        return confirmBatch.confirmBatch(toolCallIds);
    }

    /** HITL 候选批次组件（应用层 HITL 编排按错配诊断读取登记候选，见变更 D19）。 */
    public ConfirmCandidateBatch confirmCandidates() {
        return confirmBatch;
    }

    // ==================== 轮次守卫（RoundGuard） ====================

    /** 置位外部中断标记（终态路径据此表达 {@code session.interrupted}）。 */
    public void markInterrupted() {
        guard.markInterrupted();
    }

    /** 本轮是否已被外部中断。 */
    public boolean interrupted() {
        return guard.interrupted();
    }

    /**
     * 标记租约丢失（fail-closed）并按需触发中止句柄。
     *
     * @return true=本次置位（首次）；false=已置位（幂等短路）
     */
    public boolean markLeaseLost() {
        return guard.markLeaseLost();
    }

    /** 本轮是否已确证租约丢失（fail-closed 事实位）。 */
    public boolean leaseLost() {
        return guard.leaseLost();
    }

    /**
     * 注册 fail-closed 中止句柄：若租约标记已置位（早于句柄建立失效）则立即触发，保证中止信号不丢失。
     *
     * @param abort 中止句柄（取消流订阅 + 放行完成信号）
     */
    public void attachLeaseLostAbort(Runnable abort) {
        guard.attachLeaseLostAbort(abort);
    }

    /** 置位 HITL 挂起标记（挂起期间不领取终态化资格）。 */
    public void markConfirmationPending() {
        guard.markConfirmationPending();
    }

    /** 本轮是否处于 HITL 挂起（等待人工确认）。 */
    public boolean confirmationPending() {
        return guard.confirmationPending();
    }

    /** 置位超最大迭代轮次标记（供 AGENT_END 提前终态判定）。 */
    public void markExceedMaxIters() {
        guard.markExceedMaxIters();
    }

    /** 本轮是否已达最大迭代轮次。 */
    public boolean exceededMaxIters() {
        return guard.exceededMaxIters();
    }

    /**
     * 原子领取终态化资格：领取成功即本轮生成结束，清空进行中流与聚合缓冲（重连不再回补失效帧）。
     * <p>跨组件协调：CAS 在 {@link RoundGuard}，清位在 {@link TextBlockAccumulator}。</p>
     *
     * @return true=本次调用取得终态化资格；false=本轮已终态化（幂等短路）
     */
    public boolean tryFinalize() {
        boolean acquired = guard.tryAcquireFinalization();
        if (acquired) {
            text.dropInFlightAndPending();
        }
        return acquired;
    }

    // ==================== 模型名 / 文本落库 / 模型 span 散点 ====================

    /**
     * 记录最近一次模型调用携带的模型名（非空白才覆盖）。
     *
     * @param modelName 模型名（可为空，空则忽略）
     */
    public void rememberModelName(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            this.lastModelName = modelName;
        }
    }

    /**
     * 最近一次模型调用携带的模型名（{@code span.model_request_start} 的 model 字段兜底）。
     *
     * @return 模型名；本轮尚未记录时为 {@code null}
     */
    public String lastModelName() {
        return lastModelName;
    }

    /**
     * 领取本轮模型调用开始事件的 {@code evt_} ID（惰性生成）：开始与结束事件据此配对
     * （{@code span.model_request_end} 的 {@code model_request_start_id}），
     * 流式帧与落库事件共用同一 ID。
     *
     * @return 开始事件 ID（{@code evt_} 前缀）
     */
    public String ensureModelStartEventId() {
        if (modelStartEventId == null) {
            modelStartEventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        }
        return modelStartEventId;
    }

    /**
     * 取出并清空本轮模型调用开始事件 ID（结束事件配对后即作废；无开始事件返回 null）。
     *
     * @return 开始事件 ID（未开始过模型调用时为 null）
     */
    public String takeModelStartEventId() {
        String eventId = modelStartEventId;
        modelStartEventId = null;
        return eventId;
    }

    /** 标记本轮已落库 {@code agent.message}（缺失文本块时以 AGENT_RESULT 兜底）。 */
    public void markTextMessagePersisted() {
        this.textMessagePersisted = true;
    }

    /** 本轮是否已落库 {@code agent.message}。 */
    public boolean hasTextMessage() {
        return textMessagePersisted;
    }

    /** 记录模型调用开始时刻（{@code llm.call} span 起点，单模型并发场景取最近一次）。 */
    public void markModelCallStart() {
        modelSpanStart = now();
    }

    /**
     * 取出并清空模型调用开始时刻（结束事件配对后即作废）。
     *
     * @return span 起点时刻；本轮未开始过模型调用时为 {@code null}
     */
    public OffsetDateTime takeModelSpanStart() {
        OffsetDateTime start = modelSpanStart;
        modelSpanStart = null;
        return start;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}
