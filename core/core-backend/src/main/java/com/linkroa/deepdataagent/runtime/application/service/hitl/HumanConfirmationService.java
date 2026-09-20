package com.linkroa.deepdataagent.runtime.application.service.hitl;

import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.port.AgentRunExecutor;
import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.application.service.execution.RoundContext;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnEventWriter;
import com.linkroa.deepdataagent.runtime.application.service.execution.ExecutionContext;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnExecutionService;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.PendingToolCallSpec;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * HITL 人工确认入口服务（decompose-command-facade 4.1）：确认 / 拒绝指令的领取编排与
 * durable 续跑轮驱动（自原命令门面（decompose-command-facade 4.4 已物理删除）的 HITL 分区纯搬移，事务边界、
 * 异常类型与包装、日志语义、调用次序零变化）。
 * <p>durable 语义：等待现场完全由事件表承载（最新 {@code session.requires_action} 批次明细 +
 * 等待状态），确认解析不依赖任何进程内存对象——任意实例（含服务重启后）、任意等待时长均可完成
 * 裁决并续跑同一逻辑轮。事件表定位 / 明细重建规则在 {@link PendingBatchResolver}（2.2），
 * 挂起收尾（{@code enterWaitingConfirm}）在 {@code execution.TurnFinalizer}（3.1），
 * 续跑轮骨架经 {@link TurnExecutionService} 的 public 执行入口（3.2）。</p>
 * <p><b>依赖方向（design D3）</b>：{@code HumanConfirmationService → TurnExecutionService} 单向成立
 * （续跑执行入口），MUST NOT 出现反向注入；入站簇的自动裁决经本服务领取
 * （{@code InboundEventService → HumanConfirmationService}）。</p>
 */
@Service
public class HumanConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(HumanConfirmationService.class);

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    private static final String DEEP_AGENT_RUN_ERROR = "DEEP_AGENT_RUN_ERROR";

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    /** 执行端口：续跑轮以 {@code resumeConfirmation} 注入确认 / 拒绝结果并重建挂起现场。 */
    @Resource
    private AgentRunExecutor agentRunExecutor;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private TransactionTemplate transactionTemplate;
    /** 落库端口：仅用于续跑轮启动时清除落库失败标记（design D1，挂起轮的持久化失败标志不带入续跑轮）。 */
    @Resource
    private ChatEventPersister chatEventPersister;
    /** 线程仓储：领取事务内解析主线程归属，续跑轮事件随执行现场挂接归属。 */
    @Resource
    private SessionThreadRepository sessionThreadRepository;
    /** HITL 待确认批次解析器（decompose-command-facade 2.2）：锚点定位等待批次与事件表明细重建。 */
    @Resource
    private PendingBatchResolver pendingBatchResolver;
    /** turn 事件写入器（decompose-command-facade 2.3）：续跑轮恢复状态事件的落库 + 广播。 */
    @Resource
    private TurnEventWriter turnEventWriter;
    /** turn 执行链服务（decompose-command-facade 3.2）：续跑复用其 public 的执行控制面 /
     *  现场装配 / 跑轮入口（不为其增设端口）。 */
    @Resource
    private TurnExecutionService turnExecutionService;
    @Resource(name = "agentVirtualExecutor")
    private Executor virtualExecutor;

    // ==================== HITL：人工确认 / 拒绝（durable：事件表事实 + 重建续跑） ====================

    /**
     * 处理人工确认指令（确认 / 拒绝，对应入站 {@code user.tool_confirmation}）。
     * <p>durable 语义：等待现场完全由事件表承载（最新 {@code session.requires_action}
     * 批次明细 + 等待状态），确认解析不依赖任何进程内存对象——任意实例（含服务重启后）、
     * 任意等待时长均可完成裁决并续跑同一逻辑轮。</p>
     * <pre>{@code
     *  resolveHumanConfirmation
     *    ├─ requireOwnedSession + 锚点定位等待批次（批次成员判定，存量旧形态双键兼容；未命中 → 404）
     *    ├─ turn 租约 NX 抢占（先于 PG CAS；确证占用 → 409）
     *    ├─ [领取事务] CAS(waiting_confirmation→processing)（0 行 → 409，失败归还租约）
     *    ├─ 按 event_ids 批查 agent.tool_use 行重建整批明细（id/name/input 取自事件表 payload）
     *    └─ 虚拟线程 → resumeRound：装配重建 Agent + 注入确认/拒绝结果续跑直至终态
     * }</pre>
     */
    public void resolveHumanConfirmation(ResolveHumanConfirmationCommand command) {
        String sessionId = command.sessionId();
        AgentSession session = requireOwnedSession(sessionId);
        // 1. 锚点定位：仅匹配会话最新等待批次（已被新批次取代的旧锚点按不存在处理 → 404）
        List<String> batchEventIds = pendingBatchResolver.locatePendingBatch(sessionId, command.toolUseId());
        if (batchEventIds.isEmpty()) {
            throw new ResourceNotFoundException("无待确认项或锚点不属于当前等待项: " + sessionId);
        }
        // 2. 领取：先抢占 turn 租约（D4：NX 先于 PG CAS），再进领取事务做状态 CAS，CAS 失败归还租约
        turnExecutionService.acquireTurnLeaseOrConflict(sessionId, "会话已有活跃执行租约，请稍后再试");
        ExecutionContext context;
        try {
            context = transactionTemplate.execute(status -> {
                if (sessionRepository.transition(sessionId, Transition.PHASE_RESUME) <= 0) {
                    // 锚点可定位但等待已被解析 / 作废（并发方胜出、中断或已离开等待态）→ 409
                    throw new ResourceConflictException("待确认项已被解析或等待已作废: " + sessionId);
                }
                AgentSessionContext sessionContext = sessionRegistry.getOrCreate(session);
                long dbMax = chatEventRepository.nextSequenceNum(sessionId) - 1L;
                // 线程归属：领取事务内解析主线程一次，续跑轮次事件随执行现场挂接归属
                return new ExecutionContext(sessionContext, sessionContext.beginRound(dbMax),
                        resolveMainThreadId(sessionId));
            });
        } catch (RuntimeException ex) {
            turnExecutionService.releaseTurnLeaseQuietly(sessionId, "领取事务");
            throw ex;
        }
        if (context == null) {
            turnExecutionService.releaseTurnLeaseQuietly(sessionId, "领取事务");
            throw new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 创建人工确认续跑现场失败");
        }
        // 启动时清除落库失败标记（design D1）：续跑轮领取事务（租约 CAS + waiting→processing CAS）已提交，
        // 挂起轮的持久化失败标志不带入续跑轮；提交后执行，非事务内存操作且幂等
        chatEventPersister.clearPoisonFlag(sessionId);
        // 3. 整批明细重建（只读事件表查询，领取事务提交后执行）
        List<PendingToolCallSpec> specs = pendingBatchResolver.rebuildPendingBatch(sessionId, batchEventIds);
        if (specs.isEmpty()) {
            throw new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 待确认明细重建失败（事件表缺失工具调用行）");
        }
        virtualExecutor.execute(() -> resumeRound(context, session, specs, command.confirmed(),
                command.denyMessage()));
    }

    /**
     * durable 续跑轮（确认 / 拒绝共用）：领取事务已置 processing，本轮按 {@code executeRound}
     * 同构骨架执行——广播恢复状态事件 → 装配重建 Agent（会话固定版本）→ 订阅续流复用
     * 轮次编排（handleSignal / 终态唯一出口 / 租约终态释放）。
     */
    private void resumeRound(ExecutionContext context, AgentSession session, List<PendingToolCallSpec> specs,
                             boolean allowed, String denyMessage) {
        String sessionId = session.sessionId();
        log.info("人工{}（durable 续跑轮）: sessionId={}, batch={}", allowed ? "确认" : "拒绝",
                sessionId, specs.size());
        // 续跑不写会话状态事件（对外 status 恒为 running，等待 / 续跑仅是内部相位迁移）
        turnExecutionService.withTurn(context.sessionContext(), turn ->
                runResumeStream(context, session, specs, allowed, denyMessage, turn));
    }

    /**
     * 续跑轮执行体（与 {@code TurnExecutionService.runAgent} 同构骨架，变更 R10）：装配重建 → 订阅续流并阻塞 → 释放
     * 全部委托 {@code RoundExecutionTemplate}，本方法只提供续跑流供给（{@code resumeConfirmation}：
     * 重建式续跑，AgentScope 从 PG 状态存储载入会话记忆，待应答 tool_use 的挂起点状态由 SDK
     * persistPendingRequestReplyId 随挂起落库）。
     * <p>骨架装配与执行经 {@link TurnExecutionService} 的 public 续跑入口完成（不为其增设端口）。</p>
     */
    private void runResumeStream(ExecutionContext context, AgentSession session, List<PendingToolCallSpec> specs,
                                 boolean allowed, String denyMessage,
                                 TurnControl turn) {
        String sessionId = session.sessionId();
        RoundContext ctx = turnExecutionService.newRoundContext(context, session, turn, "续跑轮",
                ex -> log.warn("续跑轮 Agent 释放异常: sessionId={}", sessionId, ex));
        turnExecutionService.runRound(ctx, agent -> agentRunExecutor.resumeConfirmation(
                agent, specs, sessionId, session.userId(), allowed, denyMessage));
    }

    // ==================== 私有工具方法（随 HITL 簇自持副本，与门面既有实现逐字同形） ====================

    /**
     * 按 ID 查询会话，不存在时抛 404（会话相关用例的统一前置校验）。
     * <p>本方法不做 owner 校验，由 {@link #requireOwnedSession} 复用。</p>
     */
    private AgentSession requireSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在"));
    }

    /**
     * 按 ID 查询会话并校验归属（owner 隔离）：越权与不存在不可区分（统一 404 语义）。
     */
    private AgentSession requireOwnedSession(String sessionId) {
        AgentSession session = requireSession(sessionId);
        if (!session.ownedBy(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在");
        }
        return session;
    }

    /**
     * 解析会话主线程归属 ID（轮外低频落库路径现查一次；主线程缺失降级 null = 无归属，不阻断落库）。
     * <p>轮内高频路径不经本方法：归属在执行现场构造（启动 / 领取事务）时解析一次，
     * 随 {@link ExecutionContext#sessionThreadId()} 传递。</p>
     */
    private String resolveMainThreadId(String sessionId) {
        return sessionThreadRepository.findMain(sessionId)
                .map(SessionThread::threadId)
                .orElse(null);
    }
}
