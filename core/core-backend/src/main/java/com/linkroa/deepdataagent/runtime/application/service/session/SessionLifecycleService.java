package com.linkroa.deepdataagent.runtime.application.service.session;

import com.linkroa.deepdataagent.agent.api.AgentSnapshotApi;
import com.linkroa.deepdataagent.agent.api.DeploymentRunLifecycleApi;
import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.UpdateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeConvert;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.application.service.PayloadJson;
import com.linkroa.deepdataagent.runtime.application.service.assembly.RuntimeAgentAssemblyService;
import com.linkroa.deepdataagent.runtime.application.service.assembly.SessionMountMaterializer;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnEventWriter;
import com.linkroa.deepdataagent.runtime.application.service.UserIds;
import com.linkroa.deepdataagent.runtime.application.service.VersionNumbers;
import com.linkroa.deepdataagent.runtime.application.validation.InboundEventValidator;
import com.linkroa.deepdataagent.runtime.application.validation.SessionEnvironmentVariablesValidator;
import com.linkroa.deepdataagent.runtime.application.validation.SessionMountValidator;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory.AssembledEvent;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.MountViolationException;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.port.NoOpConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.runtime.domain.service.SessionMountPolicy;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 会话生命周期入口服务（decompose-command-facade 4.3）：会话写操作（创建 / 更新 / 归档 / 删除 /
 * 中断）与挂载资源管理（追加 / 轮换令牌 / 移除）自原命令门面（decompose-command-facade 4.4 已物理删除）的会话分区
 * 纯搬移，事务边界、异常类型与包装、日志语义、调用次序零变化。
 * <p><b>依赖方向（design D3）</b>：本服务只向下依赖
 * {@link com.linkroa.deepdataagent.runtime.application.validation.SessionMountValidator}
 * （2.1 校验规则本体）与 {@link TurnEventWriter}（2.3 会话级 seq 分配与静默广播底座），
 * <b>MUST NOT</b> 依赖 {@code execution.TurnExecutionService}——中断路径以「DB CAS 条件更新 +
 * 本地推流收敛」闭环：取消信号只投递给进程内会话上下文（{@code interruptCurrentRun}）与持久
 * {@code canceling} 痕迹，由在跑执行侧的两源取消谓词自行感知收流，本服务不触碰任何执行链入口。</p>
 * <p><b>中断 / 断连 / 删除 / 归档链路</b>（状态一律经内部相位 {@code turn_phase} 的 DB CAS，
 * 对外仅以终态收场事件集表达：线程先、会话后）：</p>
 * <pre>{@code
 *  user.interrupt → interruptSession
 *     markCanceling：CAS turn_phase → cancelling（无活跃执行 0 行 = 幂等空操作，不产生任何信号）
 *     提交成功后 sessionContext.interruptCurrentRun() → 标记中断 + turn.cancel() → SDK 流自然收流
 *     在跑执行侧感知在途取消（两源谓词：进程内中断标志 / cancelling 持久痕迹）
 *        → 收场落 [session.thread_status_idle, session.status_idle]（stop_reason.type=interrupted），
 *          相位回 idle；取消期间零状态事件（session.interrupted / session.status_canceling 已废止）
 *  断连（SSE onTimeout/onError/onCompletion）MUST NOT 取消活跃执行——仅释放连接句柄
 *  deleteSession → [事务] 逻辑删会话 + 事件流 → cancel + reject pending + 释放 SSE + 上下文移除 + 工件清理
 *  archiveSession → [事务] 仅置 archived_at（正交维度，守卫 archived_at IS NULL；0 行 = 并发归档 → 409）
 *                   → 不产生任何状态事件，随后 cancel 在跑执行 + 释放 SSE + 释放 turn 租约
 * }</pre>
 */
@Service
public class SessionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycleService.class);

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    /** metadata / 环境变量 JSON 文本反序列化目标类型（浅合并用）。 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    /** 运行装配服务：创建会话省略版本时解析激活版本、显式版本走轻量校验链。 */
    @Resource
    private RuntimeAgentAssemblyService runtimeAgentAssemblyService;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private TransactionTemplate transactionTemplate;
    /** 协调层：删除 / 归档不经执行终态唯一出口，显式幂等释放 turn 租约。 */
    @Resource
    private CoordinationLeaseService coordinationLeaseService;
    @Resource
    private ObjectMapper objectMapper;
    /** 会话挂载校验器（decompose-command-facade 2.1）：环境 / 文件 / 记忆库 / 保管库四类
     *  前置校验与挂载字节清单材料化；原直注的四个跨 BC 契约端口（FileApi / EnvironmentApi /
     *  VaultReferenceApi / MemoryStoreApi）随校验规则一并迁入该组件。 */
    @Resource
    private SessionMountValidator sessionMountValidator;
    /** turn 事件写入器（decompose-command-facade 2.3）：轮外落库点的会话级 seq 分配与静默广播。 */
    @Resource
    private TurnEventWriter turnEventWriter;
    /** 会话挂载物化编排：创建 / 追加校验通过后物化宿主副本（失败补偿）、移除删副本、删除清理命名空间。 */
    @Resource
    private SessionMountMaterializer sessionMountMaterializer;
    /** 会话产出投递端口（本 BC）：会话删除（终止）时清理其 scope=session 运行时产出文件。 */
    @Resource
    private ArtifactDeliveryPort artifactDeliveryPort;
    /** 线程仓储：会话创建落主线程、会话删除级联清理、idle 子线程归档（6.3）。 */
    @Resource
    private SessionThreadRepository sessionThreadRepository;
    /** Agent 快照契约：主线程嵌入 Agent 快照装配（裁剪规则权威在 agent BC，跨 BC 只读）。 */
    @Resource
    private AgentSnapshotApi agentSnapshotApi;
    /** Spring 原生事件发布器（工具性设施直注，迭代裁决 2026-09-13）：挂起态会话被删除 / 作废时
     *  在事务内发布 {@link TurnFinished}，由 {@code infrastructure.assembly.DeploymentRunOutcomeListener}
     *  于 AFTER_COMMIT 回写调度运行终态（回滚不发）。 */
    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    // ==================== 会话管理（写操作） ====================

    /**
     * 创建会话（created 初始态，懒构建 agent）。
     * <p>前置校验链（全有或全无，任一失败会话整体不落库）：agentId + 发布号必须绑定真实
     * Agent 版本台账（发布号非十进制 / Agent 不存在或已归档 → 404），不允许无全局回退；
     * 挂接运行环境时校验存在性 / 归属（不存在 / 越权 → 404）与执行平面
     * （{@code self_hosted} → 400，本期运行时不可执行）；携带挂载资源时逐项校验文件 /
     * 记忆库存在且归属当前用户（不存在 / 越权 → 404）；携带保管库挂载时同样逐项校验
     * 存在且归属当前用户（不存在 / 越权 → 404）；携带会话环境变量时校验其形态
     * （变量名 / 值类型 / 保留名与前缀 / 单值与总量体积 → 违规 400，见
     * {@link SessionEnvironmentVariablesValidator}）。</p>
     */
    public AgentSession createSession(CreateSessionCommand command) {
        // 省略版本时物化激活版本；显式版本时走轻量校验链（发布号格式 / Agent 存在未归档 / 版本 / profile）
        String versionNumber = resolveVersion(command);
        // 挂载前置校验（decompose-command-facade 2.1：规则本体迁至 SessionMountValidator，
        // 门面只保留调用编排）：环境存在性 / 归属 / 执行平面 → 文件判重 + 就绪 + 配额 →
        // 记忆库归属 → 保管库归属；全有或全无，任一失败会话整体不落库且零物化
        sessionMountValidator.validateEnvironment(command);
        sessionMountValidator.validateMountedFiles(command);
        sessionMountValidator.validateMountedMemoryStores(command);
        sessionMountValidator.validateVaults(command);
        // 会话环境变量形态校验（D11：与挂载校验同层、与更新路径共用同一判定；非法的键值 / 体积
        // 在此即整批 400，会话零落库零物化——校验位置先于物化与入库，与挂载校验同一时序）
        SessionEnvironmentVariablesValidator.validate(command.environmentVariables());
        CreateSessionCommand resolved = new CreateSessionCommand(
                command.userId(), command.agentId(), versionNumber, command.title(), command.metadata(),
                command.triggerType(), command.triggerId(), command.resources(),
                command.environmentId(), command.vaultIds(), command.environmentVariables());
        AgentSession session = AgentRuntimeConvert.INSTANCE.toSession(resolved);
        // 先物化、后入库（D5）：校验（判重 + 配额）已全部前置，此处只负责落盘；
        // 任一项物化失败以可读错误拒绝创建，本轮已物化副本由物化器内部补偿删除，会话不落库
        sessionMountMaterializer.materializeAll(session);
        try {
            return transactionTemplate.execute(status -> {
                AgentSession saved = sessionRepository.save(session);
                // 协调器主线程随行落库（parent_thread_id=null，线程查询 / 归档面的锚点；子线程由执行面委派产生）
                sessionThreadRepository.save(SessionThread.createMain(saved.sessionId(),
                        threadAgentJson(saved.agentId(), saved.agentVersion(), UserIds.parse(saved.userId()))));
                return saved;
            });
        } catch (RuntimeException ex) {
            // 入库失败补偿：物化成功但会话未落库 → 清理会话命名空间目录，不留孤儿副本（尽力而为，不覆盖主异常）
            sessionMountMaterializer.releaseAll(session);
            throw ex;
        }
    }

    /**
     * 解析会话绑定版本号：显式非空 → {@code assertResolvable} 校验后原样物化；
     * 省略（空白）→ 解析该 Agent 的激活版本（{@code active_version}）物化到会话。
     * owner 校验经 userId 透传装配端口（异步 / 调度链路无 ThreadLocal 认证上下文）。
     */
    private String resolveVersion(CreateSessionCommand command) {
        Long ownerId = UserIds.parse(command.userId());
        if (StringUtils.isNotBlank(command.agentVersion())) {
            runtimeAgentAssemblyService.assertResolvable(command.agentId(), command.agentVersion(), ownerId);
            return command.agentVersion();
        }
        return runtimeAgentAssemblyService.activeVersionNumber(command.agentId(), ownerId);
    }

    /**
     * 主线程 Agent 快照 JSON：经 Agent 快照契约解析 Session 嵌入形态后移除
     * {@code multiagent} 键（Thread 结构「额外去掉 multiagent」裁剪规则）；
     * 解析失败收敛为空对象（不阻断会话创建，快照留待台账修复后由执行面刷新）。
     */
    private String threadAgentJson(String agentId, String agentVersion, Long ownerId) {
        try {
            AgentSnapshotDTO dto = agentSnapshotApi.resolveSnapshot(
                    agentId, VersionNumbers.parseOrNull(agentVersion), ownerId);
            if (dto == null) {
                return "{}";
            }
            Map<String, Object> agent = new LinkedHashMap<>(dto.agent());
            agent.remove("multiagent");
            return objectMapper.writeValueAsString(agent);
        } catch (RuntimeException ex) {
            log.warn("主线程 Agent 快照装配失败（线程以空快照落库）: agentId={}, version={}",
                    agentId, agentVersion, ex);
            return "{}";
        }
    }

    /**
     * 删除会话（契约端点 {@code DELETE /sessions/{session_id}}）：会话与其历史事件流
     * 一并清理（DB 逻辑删除，列表 / 详情即刻不可见），仍活跃的执行先取消。
     * <pre>{@code
     *  deleteSession
     *    ├─ requireOwnedSession（不存在 / 越权 → 404）
     *    ├─ [事务] deleteBySessionId（agent_session + chat_event + session_thread 逻辑删，is_deleted=1）
     *    ├─ 拒绝待确认现场（HITL 等待期无流可收）并释放阻塞虚拟线程
     *    ├─ AgentSessionContext.cancel()             // 幂等中断在跑执行
     *    ├─ bindConnection(NoOpConnectionHandle)     // 释放全部 SSE emitter（旧句柄 close）
     *    ├─ sessionRegistry.remove                   // 移除内存会话上下文
     *    ├─ deleteSessionArtifacts（尽力而为）        // 清理 scope=session 运行时产出
     *    └─ releaseAll（尽力而为）                   // 递归清理宿主会话命名空间目录（含 mounts）
     * }</pre>
     */
    public void deleteSession(String sessionId) {
        AgentSession session = requireOwnedSession(sessionId);
        // 级联删除事务生效前，向全部在线 SSE 订阅者实时推送 session.deleted（随后关闭连接）——
        // 该事件不落库（会话历史即将删除），仅作实时终局通知
        String mainThreadId = resolveMainThreadId(sessionId);
        sessionRegistry.get(sessionId).ifPresent(ctx -> turnEventWriter.pushQuietly(ctx,
                ChatEvent.create(sessionId, ChatEventType.SESSION_DELETED,
                        ChatEventFactory.INSTANCE.sessionDeleted().payloadJson(),
                        turnEventWriter.nextSequence(sessionId), null, mainThreadId)));
        transactionTemplate.executeWithoutResult(status -> {
            chatEventRepository.deleteBySessionId(sessionId);
            sessionThreadRepository.deleteBySessionId(sessionId);
            sessionRepository.deleteBySessionId(sessionId);
            // 调度运行回写（事件化）：等待确认中的会话被删除，挂起轮次随之作废（不经终态唯一出口），
            // 事务内发布终局事件、提交后按中断收口回写；其余状态：idle/terminated 已由此前出口收口，
            // running（轮内相位 running）经下方 cancel 走中断终态出口回写
            if (session.turnPhase() == TurnPhase.AWAITING_CONFIRMATION) {
                applicationEventPublisher.publishEvent(new TurnFinished(sessionId, session.triggerType(),
                        DeploymentRunLifecycleApi.OUTCOME_TERMINATED));
            }
        });
        // 幂等取消在跑执行并标记中断（事件流已删除，本轮经中断路径静默收尾）
        sessionRegistry.get(sessionId).ifPresent(ctx -> ctx.cancel());
        // 关闭全部订阅者：解绑连接触发旧句柄 close 完成全部 emitter 关闭（替代 eventBroadcaster.complete）
        sessionRegistry.get(sessionId).ifPresent(ctx -> ctx.bindConnection(NoOpConnectionHandle.INSTANCE));
        // 协调层清理：删除不经过终态唯一出口，显式释放 turn 租约（幂等）
        coordinationLeaseService.releaseTurnLease(sessionId);
        // 内存上下文移除（须在 cancel / bindConnection 之后，否则取不到句柄）
        sessionRegistry.remove(sessionId);
        // 产出物生命周期清理：会话删除时其 scope=session 的运行时产出文件随之删除（尽力而为，不阻断删除）
        try {
            artifactDeliveryPort.deleteSessionArtifacts(sessionId);
        } catch (RuntimeException ex) {
            log.warn("会话产出文件清理失败（会话删除不受影响，需人工关注）: sessionId={}",
                    sessionId, ex);
        }
        // 工作区挂载清理：递归删除宿主会话命名空间目录 <workspace-root>/<agentId>/<sessionId>
        // （尽力而为、不阻断删除；MUST NOT 触及 Agent 级工作区根内容，见 SessionWorkspacePort.cleanup）
        sessionMountMaterializer.releaseAll(session);
    }

    /**
     * 中断会话（{@code user.interrupt} 入站语义）：中断在跑 turn，状态经 {@code canceling}
     * 回 idle，不产生 cancelled 终态，中断以 {@code session.status_canceling}（取消请求）+
     * {@code session.interrupted} + {@code session.status_idle}（收敛回 idle）事件表达；
     * 无活跃执行时为空操作（幂等，状态不变，不产生任何取消信号）。
     * <p>HITL 等待期间（waiting_confirmation）的中断：无流可中断，durable 等待作废
     * 直接回 idle（waiting_confirmation → idle 为合法直达迁移，不经 canceling；
     * 账本未应答明细随作废终局留痕收敛）。</p>
     * <p><b>信号投递顺序</b>：取消判定仅两源——进程内中断标志 + {@code canceling} 持久痕迹。
     * 本方法 MUST 先完成 durable 作废 / {@code processing → canceling} 持久条件更新的事务提交，
     * 提交成功后才触发同实例进程内中断（{@code interruptCurrentRun}）使执行流提前收流；
     * MUST NOT 在持久取消痕迹提交之前投递任何取消信号（0 行未命中路径直接幂等返回，
     * 不产生任何信号与协调层写入）。</p>
     */
    public void interruptSession(String sessionId) {
        AgentSession session = requireOwnedSession(sessionId);
        // HITL 等待期间（durable）：无流可中断，CAS 作废等待回 idle（不经挂起现场、无需释放租约）；
        // 等待确认被中断作废即本轮终止，无后续收敛出口，作废事务内按中断收口发布终局事件
        if (interruptWaitingConfirmationToIdle(session)) {
            return;
        }
        // 活跃执行取消：CAS 相位置 cancelling，提交成功后再触发 SDK 收流
        // （无活跃执行 / 已 canceling：CAS 0 行，取消幂等空操作——不产生任何取消信号，
        //   后续轮次的在途取消谓词不受本次影响）
        if (markCanceling(sessionId) == 0) {
            return;
        }
        sessionRegistry.get(sessionId).ifPresent(AgentSessionContext::interruptCurrentRun);
    }

    /**
     * 取消会话（契约端点 {@code POST /sessions/{id}/cancel}）：返回是否存在被取消的活跃 turn。
     * <p>契约口径（sessions spec）：存在活跃 turn（内部相位 running/awaiting_confirmation/cancelling）
     * 时执行取消并返回 {@code true}（控制器映射 HTTP 202）；Session 处于 idle 或 terminated
     * （均无活跃 turn）时为空操作返回 {@code false}（HTTP 200）；会话不存在或已归档抛 404
     * （terminated 会话仍存在，不返回 404）。取消过程零状态事件（取消请求以固定回执对外应答），
     * 收束回 idle 由执行侧中断终态出口闭环。</p>
     *
     * @param sessionId 会话 ID
     * @return true=存在活跃 turn 且已投递取消；false=idle / terminated 空操作
     */
    public boolean cancelSession(String sessionId) {
        AgentSession session = requireOwnedSession(sessionId);
        if (session.archived()) {
            // 归档会话不可取消（归档是正交维度，取消端点按会话不可见处理）
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话已归档");
        }
        // HITL 等待期：无流可中断，CAS 作废等待回 idle（作废即本轮终止）
        if (interruptWaitingConfirmationToIdle(session)) {
            return true;
        }
        // 活跃执行取消：CAS 相位置 cancelling；0 行表示 idle / terminated（无活跃执行），幂等空操作
        if (markCanceling(sessionId) == 0) {
            return false;
        }
        sessionRegistry.get(sessionId).ifPresent(AgentSessionContext::interruptCurrentRun);
        return true;
    }

    /**
     * 活跃执行取消置位：CAS 相位 {@code running/awaiting_confirmation → cancelling}
     * （对外 status 恒为 running，不改写）。
     * <p>取消链中段不落任何状态事件（对外状态无变化，取消请求以固定回执对外应答）；
     * {@code cancelling → idle} 由执行侧收敛时的中断终态出口（{@code FINISH_TURN}，
     * 相位前置为空集）闭环，或被取消方在途的 maxIters 终态回退链闭环。</p>
     *
     * @return CAS 受影响行数（1=置 cancelling 成功；0=无活跃执行，取消空操作）
     */
    private int markCanceling(String sessionId) {
        Integer rows = transactionTemplate.execute(status ->
                sessionRepository.transition(sessionId, Transition.PHASE_CANCEL));
        return rows == null ? 0 : rows;
    }

    /**
     * HITL 等待期中断（durable）：CAS {@code running/awaiting_confirmation → running/idle} +
     * {@code session.interrupted} / {@code session.status_idle} 终局留痕事件同事务落库并广播。
     * <p>相位门禁由 {@link Transition#ABANDON_WAITING_CONFIRMATION} 单点收敛——仅
     * {@code awaiting_confirmation} 命中；{@code running} 相位的活跃执行 0 行返回，交由调用方
     * 走 {@link #markCanceling} 取消链收流（MUST NOT 静默绕过取消信号）。</p>
     *
     * @return true=本次调用完成作废（此前处于等待确认态）；false=会话不在等待态，未做任何变更
     */
    private boolean interruptWaitingConfirmationToIdle(AgentSession session) {
        String sessionId = session.sessionId();
        List<AssembledEvent> events = List.of(
                ChatEventFactory.INSTANCE.threadStatus(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED),
                ChatEventFactory.INSTANCE.sessionStatus(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED));
        List<ChatEvent> saved = new ArrayList<>();
        // 轮外落库点：现查主线程归属一次（本批事件共用；缺失降级 null，不阻断落库）
        String mainThreadId = resolveMainThreadId(sessionId);
        Boolean invalidated = transactionTemplate.execute(status -> {
            if (sessionRepository.transition(sessionId, Transition.ABANDON_WAITING_CONFIRMATION) > 0) {
                for (AssembledEvent event : events) {
                    ChatEvent chatEvent = ChatEvent.create(sessionId, event.type(),
                            event.payloadJson(), turnEventWriter.nextSequence(sessionId), null, mainThreadId);
                    chatEventRepository.save(chatEvent);
                    saved.add(chatEvent);
                }
                // 调度运行回写（事件化）：作废事务内发布终局事件，提交后按中断收口回写
                applicationEventPublisher.publishEvent(new TurnFinished(sessionId, session.triggerType(),
                        DeploymentRunLifecycleApi.OUTCOME_TERMINATED));
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
        boolean result = Boolean.TRUE.equals(invalidated);
        if (result) {
            sessionRegistry.get(sessionId)
                    .ifPresent(ctx -> saved.forEach(event -> turnEventWriter.pushQuietly(ctx, event)));
        }
        return result;
    }

    /**
     * 归档会话：仅写 {@code archived_at} 时间戳（归档是正交维度，MUST NOT 改写 status / 相位，
     * 也 MUST NOT 产生归档状态事件），中断在跑执行并释放订阅。
     * <pre>{@code
     *  archiveSession
     *    ├─ requireOwnedSession（不存在 / 越权 → 404；已归档 → 幂等忽略）
     *    ├─ [事务] archive(sessionId)（仅置 archived_at，守卫 archived_at IS NULL；0 行 = 并发归档竞态 → 409）
     *    └─ cancel 在跑执行 + bindConnection(NoOpConnectionHandle) + 释放 turn 租约
     * }</pre>
     */
    public void archiveSession(String sessionId) {
        AgentSession session = requireOwnedSession(sessionId);
        if (session.archived()) {
            return;
        }
        Integer rows = transactionTemplate.execute(status -> sessionRepository.archive(sessionId));
        if (rows == null || rows == 0) {
            // 0 行：读取后至 CAS 提交前已被并发请求归档（守卫 archived_at IS NULL 未命中）→ 409 冲突
            throw new ResourceConflictException("会话归档竞态，请重试: " + sessionId);
        }
        // HITL 等待为 durable 驻留态：归档即会话级终局，账本未应答明细随终局合法收敛（无现场需释放）
        sessionRegistry.get(sessionId).ifPresent(ctx -> ctx.cancel());
        sessionRegistry.get(sessionId).ifPresent(ctx -> ctx.bindConnection(NoOpConnectionHandle.INSTANCE));
        // 协调层清理：归档不经过终态唯一出口，显式释放 turn 租约（幂等）
        coordinationLeaseService.releaseTurnLease(sessionId);
    }

    /**
     * 更新会话资料（契约端点 {@code POST /sessions/{session_id}}）：
     * {@code title} {@code titlePresent=true} 时生效——非空覆盖、{@code null} 清空；
     * {@code metadata} 为 <b>patch</b>（值为 {@code null} 的键被删除，顶层缺省为 no-op）；
     * {@code environment_variables} 提供即整体替换。更新成功同事务落库并广播
     * {@code session.updated} 事件（按请求选择性携带 title/metadata，永不含环境变量）。
     * <p>门禁：会话不存在 / 越权 → 404；已归档 → 404（归档即会话不可见）；已终止 → 409
     * （状态冲突，不可再更新）。</p>
     * <p>环境变量形态校验（D11）在方法体首行、于会话归属校验之前执行：载荷类 400 先于
     * 「会话不存在」404（与追加挂载批次校验同一优先序），且校验通过前零写入——非法替换
     * 请求下既有环境变量保持原值。创建路径与本路径共用同一判定
     * （{@link SessionEnvironmentVariablesValidator}），避免「只在创建校验、更新即成绕过口」。</p>
     *
     * @param command 更新命令（sessionId 必填，其余字段 null=不改）
     * @return 更新后的会话
     */
    public AgentSession updateSession(UpdateSessionCommand command) {
        SessionEnvironmentVariablesValidator.validate(command.environmentVariablesJson());
        AgentSession session = requireOwnedSession(command.sessionId());
        if (session.archived()) {
            // 归档即会话对写操作面不可见（对齐 sessions 规格：归档后普通属性更新 404）
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话已归档");
        }
        if (session.status() == AgentSessionStatus.TERMINATED) {
            // 终止为持久终态，不可再更新普通属性（状态冲突语义）
            throw new ResourceConflictException("会话已终止，不可更新: " + command.sessionId());
        }
        String mergedMetadata = patchMetadata(session.metadata(), command.metadataJson());
        // session.updated 选择性装配：仅携带本请求实际改动的字段（title / metadata），永不带环境变量
        AssembledEvent updatedEvent = ChatEventFactory.INSTANCE.sessionUpdated(
                selectiveUpdatedFields(command, mergedMetadata));
        List<ChatEvent> saved = new ArrayList<>();
        // 轮外落库点：现查主线程归属一次（缺失降级 null，不阻断落库）
        String mainThreadId = resolveMainThreadId(command.sessionId());
        transactionTemplate.executeWithoutResult(status -> {
            sessionRepository.updateProfile(command.sessionId(), command.title(), command.titlePresent(),
                    mergedMetadata, command.environmentVariablesJson());
            ChatEvent event = ChatEvent.create(command.sessionId(), updatedEvent.type(),
                    updatedEvent.payloadJson(), turnEventWriter.nextSequence(command.sessionId()), null, mainThreadId);
            chatEventRepository.save(event);
            saved.add(event);
        });
        sessionRegistry.get(command.sessionId())
                .ifPresent(ctx -> saved.forEach(event -> turnEventWriter.pushQuietly(ctx, event)));
        return requireSession(command.sessionId());
    }

    /**
     * {@code session.updated} 选择性字段：仅纳入本请求实际提交的可见字段。
     * <p>{@code title} 键仅在请求显式提交标题时携带（值可为 {@code null} = 已清空）；
     * {@code metadata} 键仅在请求提交 metadata patch 时携带合并后的完整对象；
     * 环境变量恒不携带（密态最小暴露，design D12）。</p>
     */
    private Map<String, Object> selectiveUpdatedFields(UpdateSessionCommand command, String mergedMetadata) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (command.titlePresent()) {
            fields.put("title", command.title());
        }
        if (mergedMetadata != null) {
            fields.put("metadata", parseJsonObject(mergedMetadata));
        }
        return fields;
    }

    /**
     * 元数据 patch 合并：既有 JSON 对象叠加增量条目——值为 {@code null} 的键<b>删除</b>，
     * 其余键覆盖；增量为 null / 空白（字段缺省）时为 no-op，返回 null（表示本列不更新，
     * 与 {@code updateProfile} 的 null 语义一致）。
     */
    private String patchMetadata(String existingJson, String incomingJson) {
        if (incomingJson == null || incomingJson.isBlank()) {
            return null;
        }
        Map<String, Object> merged = parseJsonObject(existingJson);
        parseJsonObject(incomingJson).forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        return PayloadJson.jsonOf(objectMapper, merged);
    }

    /** JSON 文本 → 可变键值对象（空白 / 非法收敛为空对象，既有元数据不因脏历史阻断更新）。 */
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException ex) {
            return new LinkedHashMap<>();
        }
    }

    // ==================== 挂载资源管理（追加 / 轮换令牌 / 移除） ====================

    /**
     * 创建后追加挂载文件资源（契约端点 {@code POST /sessions/{id}/resources}，仅 file 类型）。
     * <p>校验全前置、整批全有或全无（任一非法条目即抛错，会话资源零变更）：</p>
     * <ul>
     *   <li>会话不存在 / 越权 → 404；已归档 / 已终止 → 409；</li>
     *   <li>空批次（应用入口校验器首行拒绝）、非 file 类型资源 → 400；</li>
     *   <li>同一文件重复挂载或挂载路径冲突（批内重复或与现有挂载重复）→ 409；</li>
     *   <li>文件不存在 / 越权 / 未就绪 → 409（就绪门禁收敛为冲突语义，不泄露存在性）；</li>
     *   <li>追加后挂载总量（既有 + 新增，既有文件元数据缺失计 0）超过 500MB → 400。</li>
     * </ul>
     * <p>校验通过后单事务以合并后的全量资源覆盖 resources 列；返回追加项
     * （含自动生成的 {@code sesr_} 资源 ID 与缺省挂载路径归一结果，挂载时刻即 {@code updated_at}）。</p>
     *
     * @param sessionId 会话业务 ID
     * @param resources 追加的挂载资源项（仅 file 类型）
     * @return 已追加的挂载资源列表（保序）
     */
    public List<SessionResource> appendResources(String sessionId, List<SessionResource> resources) {
        // 批次非空校验首行前置（D2 落点）：保持「载荷类 400 先于会话不存在」的对外优先序
        InboundEventValidator.validateAppendResourceBatch(resources);
        AgentSession session = requireOwnedSession(sessionId);
        requireMutableSession(session, "追加挂载资源");
        Long ownerId = UserIds.parse(session.userId());
        if (ownerId == null) {
            throw new IllegalArgumentException("会话归属用户非法，不可追加挂载资源");
        }
        // 类型门禁（领域规则，先于材料化执行，避免为必然非法的批次做无谓就绪查询）：
        // 本期追加路径仅允许 file 资源（github_repository / memory_store 创建后不可追加）
        SessionMountPolicy.requireFileOnly(resources);
        // 字节清单材料化（既有 + 新增一次取齐）后交聚合判定：fileId / mount_path 判重 → 就绪结论 →
        // 总量配额（规则权威 SessionMountPolicy，与创建路径同一实现；判定顺序即状态码优先级）
        Map<String, Long> sizeBytesByFileId = sessionMountValidator.mountedSizeBytes(session.resources(), ownerId);
        sizeBytesByFileId.putAll(sessionMountValidator.mountedSizeBytes(resources, ownerId));
        AgentSession appended = mutateMounts(() -> session.appendFiles(resources, sizeBytesByFileId));
        // 先物化新增项、后入库（D5）：任一项物化失败整批拒绝（本轮已物化项由物化器内部补偿），
        // 既有挂载副本不受影响
        sessionMountMaterializer.materialize(session, resources);
        try {
            transactionTemplate.executeWithoutResult(status ->
                    sessionRepository.updateResources(sessionId, appended.resources()));
        } catch (RuntimeException ex) {
            // 入库失败补偿：仅删除本轮新物化的副本（尽力而为，不覆盖主异常）
            resources.forEach(resource -> sessionMountMaterializer.release(session, resource));
            throw ex;
        }
        return resources;
    }

    /**
     * 轮换 GitHub 仓库挂载令牌（资源管理契约 {@code update}）。
     * <p>门禁与派生均在聚合（{@code AgentSession#rotateResourceToken}）：空令牌 400 →
     * 资源未命中 404 → 类型门禁 400，判定顺序即状态码优先级。
     * 新旧令牌均不写日志、不进响应（响应装配层剔除令牌字段）。</p>
     *
     * @param sessionId  会话业务 ID
     * @param resourceId 挂载资源业务 ID（{@code sesr_}）
     * @param newToken   新访问令牌（非空）
     * @return 轮换后的资源值对象（含派生字段，令牌字段调用方不得回显）
     */
    public SessionResource rotateResourceToken(String sessionId, String resourceId, String newToken) {
        AgentSession session = requireOwnedSession(sessionId);
        requireMutableSession(session, "轮换挂载资源令牌");
        AgentSession rotated = mutateMounts(() -> session.rotateResourceToken(resourceId, newToken));
        // 轮换后的资源条目（聚合已保证其存在）：仅用于返回值装配，令牌字段由响应层剔除
        SessionResource target = requireMountedResource(rotated, resourceId);
        transactionTemplate.executeWithoutResult(status ->
                sessionRepository.updateResources(sessionId, rotated.resources()));
        return target;
    }

    /**
     * 移除挂载资源（资源管理契约 {@code delete}）：仅 {@code file} 类型可移除（聚合门禁，
     * 非 file → 409），移除后文件脱离挂载；{@code github_repository / memory_store}
     * 创建后不可摘除。
     *
     * @param sessionId  会话业务 ID
     * @param resourceId 待移除的挂载资源业务 ID（{@code sesr_}）
     */
    public void removeResource(String sessionId, String resourceId) {
        AgentSession session = requireOwnedSession(sessionId);
        requireMutableSession(session, "移除挂载资源");
        AgentSession remaining = mutateMounts(() -> session.removeResource(resourceId));
        // 移除前条目（聚合已保证其存在且为 file 类型）：宿主副本释放锚点
        SessionResource target = requireMountedResource(session, resourceId);
        transactionTemplate.executeWithoutResult(status ->
                sessionRepository.updateResources(sessionId, remaining.resources()));
        // 移除即释放宿主副本（尽力而为：失败仅告警，不影响移除结果；下一轮对账与清单同步收缩）
        sessionMountMaterializer.release(session, target);
    }

    /**
     * 执行聚合挂载变更并把领域冲突映射为写操作面协议异常（追加 / 轮换 / 移除共用）。
     *
     * @param mutation 聚合动作（纯内存派生，不含 IO）
     * @return 派生后的会话
     * @throws RuntimeException 按冲突种类映射后的协议异常（409 / 404 / 透传 400）
     */
    private AgentSession mutateMounts(Supplier<AgentSession> mutation) {
        try {
            return mutation.get();
        } catch (MountViolationException ex) {
            throw toMountRejection(ex);
        }
    }

    /**
     * 挂载规则冲突 → 写操作面协议异常映射：判重 / 不就绪 / 不可移除升级为 409
     * {@code invalid_request_error}（api-conventions「会话忙与会话资源冲突」特例：
     * 契约明文规定的会话资源冲突不落通用 {@code conflict_error}；消息文本单点定义于领域），
     * 资源未命中 404；配额超限与类型 / 令牌门禁本即 400（异常继承
     * {@link IllegalArgumentException}）直接透传。
     */
    private RuntimeException toMountRejection(MountViolationException ex) {
        return switch (ex.violation()) {
            case DUPLICATE_FILE, DUPLICATE_MOUNT_PATH, FILE_NOT_MOUNTABLE, REMOVE_TYPE_UNSUPPORTED ->
                    new SessionBusyException(ex.getMessage());
            case MOUNTED_RESOURCE_NOT_FOUND -> new ResourceNotFoundException(ex.getMessage());
            default -> ex;
        };
    }

    /**
     * 资源写操作门禁：已归档 / 已终止会话不可变更挂载（聚合谓词 {@code mutable()} 单点判定）。
     * <p>拒绝落 409 {@code invalid_request_error}（{@link SessionBusyException}）——会话资源冲突
     * 属 api-conventions「会话忙冲突的错误类型」特例，契约明文路径不落通用 {@code conflict_error}。</p>
     */
    private void requireMutableSession(AgentSession session, String action) {
        if (!session.mutable()) {
            throw new SessionBusyException("会话已归档或已终止，不可" + action + ": " + session.sessionId());
        }
    }

    /** 按资源 ID 定位已挂载资源项（未命中 → 404，不泄露其他会话的资源存在性）。 */
    private SessionResource requireMountedResource(AgentSession session, String resourceId) {
        return session.findResource(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("挂载资源不存在: " + resourceId));
    }

    // ==================== 私有工具方法（随会话簇自持副本，与门面既有实现逐字同形） ====================

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
     * 随 {@code execution.ExecutionContext#sessionThreadId()} 传递。</p>
     */
    private String resolveMainThreadId(String sessionId) {
        return sessionThreadRepository.findMain(sessionId)
                .map(SessionThread::threadId)
                .orElse(null);
    }
}
