package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.api.AgentSnapshotApi;
import com.linkroa.deepdataagent.agent.api.EnvironmentApi;
import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.runtime.application.port.AgentRunExecutor;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalScheduler;
import com.linkroa.deepdataagent.runtime.application.service.assembly.RuntimeAgentAssemblyService;
import com.linkroa.deepdataagent.runtime.application.service.assembly.SessionMountMaterializer;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnEventWriter;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnExecutionService;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnFinalizer;
import com.linkroa.deepdataagent.runtime.application.service.event.InboundEventService;
import com.linkroa.deepdataagent.runtime.application.service.hitl.HumanConfirmationService;
import com.linkroa.deepdataagent.runtime.application.service.hitl.PendingBatchResolver;
import com.linkroa.deepdataagent.runtime.application.service.session.SessionLifecycleService;
import com.linkroa.deepdataagent.runtime.application.validation.InboundEventValidator;
import com.linkroa.deepdataagent.runtime.application.validation.SessionMountValidator;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.execution.InMemorySessionRegistry;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.api.VaultReferenceApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * runtime 应用服务簇的共享测试夹具基座（decompose-command-facade 4.1，design D4「共享 fixture
 * 基座承载依赖 mock 装配」）。
 * <p>把门面拆分后各入口服务（{@link HumanConfirmationService} / 4.2 {@code InboundEventService} /
 * 4.3 {@code SessionLifecycleService}）与其协作件（{@link TurnExecutionService} /
 * {@link TurnFinalizer} / {@link TurnEventWriter} / {@link PendingBatchResolver} /
 * {@link SessionMountValidator}）以<b>真实实例 + 同一组替身端口</b>装配成一张协作网，令「哪些事件
 * 按何序落库 / 推送」「领取事务前后次序」等跨服务红线在子包镜像测试类里仍可端到端固化断言。</p>
 * <p>夹具口径与壳测试一致：真实 {@link InMemorySessionRegistry} + 真实
 * {@code Schedulers.immediate()}（令 {@code doOnNext} 在测试线程同步跑完）+ 同步直跑虚拟执行器，
 * 仓储 / 装配 / 租约 / 事务 / 跨 BC 契约为替身。</p>
 */
@ExtendWith(MockitoExtension.class)
public abstract class AgentRuntimeServiceTestSupport {

    @Mock protected AgentSessionRepository sessionRepository;
    @Mock protected ChatEventRepository chatEventRepository;
    @Mock protected AgentFactoryPort agentFactory;
    @Mock protected AgentRunExecutor agentRunExecutor;
    @Mock protected TransactionTemplate transactionTemplate;
    @Mock protected RuntimeAgentAssemblyService runtimeAgentAssemblyService;
    /** 协调层租约 mock：turn 租约获取 / 续约 / 释放（获取默认放行）。 */
    @Mock protected CoordinationLeaseService coordinationLeaseService;
    /** 广播端口 mock：验证 {@code session.status_*} / 源码事件经连接层下发。 */
    @Mock protected ConnectionHandle connectionHandle;
    /** 文件服务契约 mock：创建 / 追加挂载校验（无挂载用例不触碰）。 */
    @Mock protected FileApi fileApi;
    /** 环境查询契约 mock：创建会话环境存在性 / 执行平面校验（未挂环境用例不触碰）。 */
    @Mock protected EnvironmentApi environmentApi;
    /** 保管库引用契约 mock：创建会话保管库挂载校验（未挂载用例不触碰）。 */
    @Mock protected VaultReferenceApi vaultReferenceApi;
    /** 记忆库查询契约 mock：创建会话挂载记忆库校验（未挂载用例不触碰）。 */
    @Mock protected MemoryStoreApi memoryStoreApi;
    /** 会话挂载物化编排 mock：创建 / 追加物化与补偿、移除与删除清理（默认空操作）。 */
    @Mock protected SessionMountMaterializer sessionMountMaterializer;
    /** 会话产出投递端口 mock：会话删除（终止）时清理 scope=session 产出文件。 */
    @Mock protected ArtifactDeliveryPort artifactDeliveryPort;
    /** 线程仓储 mock：创建落主线程 / 删除级联 / 轮外落库点现查归属。 */
    @Mock protected SessionThreadRepository sessionThreadRepository;
    /** Agent 快照契约 mock：主线程快照装配（默认返回 null 收敛空快照）。 */
    @Mock protected AgentSnapshotApi agentSnapshotApi;
    /** Spring 事件发布器 mock：验证终局出口在终态事务内直发 TurnFinished。 */
    @Mock protected ApplicationEventPublisher applicationEventPublisher;
    /** turn 租约定时续约调度端口 mock：捕获续约任务与周期，验证启停 / fail-closed 行为。 */
    @Mock protected LeaseRenewalScheduler leaseRenewalScheduler;
    /** 续约停止句柄 mock：验证轮次全部出口统一 cancel（无虚续约）。 */
    @Mock protected LeaseRenewalHandle leaseRenewalHandle;

    /** 真实会话级聚合注册表：串行守卫 / 断连中断语义按真实实现执行（与主链路一致）。 */
    protected final InMemorySessionRegistry sessionRegistry = new InMemorySessionRegistry();

    /** 真实同步调度器：publishOn 后在调用线程同步执行 doOnNext（测试断言确定性强）。 */
    protected final Scheduler blockingScheduler = Schedulers.immediate();

    /** 默认同步执行器：消息发送同步直跑，断言完整链路；异步入口用例单独替换为 mock。 */
    protected Executor virtualExecutor = task -> task.run();

    protected TurnEventWriter turnEventWriter;
    protected TurnFinalizer turnFinalizer;
    protected TurnExecutionService turnExecutionService;
    protected PendingBatchResolver pendingBatchResolver;
    protected HumanConfirmationService humanConfirmationService;
    protected SessionLifecycleService sessionLifecycleService;
    protected InboundEventService inboundEventService;
    protected SessionMountValidator sessionMountValidator;
    protected InboundEventValidator inboundEventValidator;
    /** 协作网共用的同步落库端口替身（四服务共用同一实例，故障注入点单一）。 */
    protected ChatEventPersister persister;

    @BeforeEach
    protected void setUpRuntimeServiceCluster() {
        // 受保护用例走 AuthContext owner 隔离（数字 user_id 字符串化比对）
        AuthContext.setUserId(1L);
        assembleRuntimeServices();
    }

    @AfterEach
    protected void clearRuntimeServiceAuth() {
        AuthContext.clear();
    }

    // ==================== 装配 ====================

    /**
     * 装配 runtime 应用服务协作网（@Resource 字段全部注入测试替身 / 真实协作件）。
     * <p>落库端口三处同步（执行侧启动时清除落库失败标记 + Writer 入队 + 收口器排空 / 校验落库失败标记），
     * 与 {@link #wirePersister} 一致的故障注入点保持「注入即全链路生效」语义。</p>
     */
    protected void assembleRuntimeServices() {
        persister = synchronousPersister();
        turnEventWriter = newTurnEventWriter(persister);
        turnFinalizer = newTurnFinalizer(turnEventWriter, persister);
        turnExecutionService = newTurnExecutionService(turnEventWriter, turnFinalizer, persister);
        pendingBatchResolver = newPendingBatchResolver();
        humanConfirmationService = newHumanConfirmationService();
        sessionMountValidator = newSessionMountValidator();
        inboundEventValidator = newInboundEventValidator();
        sessionLifecycleService = newSessionLifecycleService();
        // 入站服务装配置于协作网末端：其驱动目标（执行链 / HITL / 会话生命周期）须先就位
        inboundEventService = newInboundEventService();
    }

    /** 反射装配真实事件写入器（2.3：先推后入队 / 吞推送异常 / seq 分配的唯一底座）。 */
    protected TurnEventWriter newTurnEventWriter(ChatEventPersister persister) {
        TurnEventWriter writer = new TurnEventWriter();
        ReflectionTestUtils.setField(writer, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(writer, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(writer, "chatEventPersister", persister);
        return writer;
    }

    /** 反射装配真实轮次收口器（3.1：终局四出口 + HITL 挂起收尾 + 严格排空协议事务侧）。 */
    protected TurnFinalizer newTurnFinalizer(TurnEventWriter writer, ChatEventPersister persister) {
        TurnFinalizer finalizer = new TurnFinalizer();
        ReflectionTestUtils.setField(finalizer, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(finalizer, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(finalizer, "chatEventPersister", persister);
        ReflectionTestUtils.setField(finalizer, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(finalizer, "turnEventWriter", writer);
        ReflectionTestUtils.setField(finalizer, "coordinationLeaseService", coordinationLeaseService);
        ReflectionTestUtils.setField(finalizer, "applicationEventPublisher", applicationEventPublisher);
        return finalizer;
    }

    /** 反射装配真实执行链服务（3.2：启动抢占 → 启动事务 → 骨架装配 → 信号分发 → 收流 / 收轮）。 */
    protected TurnExecutionService newTurnExecutionService(TurnEventWriter writer, TurnFinalizer finalizer,
                                                           ChatEventPersister persister) {
        TurnExecutionService exec = new TurnExecutionService();
        ReflectionTestUtils.setField(exec, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(exec, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(exec, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(exec, "agentRunExecutor", agentRunExecutor);
        ReflectionTestUtils.setField(exec, "runtimeAgentAssemblyService", runtimeAgentAssemblyService);
        ReflectionTestUtils.setField(exec, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(exec, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(exec, "chatEventPersister", persister);
        ReflectionTestUtils.setField(exec, "coordinationLeaseService", coordinationLeaseService);
        ReflectionTestUtils.setField(exec, "virtualExecutor", virtualExecutor);
        ReflectionTestUtils.setField(exec, "blockingScheduler", blockingScheduler);
        ReflectionTestUtils.setField(exec, "leaseRenewalScheduler", leaseRenewalScheduler);
        ReflectionTestUtils.setField(exec, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(exec, "sessionThreadRepository", sessionThreadRepository);
        ReflectionTestUtils.setField(exec, "turnEventWriter", writer);
        ReflectionTestUtils.setField(exec, "turnFinalizer", finalizer);
        return exec;
    }

    /** 反射装配真实批次解析器（2.2：事件表锚点定位与整批明细重建）。 */
    protected PendingBatchResolver newPendingBatchResolver() {
        PendingBatchResolver resolver = new PendingBatchResolver();
        ReflectionTestUtils.setField(resolver, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(resolver, "objectMapper", new ObjectMapper());
        return resolver;
    }

    /** 反射装配真实 HITL 入口服务（4.1：领取编排 + durable 续跑轮）。 */
    protected HumanConfirmationService newHumanConfirmationService() {
        HumanConfirmationService hitl = new HumanConfirmationService();
        ReflectionTestUtils.setField(hitl, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(hitl, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(hitl, "agentRunExecutor", agentRunExecutor);
        ReflectionTestUtils.setField(hitl, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(hitl, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(hitl, "chatEventPersister", persister);
        ReflectionTestUtils.setField(hitl, "sessionThreadRepository", sessionThreadRepository);
        ReflectionTestUtils.setField(hitl, "pendingBatchResolver", pendingBatchResolver);
        ReflectionTestUtils.setField(hitl, "turnEventWriter", turnEventWriter);
        ReflectionTestUtils.setField(hitl, "turnExecutionService", turnExecutionService);
        ReflectionTestUtils.setField(hitl, "virtualExecutor", virtualExecutor);
        return hitl;
    }

    /** 反射装配真实挂载校验器（2.1：四类前置校验规则本体）。 */
    protected SessionMountValidator newSessionMountValidator() {
        SessionMountValidator validator = new SessionMountValidator();
        ReflectionTestUtils.setField(validator, "fileApi", fileApi);
        ReflectionTestUtils.setField(validator, "environmentApi", environmentApi);
        ReflectionTestUtils.setField(validator, "vaultReferenceApi", vaultReferenceApi);
        ReflectionTestUtils.setField(validator, "memoryStoreApi", memoryStoreApi);
        return validator;
    }

    /** 反射装配真实入站校验器（入站批次结构校验规则本体：载荷类 400 先于会话不存在）。 */
    protected InboundEventValidator newInboundEventValidator() {
        InboundEventValidator validator = new InboundEventValidator();
        ReflectionTestUtils.setField(validator, "fileApi", fileApi);
        return validator;
    }

    /**
     * 反射装配真实会话生命周期入口服务（4.3：创建 / 更新 / 归档 / 删除 / 中断 + 挂载资源管理）。
     * <p>中断路径按 design D3 不注入执行链服务（DB CAS + 本地推流收敛），装配面与主源
     * {@code SessionLifecycleService} 的 14 个 {@code @Resource} 一一对应。</p>
     */
    protected SessionLifecycleService newSessionLifecycleService() {
        SessionLifecycleService svc = new SessionLifecycleService();
        ReflectionTestUtils.setField(svc, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(svc, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(svc, "runtimeAgentAssemblyService", runtimeAgentAssemblyService);
        ReflectionTestUtils.setField(svc, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(svc, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(svc, "coordinationLeaseService", coordinationLeaseService);
        ReflectionTestUtils.setField(svc, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(svc, "sessionMountValidator", sessionMountValidator);
        ReflectionTestUtils.setField(svc, "turnEventWriter", turnEventWriter);
        ReflectionTestUtils.setField(svc, "sessionMountMaterializer", sessionMountMaterializer);
        ReflectionTestUtils.setField(svc, "artifactDeliveryPort", artifactDeliveryPort);
        ReflectionTestUtils.setField(svc, "sessionThreadRepository", sessionThreadRepository);
        ReflectionTestUtils.setField(svc, "agentSnapshotApi", agentSnapshotApi);
        ReflectionTestUtils.setField(svc, "applicationEventPublisher", applicationEventPublisher);
        return svc;
    }

    /**
     * 反射装配真实入站摄取入口服务（4.2：批量落库 + 按语义驱动 turn）。
     * <p>三个驱动目标复用协作网内同一批真实实例（{@link TurnExecutionService} /
     * {@link HumanConfirmationService} / {@link SessionLifecycleService}），故
     * 「user.message 驱动跑 turn」「user.interrupt 驱动取消链落 session.status_canceling」
     * 「user.tool_confirmation 驱动领取续跑」三类端到端红线仍可固化断言；
     * 装配面与主源 {@code InboundEventService} 的 11 个 {@code @Resource} 一一对应。</p>
     */
    protected InboundEventService newInboundEventService() {
        InboundEventService svc = new InboundEventService();
        ReflectionTestUtils.setField(svc, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(svc, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(svc, "sessionRegistry", sessionRegistry);
        ReflectionTestUtils.setField(svc, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(svc, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(svc, "turnEventWriter", turnEventWriter);
        ReflectionTestUtils.setField(svc, "inboundEventValidator", inboundEventValidator);
        ReflectionTestUtils.setField(svc, "sessionThreadRepository", sessionThreadRepository);
        ReflectionTestUtils.setField(svc, "turnExecutionService", turnExecutionService);
        ReflectionTestUtils.setField(svc, "humanConfirmationService", humanConfirmationService);
        ReflectionTestUtils.setField(svc, "sessionLifecycleService", sessionLifecycleService);
        return svc;
    }

    /**
     * 落库端口替身三处同步替换（严格排空协议与启动时清除落库失败标记的故障注入点）：执行侧仅承载启动时清除落库失败标记
     * （design D1），入队与排空 / 校验落库失败标记分别经 {@link TurnEventWriter} 与 {@link TurnFinalizer}。
     */
    protected void wirePersister(ChatEventPersister persister) {
        ReflectionTestUtils.setField(turnExecutionService, "chatEventPersister", persister);
        ReflectionTestUtils.setField(turnEventWriter, "chatEventPersister", persister);
        ReflectionTestUtils.setField(turnFinalizer, "chatEventPersister", persister);
        ReflectionTestUtils.setField(humanConfirmationService, "chatEventPersister", persister);
    }

    // ==================== 通用桩 ====================

    /** 同步落库的测试替身：enqueue 即 save、flush 无操作、校验落库失败标记恒为假（无持久化故障的常态）。 */
    protected ChatEventPersister synchronousPersister() {
        return new ChatEventPersister() {
            @Override
            public void enqueue(ChatEvent event) {
                chatEventRepository.save(event);
            }

            @Override
            public void flush() {
                // 默认无落库故障：协议·事务前段排空即等同宽松排空（标记落库失败场景由专用替身注入）
            }

            @Override
            public boolean isPoisoned(String sessionId) {
                // 事件表可信：事务首行校验落库失败标记恒为假，状态迁移放行
                return false;
            }

            @Override
            public void clearPoisonFlag(String sessionId) {
            }
        };
    }

    /** 让 mock 事务模板同步执行回调（execute / executeWithoutResult 两类回调可共存）。 */
    protected void wireTransactionTemplate() {
        lenient().doAnswer(inv -> {
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /**
     * 装配事件流成功路径的公共桩（启动抢占 + 启动事务 + 构建 + 注册 + 会话状态迁移 CAS）。
     * <p>公共桩以 {@code lenient} 注册避免 UnnecessaryStubbing 误报（各用例只消费其中一部分）；
     * {@code TO_IDLE} 迁移按真实 CAS 语义返回 {@code (1, 0)}：AGENT_END 提前终态已迁移成功后，
     * onComplete 兜底再次迁移受影响行数为 0（不再重复落库终态事件）。</p>
     */
    protected BuiltAgent wireHappyPathForExecution() {
        wireTransactionTemplate();
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.BEGIN_TURN))).thenReturn(1);
        // turn 租约默认获取成功（并发拒绝场景单独置 false）
        lenient().when(coordinationLeaseService.tryAcquireTurnLease(anyString())).thenReturn(true);
        // 定时续约默认调度成功并返回停止句柄（mock 调度器不实际执行任务，续约失败场景单独捕获任务体手动触发）
        lenient().when(leaseRenewalScheduler.schedule(any(Runnable.class), any(Duration.class)))
                .thenReturn(leaseRenewalHandle);
        // 续约默认成功（fail-closed 场景单独置 false）
        lenient().when(coordinationLeaseService.renewTurnLease(anyString())).thenReturn(true);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.FINISH_TURN))).thenReturn(1, 0);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.TERMINATE))).thenReturn(1);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.PHASE_AWAIT))).thenReturn(1);
        lenient().when(sessionRepository.transition(anyString(), eq(Transition.PHASE_RESUME)))
                .thenReturn(1);
        // beginRound 以 DB max 抬升 seq 基准：mock 返回下一序号 1 → 基准 0（从 1 起分配）
        lenient().when(chatEventRepository.nextSequenceNum(anyString())).thenReturn(1L);
        lenient().when(chatEventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        // 运行装配：实时从 agent 台账解析（无回退），执行路径必须返回有效规格
        lenient().when(runtimeAgentAssemblyService.assemble(any(AgentSession.class))).thenReturn(sampleAssembly());
        BuiltAgent agent = mock(BuiltAgent.class);
        lenient().when(agentFactory.build(any(AgentAssemblySpec.class))).thenReturn(agent);
        return agent;
    }

    /** 会话语境绑定连接句柄（广播改经 connection().push 验证）。 */
    protected AgentSessionContext bindConnection(AgentSession session) {
        AgentSessionContext context = sessionRegistry.getOrCreate(session);
        context.bindConnection(connectionHandle);
        return context;
    }

    /** 汇总 chatEventRepository.save 的记录（事件类型 / payload / seq，按调用顺序）。 */
    protected List<ChatEvent> savedChatEvents() {
        return org.mockito.Mockito.mockingDetails(chatEventRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("save"))
                .map(inv -> inv.getArgument(0, ChatEvent.class))
                .collect(Collectors.toList());
    }

    /** 取落库记录中首个指定类型事件（落库顺序断言用）。 */
    protected ChatEvent firstOfType(List<ChatEvent> saved, ChatEventType type) {
        return saved.stream().filter(e -> e.type() == type).findFirst().orElseThrow();
    }

    /**
     * 事件表反查动态桩：从落库记录反查工具调用 / 工具结果两类行，模拟 durable HITL 等待现场的
     * 事件表读取（等待事实由「未应答 tool_use」承载，无 requires_action 旁路事件）。
     */
    protected void wireLedgerQueries() {
        lenient().when(chatEventRepository.findByTypes(anyString(), eq(ChatEventType.TOOL_USE_TYPES)))
                .thenAnswer(inv -> savedChatEvents().stream()
                        .filter(e -> ChatEventType.TOOL_USE_TYPES.contains(e.type().value()))
                        .toList());
        lenient().when(chatEventRepository.findByTypes(anyString(), eq(ChatEventType.TOOL_RESULT_TYPES)))
                .thenAnswer(inv -> savedChatEvents().stream()
                        .filter(e -> ChatEventType.TOOL_RESULT_TYPES.contains(e.type().value()))
                        .toList());
        lenient().when(chatEventRepository.findToolUsesByEventIds(anyString(), anyList()))
                .thenAnswer(inv -> {
                    List<String> ids = inv.getArgument(1);
                    return ids.stream()
                            .flatMap(id -> savedChatEvents().stream()
                                    .filter(e -> ChatEventType.TOOL_USE_TYPES.contains(e.type().value())
                                            && id.equals(e.eventId())))
                            .toList();
                });
    }

    // ==================== 领域夹具 ====================

    /**
     * HITL 挂起批次信号流：两个工具调用（tc-1 / tc-2）完成入参聚合后，
     * REQUIRE 信号按 reply 整批携带待确认 id，AGENT_END 收流（挂起守卫使其不终态化）。
     */
    protected Flux<AgentStreamSignal> suspendBatchSignals() {
        return Flux.just(
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", "search",
                        "{\"q\":\"x\"}", null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-2", "calculator", null, null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-2", "calculator",
                        "{\"expr\":\"1+1\"}", null),
                AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-2", "calculator", null, null),
                AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, "reply-1",
                        List.of("tc-1", "tc-2")),
                AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null));
    }

    /** 构造 {@code agent.tool_use} 事件表行（payload.tool_use_id 为 SDK 调用 id，事件表反查桩 / 明细重建输入）。 */
    protected ChatEvent toolUseEvent(String sessionId, String sdkId, String name, String inputJson, long seq) {
        return ChatEvent.create(sessionId, ChatEventType.AGENT_TOOL_USE,
                "{\"tool_use_id\":\"" + sdkId + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}", seq);
    }

    /**
     * 构造批次工具调用事件表行（保留给未迁移的外部调用方；等待事实现由未应答 {@code agent.tool_use} 承载）。
     *
     * @param sessionId 会话 ID
     * @param eventIds  批次公开事件 id（取首位作 SDK 工具调用键）
     * @param name      工具名
     * @param seq       会话内序列号
     */
    protected ChatEvent requiresActionEvent(String sessionId, List<String> eventIds, String name, long seq) {
        return ChatEvent.create(sessionId, ChatEventType.AGENT_TOOL_USE,
                "{\"tool_use_id\":\"" + eventIds.get(0) + "\",\"name\":\"" + name + "\",\"input\":{}}", seq);
    }

    protected AgentSession idleSession() {
        return AgentSession.create("1", "agent-a", "1.0.0", "{}", null);
    }

    /** 调度打标会话夹具（cron 触发，triggerId 指向调度器）：终局出口须发布带打标的终局事件。 */
    protected AgentSession scheduledIdleSession() {
        return AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, "cron", "dep-1");
    }

    /** 指定 metadata 的会话夹具（浅合并用例输入）。 */
    protected AgentSession sessionWithMetadata(String metadata) {
        AgentSession base = idleSession();
        return new AgentSession(base.id(), base.sessionId(), base.userId(), base.agentId(), base.agentVersion(),
                base.status(), base.turnPhase(), metadata, base.title(), base.environmentId(),
                base.vaultIds(), base.memoryStoreIds(), base.environmentVariables(), base.resources(),
                base.triggerType(), base.triggerId(), base.lastActiveAt(), base.archivedAt(),
                base.createdAt(), base.updatedAt(), base.createdBy(), base.updatedBy());
    }

    /** 挂载会话夹具（resources 与 memoryStoreIds 显式给定，资源管理用例输入）。 */
    protected AgentSession mountedSession(List<SessionResource> resources, List<String> memoryStoreIds,
                                          AgentSessionStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentSession(1L, "sess_mount_" + System.nanoTime(), "1", "agent-a", "1.0.0",
                status, status == AgentSessionStatus.IDLE ? TurnPhase.IDLE : TurnPhase.RUNNING,
                "{}", null, null, List.of(), memoryStoreIds, "{}",
                resources, null, null, now, null, now, now, null, null);
    }

    /** 归档会话夹具（追加挂载拒绝场景：归档是正交维度，status 保持 idle 而 archived_at 非空）。 */
    protected AgentSession archivedSession() {
        AgentSession base = idleSession();
        return new AgentSession(base.id(), base.sessionId(), base.userId(), base.agentId(), base.agentVersion(),
                AgentSessionStatus.IDLE, base.turnPhase(), base.metadata(), base.title(), base.environmentId(),
                base.vaultIds(), base.memoryStoreIds(), base.environmentVariables(), base.resources(),
                base.triggerType(), base.triggerId(), base.lastActiveAt(), OffsetDateTime.now(),
                base.createdAt(), base.updatedAt(), base.createdBy(), base.updatedBy());
    }

    /** 装配结果样本（构建路径输入：领域规格，含凭证 / 端点）。 */
    protected AgentAssemblySpec sampleAssembly() {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "v1"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding(
                        "openai:gpt-4", "sk-cred", "https://api.example.com/v1", null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent("你是数据分析专家", null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), java.util.Map.of(), List.of()))
                .withToolGovernance(AgentAssemblySpec.ToolGovernance.empty())
                .withArtifactOwnership(AgentAssemblySpec.ArtifactOwnership.empty())
                .build();
    }
}
