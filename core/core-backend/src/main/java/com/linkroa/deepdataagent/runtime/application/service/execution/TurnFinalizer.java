package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.agent.api.DeploymentRunLifecycleApi;
import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.McpToolRuntimeName;
import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.DeploymentOutcome;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.ConfirmCandidateBatch;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.ToolCallAggregator;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.service.Decision;
import com.linkroa.deepdataagent.runtime.domain.service.TurnFinalizationPolicy;
import com.linkroa.deepdataagent.runtime.domain.service.TurnResult;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.security.SecretMasker;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * turn 收口服务（decompose-command-facade 3.1）：轮次四终局出口（正常 / 失败 / 中断 / 挂起）
 * 与终态唯一编排、严格排空协议事务侧协作、调度运行终局回写的自持服务。
 * <p>方法体自原命令门面（decompose-command-facade 4.4 已物理删除）纯搬移，四条红线（design Context 硬约束）
 * 次序与语义零变化：
 * <ol>
 *   <li><b>严格排空协议</b>（{@code saveAndBroadcastTerminal} / {@code enterWaitingConfirm}）：
 *       事务<b>外</b> {@code chatEventPersister.flush()} 整队排空 + 事务<b>首行</b>
 *       {@code requireLedgerDurable} 按会话校验落库失败标记，落库失败整批回滚、会话状态不迁移；
 *       排空 MUST NOT 置于事务内（否则本会话回滚波及他会话已落库事实）；</li>
 *   <li><b>D19 拒绝挂起</b>：批次 / 候选错配抛点（{@code enterWaitingConfirm} 首段）位于 flush
 *       与事务<b>之前</b>，两条对称分支归一为「空批次」拒绝，异常沿 {@code onStreamError →
 *       finalizeFailed} 收敛为执行错误终态，MUST NOT 落任何等待事件；</li>
 *   <li><b>防止永久阻塞的收尾</b>：落库失败标记命中时在 catch 内捕获处理（真 no-op + {@code turn.finish()} +
 *       释放租约），不写终态、不发 {@code TurnFinished}，MUST NOT 让异常穿透流回调线程；</li>
 *   <li><b>挂起即物理轮终局</b>：{@code clearInFlightStreamOnSuspend} →
 *       {@code markConfirmationPending} → {@code turn.finish()} → 释放租约的先后顺序不变。</li>
 * </ol></p>
 * <p>依赖方向（design D3 破环）：{@code enterWaitingConfirm} 归本收口家族而非 HITL 服务，
 * 令 {@code TurnExecutionService → TurnFinalizer} 单向成立；落库推流底座
 * {@link TurnEventWriter} 由收口与执行两簇共用（{@code Finalizer → Writer}）。</p>
 */
@Service
public class TurnFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TurnFinalizer.class);

    private static final String DEEP_AGENT_RUN_ERROR = "DEEP_AGENT_RUN_ERROR";
    /** 中断配对补偿的合成工具结果正文（表达「调用被中断而未执行完成」）。 */
    private static final String INTERRUPTED_TOOL_OUTPUT = "工具调用被中断，未执行完成";

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private ChatEventPersister chatEventPersister;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private TurnEventWriter turnEventWriter;
    @Resource
    private CoordinationLeaseService coordinationLeaseService;
    /** Spring 原生事件发布器（工具性设施直注，迭代裁决 2026-09-13）：轮次终局在终态事务内发布
     *  {@link TurnFinished}，由 {@code infrastructure.assembly.DeploymentRunOutcomeListener}
     *  于 AFTER_COMMIT 回写调度运行终态（回滚不发）。 */
    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * 进入 HITL 等待确认态（durable）：挂起事务<b>前</b>先在事务外整队排空（design D1 严格排空
     * 协议），事务首行按会话校验落库失败标记——通过后（本会在队明细均已确认落库）才执行状态迁移 + 批次明细 /
     * 状态事件同事务落库，<b>挂起即物理轮终局</b>——标记等待守卫后放行完成信号（释放阻塞虚拟线程、
     * 收轮并释放 turn 租约），等待事实只存事件表与会话状态，进程内零驻留。
     * <p><b>持久化原子性</b>：任一待确认明细无法确认落库（事务前已排空、首行命中落库失败标记）时抛异常，
     * 整事务回滚、会话保持 {@code processing}（宁可不挂起，也不留下「状态已等待而事件表缺明细、
     * 确认永久 404」的会话）；排空在事务外独立提交，他会话事件行不随本会话回滚丢失。
     * 落库失败标记校验异常在本方法内捕获处理（design D2 防止永久阻塞）：仍必达收轮信号与租约释放，MUST NOT 让流回调线程
     * 携带该异常上抛而令 {@code awaitFinish()} 永久阻塞、续约永不停止。</p>
     * <p><b>批次对齐门禁（D19）</b>：状态迁移<b>之前</b>先定位候选批次，批次与登记无法对齐到任何候选
     * （空批次）时抛 {@link DeepDataAgentException} 拒绝挂起——此路径在事务之前抛出、不涉及回滚，
     * 与上方在本方法内捕获处理的落库失败标记校验异常不同，沿 {@code doOnNext → onStreamError → finalizeFailed} 收敛为执行错误终态
     * （回 {@code idle} + {@code session.error}），不落任何等待事件（见 {@code runtime/hitl} 规格「挂起侧批次对齐」）。</p>
     */
    public void enterWaitingConfirm(ExecutionContext context, AgentStreamSignal signal,
                                    TurnControl turn) {
        String sessionId = context.session().sessionId();
        TurnRunState runState = context.runState();
        // 批次明细：信号工具调用 id 回读候选现场（TOOL_CALL_END 登记，含 evt_ 公开事件 id 锚点）
        List<ConfirmCandidateBatch.ConfirmCandidate> batch = runState.confirmBatch(signal.toolCallIds());
        // D19（变更 decompose-turn-pipeline D4）：批次与候选登记错配即拒绝挂起——两条对称分支
        // （① 批次 id 全部未命中候选；② 本轮候选登记为空，含信号未携带任何 id）在删除静默兜底后
        // 均归一为「空批次」。抛点在状态迁移与挂起事务<b>之前</b>（confirmBatch 先于 transactionTemplate.execute，
        // 此路径不涉及任何回滚），异常沿信号消费链 doOnNext → onError → onStreamError → finalizeFailed
        // 收敛为执行错误终态：processing → idle + session.error + session.status_idle(error) 同事务落库并广播，
        // 会话回 idle、轮次租约随收轮释放；MUST NOT 落 session.status_waiting_confirmation / session.requires_action。
        if (batch.isEmpty()) {
            ConfirmCandidateBatch candidates = runState.confirmCandidates();
            log.warn("HITL 挂起批次与候选登记错配，拒绝确立等待: sessionId={}, replyId={}, "
                            + "signalToolCallIds={}, candidateIds={}",
                    sessionId, signal.replyId(), signal.toolCallIds(), candidates.registeredIds());
            throw new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": HITL 挂起批次与本轮待确认候选无法对齐，"
                    + "拒绝确立等待（请重新发起该轮）: sessionId=" + sessionId
                    + ", replyId=" + signal.replyId()
                    + ", signalToolCallIds=" + signal.toolCallIds()
                    + ", registeredCandidateCount=" + candidates.registeredCount()
                    + ", registeredCandidateIds=" + candidates.registeredIds());
        }
        // 命中候选必带 TOOL_CALL_START 惰性分配的 evt_ 锚点，故 batch 非空时 eventIds 必非空；
        // 批次部分命中时明细仅含已对齐候选的公开事件 id（等待现场由事件表这些工具调用行承载）
        List<String> eventIds = batch.stream()
                .map(ConfirmCandidateBatch.ConfirmCandidate::toolEventId)
                .filter(StringUtils::isNotBlank)
                .toList();
        // 等待现场完全由事件表承载：候选工具调用事件（agent.tool_use / agent.mcp_tool_use）已随轮内落库，
        // 挂起只做相位迁移（running → awaiting_confirmation，对外 status 恒为 running），
        // MUST NOT 落 session.status_waiting_confirmation / session.requires_action 等状态事件。
        // CAS 仅当 phase running → awaiting_confirmation 成功时写入；失败（并发取消 / 归档已离开
        // running 相位）不挂起、不放行完成信号，其流收流经 onStreamComplete 正常收敛终态
        Boolean casSuccess;
        try {
            // 严格排空协议·事务前段：整队排空（事务外逐条独立提交，他会话事件行不进入本会话事务）
            chatEventPersister.flush();
            casSuccess = transactionTemplate.execute(status -> {
                // 事务首行校验落库失败标记：待确认明细（agent.tool_use 等低 seq 流内事件）已随事务前 flush 排空，
                // 本会话若命中落库失败标记则整事务回滚——杜绝「相位已等待而事件表缺明细、确认永久 404」的会话
                requireLedgerDurable(sessionId);
                return sessionRepository.transition(sessionId, Transition.PHASE_AWAIT) > 0;
            });
        } catch (DeepDataAgentException ex) {
            // 编排层防止永久阻塞的收尾（design D2）：不落等待事件、不置等待守卫，但本轮仍必达收轮信号与
            // 租约释放——会话保持 processing，由租约失效与启动复位路径收敛，用户可重新发起
            log.error("HITL 挂起事务严格排空协议失败（事务前已排空、首行命中落库失败标记），整批回滚且不进入"
                    + "等待确认态（明细不可信时宁不挂起）: sessionId={}, replyId={}",
                    sessionId, signal.replyId(), ex);
            turn.finish();
            coordinationLeaseService.releaseTurnLease(sessionId);
            return;
        }
        if (!Boolean.TRUE.equals(casSuccess)) {
            log.warn("进入等待确认态 CAS 失败（会话已离开 running 相位，跳过挂起收尾）: sessionId={}, replyId={}",
                    sessionId, signal.replyId());
            return;
        }
        // 挂起即物理轮终局，流式块不会再收到 TEXT_END / THINKING_END：显式清理进行中流快照，
        // 使重连不再回补一条永远等不到收尾的 event_start（design D3）
        runState.clearInFlightStreamOnSuspend();
        // 挂起即物理轮终局：守卫置位（AGENT_END / onComplete 依此不终态化）→ 完成阻塞等待收轮
        runState.markConfirmationPending();
        turn.finish();
        // 等待横跨用户思考时间（不定长）：显式释放 turn 租约，不占跨进程执行独占
        coordinationLeaseService.releaseTurnLease(sessionId);
        log.info("进入 HITL 等待确认态（durable 挂起收轮）: sessionId={}, replyId={}, batch={}",
                sessionId, signal.replyId(), eventIds.size());
    }

    /**
     * 正常终态唯一入口（AGENT_END 提前 / onComplete 兜底共用）：本轮以
     * {@code COMPLETED}（正常结束）或 {@code MAX_ITERATIONS}（迭代上限）交终态唯一编排
     * （{@link #saveAndBroadcastTerminal}）按决策表收尾，终态事件<b>按实际迁入状态</b>构造
     * 并随迁移同事务落库。
     * <p>「首选 terminated、取消抢跑回退 idle」的交错推导已下沉
     * {@code TurnFinalizationPolicy}（决策表第 2 / 3 行）：{@code TO_TERMINATED}
     * （前置仅 processing）CAS 未命中时同链回退 {@code TO_IDLE}（前置含 canceling）收敛回 idle
     * （取消优先），终态事件集相应改写为 {@code session.interrupted} + {@code session.status_idle}，
     * {@code canceling} 在任何交错下都不成为静默死路。</p>
     */
    public void finalizeNormal(ExecutionContext context, TurnControl turn) {
        TurnRunState runState = context.runState();
        // fail-closed 守卫：租约丢失后任何终态汇聚路径都不得写终态（中止句柄负责完成等待）
        if (runState.leaseLost()) {
            log.warn("turn 租约已丢失，跳过正常终态收敛（fail-closed）: sessionId={}", context.sessionId());
            return;
        }
        // 终态幂等守卫：AGENT_END 提前终态后 onComplete 兜底不得重复终态化（CAS 领取终态资格）
        if (!runState.tryFinalize()) {
            log.debug("本轮已终态化，跳过重复正常终态: sessionId={}", context.sessionId());
            return;
        }
        // 缺失文本块时以 AGENT_RESULT 最终文本兜底落一条 agent.message
        if (!runState.hasTextMessage() && StringUtils.isNotBlank(runState.output())) {
            turnEventWriter.persistAndBroadcast(context, ChatEventFactory.INSTANCE.agentMessage(runState.output()));
        }
        TurnResult result = runState.exceededMaxIters() ? TurnResult.maxIterations() : TurnResult.completed();
        saveAndBroadcastTerminal(context, result);
        log.info("turn 终态落库完成: sessionId={}, maxIters={}", context.sessionId(),
                runState.exceededMaxIters());
        turn.finish();
    }

    /**
     * 兜底失败终态（agent 构建 / 注册 / 终态化回调抛出的异常路径）：可恢复执行错误回 idle，
     * 伴随 {@code session.error} 事件（不可恢复失败进 terminated 语义由显式终止指令承载）。
     * <p>与 {@code onStreamError} 同理维持进程内 {@code interrupted} 判定，
     * 不接入执行侧两源取消谓词（见 {@code TurnExecutionService#isCancelRequested} javadoc）。</p>
     *
     * @param setupFailure 是否发生在事件流启动前（构建 / 注册失败）
     */
    public void finalizeAsFailure(ExecutionContext context, Throwable ex, boolean setupFailure) {
        if (setupFailure || !context.runState().interrupted()) {
            finalizeFailed(context, blankToDefault(ex.getMessage(), "agent 执行失败"));
        } else {
            finalizeInterrupted(context);
        }
    }

    /**
     * 执行错误终态（决策表第 5 行）：会话回 idle + {@code session.error}（含 type/code/message）
     * + 状态事件同事务落库并广播；错误信息在构造轮次结果前完成脱敏——先按本轮挂载保管库凭据
     * 明文精确掩码（MCP 注册 / 调用异常消息可能回显 token），再走形态脱敏 + 截断。
     */
    public void finalizeFailed(ExecutionContext context, String message) {
        // fail-closed 守卫：租约丢失后任何终态汇聚路径都不得写终态（含流异常 / catch 异常收敛）
        if (context.runState().leaseLost()) {
            log.warn("turn 租约已丢失，跳过失败终态收敛（fail-closed）: sessionId={}", context.sessionId());
            return;
        }
        // 终态幂等守卫：与 finalizeNormal / finalizeInterrupted 互斥，仅首个终态路径生效
        if (!context.runState().tryFinalize()) {
            log.debug("本轮已终态化，跳过重复错误终态: sessionId={}", context.sessionId());
            return;
        }
        String sanitized = SecretMasker.sanitize(SecretMasker.maskExactValues(
                blankToDefault(message, "agent 执行失败"), context.runState().mountedVaultSecrets()));
        saveAndBroadcastTerminal(context, TurnResult.executionError(sanitized));
    }

    /**
     * 中断终态（决策表第 4 行）：会话回 idle + 收场二事件（{@code thread_status_idle} +
     * {@code status_idle}，{@code stop_reason.type=interrupted}）同事务落库并广播。
     * <p>经前置含 canceling 的 {@code TO_IDLE} 迁移收敛，取消链 {@code processing → canceling → idle} 由此闭环。</p>
     * <p><b>中断配对补偿</b>：收场前先对本轮仍执行中的内置 / MCP 工具调用补合成错误结果
     * （{@code agent.tool_result} / {@code agent.mcp_tool_result}），保证事件表无悬空 tool_use；
     * 合成结果不落 {@code session.error}、不改写中断终态语义（终态仍为收场二事件
     * {@code stop_reason=interrupted}）。</p>
     */
    public void finalizeInterrupted(ExecutionContext context) {
        // fail-closed 守卫：租约丢失后中断收敛路径同样不得写终态（执行权已丧失，终态权不归本实例）
        if (context.runState().leaseLost()) {
            log.warn("turn 租约已丢失，跳过中断终态收敛（fail-closed）: sessionId={}", context.sessionId());
            return;
        }
        // 终态幂等守卫：与 finalizeNormal / finalizeFailed 互斥，仅首个终态路径生效
        if (!context.runState().tryFinalize()) {
            log.debug("本轮已终态化，跳过重复中断终态: sessionId={}", context.sessionId());
            return;
        }
        compensateInFlightToolCalls(context);
        saveAndBroadcastTerminal(context, TurnResult.interrupted());
    }

    /**
     * 中断配对补偿（见 runtime/events 规格「中断不留下悬空 tool_use」）：对本轮 TOOL_CALL_END
     * 已落库 tool_use 但结果未到的执行中工具调用，逐条补落合成错误结果（MCP 实名走
     * {@code agent.mcp_tool_result}，其余走 {@code agent.tool_result}）。合成结果经写入底座
     * 落库并在收场事务前随严格排空协议整队排空，seq 恒低于收场二事件（先于终态可见）。
     */
    private void compensateInFlightToolCalls(ExecutionContext context) {
        List<ToolCallAggregator.PendingToolUse> pending = context.runState().pendingToolUses();
        if (pending.isEmpty()) {
            return;
        }
        for (ToolCallAggregator.PendingToolUse call : pending) {
            turnEventWriter.persistAndBroadcast(context, syntheticInterruptedResult(call));
        }
        log.info("中断配对补偿：为 {} 个执行中工具调用补落合成错误结果: sessionId={}",
                pending.size(), context.sessionId());
    }

    /** 合成中断错误结果装配：MCP 实名（{@code mcp__} 前缀）走 MCP 形态，其余走内置形态。 */
    private static AssembledEvent syntheticInterruptedResult(ToolCallAggregator.PendingToolUse call) {
        String mcpServerName = McpToolRuntimeName.serverNameOf(call.toolName());
        if (mcpServerName == null) {
            return ChatEventFactory.INSTANCE.toolResult(call.toolCallId(), call.toolName(),
                    "error", INTERRUPTED_TOOL_OUTPUT, false);
        }
        return ChatEventFactory.INSTANCE.mcpToolResult(call.toolCallId(), call.toolName(), mcpServerName,
                "error", INTERRUPTED_TOOL_OUTPUT, false);
    }

    /**
     * 调度运行终局语义 → agent BC 发布语言值（跨 BC 契约常量仅出现在应用层，领域决策表不持有）。
     */
    private static String deploymentOutcomeValue(DeploymentOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> DeploymentRunLifecycleApi.OUTCOME_SUCCEEDED;
            case FAILED -> DeploymentRunLifecycleApi.OUTCOME_FAILED;
            case TERMINATED -> DeploymentRunLifecycleApi.OUTCOME_TERMINATED;
        };
    }

    /**
     * 按决策链顺序执行迁移尝试，返回首个命中（受影响行数 &gt; 0）的尝试；全链未命中返回 {@code null}
     * （真 no-op：会话被并发归档 / 终止，显式指令胜出）。
     * <p><b>CAS 仍是终态资格的唯一仲裁者</b>：链上每个尝试的前置集合来自状态机声明，
     * 并发抢跑只会让后续尝试命中或全链落空，不会出现「事件与实际状态错位」。</p>
     */
    private Decision.Attempt adoptAttempt(String sessionId, Decision decision) {
        List<Decision.Attempt> attempts = decision.attempts();
        for (int index = 0; index < attempts.size(); index++) {
            Decision.Attempt attempt = attempts.get(index);
            if (sessionRepository.transition(sessionId, attempt.transition()) > 0) {
                if (index > 0) {
                    log.warn("首选终态迁移未命中（取消 / 终态抢跑），按决策链回退收敛: sessionId={}, 首选={}, 实选={}",
                            sessionId, attempts.get(0).transition().to(), attempt.transition().to());
                }
                return attempt;
            }
        }
        return null;
    }

    /**
     * 终态唯一编排（决策表落地）：终态事务<b>前</b>先整队排空该会话在队事件（事务外独立提交，
     * design D1 严格排空协议），事务首行按会话校验落库失败标记——存在未落库事件即抛异常令整事务回滚；
     * 随后窄读当前状态 → 调 {@code TurnFinalizationPolicy} 决策 → 按尝试链执行状态迁移 →
     * <b>按实际迁入结果构造</b>终态事件并逐条同事务落库（回放必见终态）→ 发终局事件，
     * 提交后按序广播<b>同一事件对象</b>（回放 + 实时订阅重合窗口按 id 去重），最后释放 turn 租约。
     * <p>排空 MUST 置于事务外：他会话事件行不进入本会话 JDBC 事务，本会话落库失败回滚
     * 不波及他会话已落库事实（事务内排空会跨会话静默丢事件表，design D1 被否决项）。</p>
     * <p>决策为 no-op（显式指令胜出）或迁移全链未命中时不落不广播任何终态事件，
     * 避免与显式终态语义冲突；迁移与事件构造同事务，事件集与实际状态不可能错位。</p>
     * <p><b>编排层防止永久阻塞的收尾（design D2）</b>：严格排空协议异常（事务首行命中落库失败标记）在本方法
     * 内捕获处理——记结构化 ERROR、不重试任何状态迁移、按真 no-op 处理，令调用方
     * （{@code finalizeNormal} / {@code finalizeFailed} / {@code finalizeInterrupted}）尾部的
     * {@code turn.finish()} 必达、方法尾部既有的 {@code releaseTurnLease} 照常执行。
     * MUST NOT 让该异常穿透到流回调线程——否则 {@code runStream} 将永久阻塞在
     * {@code awaitFinish()}、续约任务永不停止，会话被自己的续约卡死在 {@code processing}
     * （比缺行更糟）。</p>
     *
     * @param context 执行现场
     * @param result  轮次结果（决策表输入侧，已由调用方完成分类与脱敏）
     */
    private void saveAndBroadcastTerminal(ExecutionContext context, TurnResult result) {
        String sessionId = context.session().sessionId();
        List<ChatEvent> saved = new ArrayList<>();
        List<ChatEvent> toBroadcast = List.of();
        boolean ledgerDurable = true;
        try {
            // 严格排空协议·事务前段：整队排空（事务外逐条独立提交，他会话事件行不进入本事务、
            // 不随本会话落库失败回滚丢失），排空完整性由事务首行校验落库失败标记判定
            chatEventPersister.flush();
            List<ChatEvent> committed = transactionTemplate.execute(status -> {
                // 事务首行校验落库失败标记：flush 已排空全部在队事件，本会话若命中落库失败标记则整事务回滚，
                // 杜绝「无终局事件的终态会话」
                requireLedgerDurable(sessionId);
                Decision decision = TurnFinalizationPolicy.decide(result,
                        sessionRepository.currentStatus(sessionId).orElse(null));
                if (decision.noOp()) {
                    // 决策表第 7 行：会话已归档 / 已终止，显式指令胜出（现状等价于迁移必然 0 行）
                    log.warn("轮次终态决策为 no-op（显式终态胜出），不落不广播终态事件: sessionId={}, reason={}",
                            sessionId, decision.noOpReason());
                    return List.of();
                }
                Decision.Attempt adopted = adoptAttempt(sessionId, decision);
                if (adopted == null) {
                    // 会话已非 running（如已被终止 / 归档，显式指令胜出）：真 no-op，不落不广播终态事件
                    log.warn("会话终态迁移未命中（已归档/已终止），不落不广播终态事件: sessionId={}", sessionId);
                    return List.of();
                }
                for (TerminalEventSpec spec : adopted.events()) {
                    AssembledEvent event = ChatEventFactory.INSTANCE.terminalEvent(spec);
                    // 线程归属取自执行现场（轮内路径）
                    ChatEvent chatEvent = ChatEvent.create(sessionId, event.type(),
                            event.payloadJson(), turnEventWriter.nextSequence(sessionId), null, context.sessionThreadId());
                    chatEventRepository.save(chatEvent);
                    saved.add(chatEvent);
                }
                sessionRepository.touchLastActive(sessionId);
                // 终局事件随本事务发布：提交成功后 AFTER_COMMIT 监听器才回写调度运行
                // （非调度触发由监听器按空 triggerType 忽略；真 no-op / 回滚均不发）
                applicationEventPublisher.publishEvent(new TurnFinished(sessionId, context.session().triggerType(),
                        deploymentOutcomeValue(adopted.outcome())));
                return List.copyOf(saved);
            });
            toBroadcast = committed == null ? List.of() : committed;
        } catch (DeepDataAgentException ex) {
            // 事件表不可信（事务首行命中落库失败标记：存在重试耗尽未落库事件）：终态权移交给复位路径，
            // fail-closed 语义同 leaseLost
            ledgerDurable = false;
            log.error("终态事务严格排空协议失败（事务前已排空、首行命中落库失败标记），整批回滚且不迁移会话状态"
                    + "（保持 processing，由租约失效与启动复位收敛；本轮仍照常收轮）: sessionId={}",
                    sessionId, ex);
        }
        if (!toBroadcast.isEmpty()) {
            toBroadcast.forEach(event -> turnEventWriter.pushQuietly(context.sessionContext(), event));
        } else if (ledgerDurable) {
            // 迁移未命中的告警已在事务内发出，此处仅在事务正常提交而仍无终态事件时兜底
            log.debug("本轮未产生终态事件（no-op / 未命中）: sessionId={}", sessionId);
        }
        // 终态出口协调层清理：释放 turn 租约（幂等；状态迁移失败 / 排空失败场景同样释放，
        // 即刻让出执行权而非等 TTL，令会话可被复位路径接管）
        coordinationLeaseService.releaseTurnLease(sessionId);
    }

    /**
     * 严格排空协议·事务首行校验落库失败标记（前置校验，design D1）：本会话存在「重试耗尽仍未落库」事件时抛
     * 业务异常，令外层状态迁移事务（终态 / HITL 挂起）整批回滚、会话状态不迁移。
     * <p>调用前提：已在本会话事务开启<b>前</b>经 {@code chatEventPersister.flush()} 整队排空
     * （独立提交）——落库失败标记仅在落库线程持锁写失败瞬间置位，排空完成后校验结果即与排空结果一致；
     * 落库失败标记集合按 sessionId 隔离，他会话标记落库失败不影响本会话判定。</p>
     */
    private void requireLedgerDurable(String sessionId) {
        if (chatEventPersister.isPoisoned(sessionId)) {
            throw new DeepDataAgentException("事件表存在重试耗尽未落库事件（严格排空协议命中落库失败标记），"
                    + "拒绝状态迁移: sessionId=" + sessionId);
        }
    }

    /** 空串兜底：值为 null 或空白时返回 fallback（与门面既有私有工具逐字同形，随收口簇复用）。 */
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
