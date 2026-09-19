package com.linkroa.deepdataagent.agent.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.agent.application.port.SessionEnvironmentVariablesValidationPort;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentRunsQuery;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentsQuery;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentListFilter;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRunsFilter;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRunRepository;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.runtime.api.CoordinationLeaseApi;
import com.linkroa.deepdataagent.runtime.api.SchedulerSessionApi;
import com.linkroa.deepdataagent.runtime.api.dto.SchedulerLaunchDTO;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.api.VaultReferenceApi;
import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 调度器应用服务（Deployment 即调度器资源）。
 *
 * <p>职责：创建调度器（Agent 版本<b>创建时解析并固定</b>——省略版本取激活版本、显式 pin
 * 校验存在性；webhook flag 生成回调 token；调度配置物化 {@code next_run_at}）、
 * 列表 / 详情、暂停（可携带原因）/ 恢复 / 归档、<b>触发执行</b>
 * （手动 / webhook / cron 轮询：经 {@link SchedulerSessionApi} 新建打标 Session 跑 turn，
 * 落 {@code deployment_run} 运行记录并刷新最近运行快照）。</p>
 *
 * <p>调度领取（D14）：{@link #triggerDueDeployments} 以「CAS 推进 {@code next_run_at}」为领取动作，
 * 多实例并发下恰好一个实例领取成功；领取后触发失败该窗口跳过不补（misfire 语义）。
 * 触发编排运行在调度线程（无 ThreadLocal 认证上下文），全程显式透传调度器 owner。</p>
 *
 * <p>owner 隔离：读写操作先校验归属（不含则 404 不泄露存在性）；webhook 路径 token 触发
 * 免 JWT，暂停 / 已归档 / 未知 token 统一 404。</p>
 */
@Slf4j
@Service
public class DeploymentApplicationService {

    /** 默认时区（全链路统一 Asia/Shanghai，调度到期重算依据） */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /** JSON 文本工具（元数据浅合并） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private DeploymentRepository deploymentRepository;
    @Resource
    private DeploymentRunRepository deploymentRunRepository;
    @Resource
    private AgentDefinitionRepository agentDefinitionRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private AgentVersionAssemblyPort agentVersionAssemblyPort;
    @Resource
    private SessionEnvironmentVariablesValidationPort sessionEnvironmentVariablesValidationPort;
    @Resource
    private EnvironmentRepository environmentRepository;
    @Resource
    private VaultReferenceApi vaultReferenceApi;
    @Resource
    private SchedulerSessionApi schedulerSessionApi;
    @Resource
    private CoordinationLeaseApi coordinationLeaseApi;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建调度器：
     * <ol>
     *   <li>校验 Agent 归属；</li>
     *   <li>版本固定：省略版本解析当前<b>激活版本</b>，显式 pin 校验版本存在性——
     *       触发链一律使用创建时固定的版本，不随后续发布漂移；</li>
     *   <li>webhook=true 生成路径 token（免 JWT 回调密钥）；</li>
     *   <li>持久化调度器（有 schedule 时物化首次到期时间）。</li>
     * </ol>
     */
    public Deployment create(CreateDeploymentCommand command) {
        Long ownerId = AuthContext.requireUserId();
        return transactionTemplate.execute(status -> {
            requireOwnedAgent(command.agentId());
            // 挂载引用校验（审查修复 F10）：环境 / 保管库不存在或越权 → 404，避免落库后调度静默停摆
            validateMounts(command.environmentId(), command.vaultIds(), ownerId);
            // 触发会话环境变量形态校验（与挂载引用同点前置）：不合规材料会在每次触发建 Session 时才 400，
            // 落库即等于创建出「永远无法触发」的调度器
            sessionEnvironmentVariablesValidationPort.validate(command.environmentVariables());
            int agentVersion = resolveVersionToPin(command.agentId(), command.agentVersion(), ownerId);
            String webhookToken = command.webhook()
                    ? UUID.randomUUID().toString().replace("-", "")
                    : null;
            return deploymentRepository.save(Deployment.create(
                    // 资源 ID 语义前缀（shared/api-conventions：dep_ + 无连分 UUID）
                    Deployment.DEPLOYMENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                    command.name(),
                    command.description(),
                    command.agentId(),
                    agentVersion,
                    command.environmentId(),
                    command.environmentVariables(),
                    command.resources(),
                    command.vaultIds(),
                    command.initialEvents(),
                    command.metadata(),
                    command.schedule(),
                    webhookToken,
                    ownerId
            ));
        });
    }

    /**
     * 调度器游标分页列表（6.5 管理面 Cursor 约定，创建时间降序 keyset）：
     * 支持状态 / Agent / 创建时间区间过滤与 {@code include_archived} 归档包含开关
     * （缺省排除归档）；游标 after_id/before_id 指向的调度器不存在 / 非本人 → 404。
     * 指定 agentId 时校验 Agent 归属（非 owner → 404）。
     */
    public CursorPage<Deployment> list(ListDeploymentsQuery query) {
        Long ownerId = query.ownerId();
        String agentId = StringUtils.trimToNull(query.agentId());
        if (agentId != null) {
            requireOwnedAgent(agentId);
        }
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (StringUtils.isNotBlank(cursor.afterId())) {
            Deployment anchor = requireOwnedDeployment(cursor.afterId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
        } else if (StringUtils.isNotBlank(cursor.beforeId())) {
            Deployment anchor = requireOwnedDeployment(cursor.beforeId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
            reverse = true;
        }
        DeploymentListFilter filter = new DeploymentListFilter(query.status(), agentId,
                query.createdAfter(), query.createdBefore(), query.includeArchived(),
                cursorCreatedAt, cursorRowId, reverse);
        List<Deployment> rows = deploymentRepository.findByCursor(ownerId, filter, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<Deployment> data = hasMore ? List.copyOf(rows.subList(0, cursor.limit())) : rows;
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return CursorPage.of(data, hasMore, Deployment::deploymentId);
    }

    /**
     * merge-patch 更新调度器（6.5 管理面）：命令侧 present 标志区分「缺省不改」与
     * 「显式提供」，提供且值为 null 即清空（描述→空串、透传载荷→默认空集、
     * 元数据→{}、调度→null）；元数据提供时为浅合并（同名键覆盖、键值 null 删除该键）。
     * 调度配置变更时按当前时刻重算 {@code nextRunAt}，未变更时保留既有到期时间；
     * 绑定关系（agent / 固定版本 / webhook 开通）不可调。
     */
    public Deployment update(UpdateDeploymentCommand command) {
        return transactionTemplate.execute(status -> {
            Deployment current = requireOwnedDeployment(command.deploymentId());
            String name = StringUtils.isNotBlank(command.name()) ? command.name() : current.name();
            String description = command.descriptionPresent()
                    ? StringUtils.trimToEmpty(command.description()) : current.description();
            String environmentId = command.environmentIdPresent()
                    ? command.environmentId() : current.environmentId();
            String environmentVariables = command.environmentVariablesPresent()
                    ? command.environmentVariables() : current.environmentVariables();
            String resources = command.resourcesPresent() ? command.resources() : current.resources();
            List<String> vaultIds = command.vaultIdsPresent() ? command.vaultIds() : current.vaultIds();
            String initialEvents = command.initialEventsPresent()
                    ? command.initialEvents() : current.initialEvents();
            String metadata;
            if (!command.metadataPresent()) {
                metadata = current.metadata();
            } else if (command.metadataMerge() == null) {
                metadata = "{}";
            } else {
                metadata = mergeMetadata(current.metadata(), command.metadataMerge());
            }
            DeploymentSchedule schedule = command.schedulePresent() ? command.schedule() : current.schedule();
            // 挂载引用校验（审查修复 F10）：仅提供 environmentId / vaultIds 时校验，缺失 / 越权 → 404
            if (command.environmentIdPresent() || command.vaultIdsPresent()) {
                validateMounts(environmentId, vaultIds, current.ownerId());
            }
            // 触发会话环境变量形态校验：仅提供该项时校验（同创建路径口径），避免更新口成为绕过面
            if (command.environmentVariablesPresent()) {
                sessionEnvironmentVariablesValidationPort.validate(environmentVariables);
            }
            OffsetDateTime nextRunAt = command.schedulePresent()
                    ? (schedule == null ? null : schedule.nextAfter(OffsetDateTime.now(DEFAULT_ZONE)))
                    : current.nextRunAt();
            return deploymentRepository.update(current.withTunable(name, description, environmentId,
                    environmentVariables, resources, vaultIds, initialEvents, metadata, schedule, nextRunAt));
        });
    }

    /**
     * 调度器详情（含已归档记录，归档后仍可查看）。
     */
    public Deployment get(String deploymentId) {
        return requireOwnedDeployment(deploymentId);
    }

    /**
     * 暂停调度器（{@code status=paused} + 记录原因；暂停期间不被轮询领取、不可手动触发）。
     *
     * @param deploymentId 调度器业务 ID
     * @param reason       暂停原因（可空）
     */
    public Deployment pause(String deploymentId, String reason) {
        return deploymentRepository.update(requireOwnedDeployment(deploymentId).pause(reason));
    }

    /**
     * 恢复调度器（{@code status=active}，清空暂停原因；有 schedule 时到期时间按恢复时刻
     * 重算，不补触发暂停期间的欠账窗口）；已归档调度器不可恢复（抛业务异常）。
     */
    public Deployment unpause(String deploymentId) {
        return deploymentRepository.update(requireOwnedDeployment(deploymentId).unpause());
    }

    /**
     * 归档调度器（{@code archived_at} + {@code status=paused} 双写，不再触发、列表不可见）。
     */
    public Deployment archive(String deploymentId) {
        return deploymentRepository.update(requireOwnedDeployment(deploymentId).archive());
    }

    /**
     * 手动运行调度器（run，受保护端点，需用户 JWT；6.5 管理面由 trigger 更名）：
     * 校验归属 + 可触发态后，新建打标 Session 跑 turn、落运行记录并刷新最近运行快照。
     * paused / 已归档调度器不可触发（与不存在不可区分 → 404）。
     *
     * @param deploymentId 调度器业务 ID
     * @param input        触发消息（可空，运行时回退首批事件合成或默认调度提示）
     * @return 触发结果（调度器 ID + 运行记录 ID + 新建会话 ID）
     */
    public DeploymentTriggerResult run(String deploymentId, String input) {
        Deployment deployment = requireOwnedDeployment(deploymentId);
        if (!deployment.active()) {
            throw new ResourceNotFoundException("调度器不存在");
        }
        return fire(deployment, input, DeploymentTriggerType.MANUAL);
    }

    /**
     * 运行记录游标分页列表（6.5 管理面，触发时间降序 keyset）：{@code deploymentId}
     * 非空为单调度器作用域（先校验归属，非 owner → 404）；为空为全局作用域
     * （经 deployment 表归属子查询过滤）。游标 after_id/before_id 指向的运行记录
     * 不存在 / 非本人 → 404；支持触发时间区间过滤。
     */
    public CursorPage<DeploymentRun> listRuns(ListDeploymentRunsQuery query) {
        Long ownerId = query.ownerId();
        String deploymentId = StringUtils.trimToNull(query.deploymentId());
        if (deploymentId != null) {
            requireOwnedDeployment(deploymentId);
        }
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (StringUtils.isNotBlank(cursor.afterId())) {
            DeploymentRun anchor = requireOwnedRun(cursor.afterId(), ownerId);
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
        } else if (StringUtils.isNotBlank(cursor.beforeId())) {
            DeploymentRun anchor = requireOwnedRun(cursor.beforeId(), ownerId);
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
            reverse = true;
        }
        DeploymentRunsFilter filter = new DeploymentRunsFilter(deploymentId,
                deploymentId == null ? ownerId : null,
                query.createdAfter(), query.createdBefore(), cursorCreatedAt, cursorRowId, reverse);
        List<DeploymentRun> rows = deploymentRunRepository.findByCursor(filter, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<DeploymentRun> data = hasMore ? List.copyOf(rows.subList(0, cursor.limit())) : rows;
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return CursorPage.of(data, hasMore, DeploymentRun::runId);
    }

    /**
     * 单调度器运行记录详情：记录不存在 / 不属于该调度器 / 调度器非本人 → 404（不泄露存在性）。
     */
    public DeploymentRun getRun(String deploymentId, String runId) {
        Deployment deployment = requireOwnedDeployment(deploymentId);
        DeploymentRun run = deploymentRunRepository.findByRunId(runId)
                .orElseThrow(() -> new ResourceNotFoundException("运行记录不存在"));
        if (!run.deploymentId().equals(deployment.deploymentId())) {
            throw new ResourceNotFoundException("运行记录不存在");
        }
        return run;
    }

    /**
     * 全局运行记录详情（跨全部调度器）：经运行记录 → 所属调度器归属校验，
     * 非本人一律 404。
     */
    public DeploymentRun getRunGlobal(String runId) {
        return requireOwnedRun(runId, AuthContext.requireUserId());
    }

    /**
     * webhook 触发调度器（公开端点，免 JWT）：按路径 token 定位<b>可触发</b>调度器，
     * 暂停 / 已归档 / 未知 token 与不存在不可区分（统一 404）。
     *
     * @param webhookToken 路径中的 webhook token
     * @param input        触发消息（可空，运行时回退首批事件合成或默认调度提示）
     * @return 触发结果（调度器 ID + 运行记录 ID + 新建会话 ID）
     */
    public DeploymentTriggerResult triggerByWebhookToken(String webhookToken, String input) {
        Deployment deployment = deploymentRepository.findByWebhookToken(webhookToken)
                .orElseThrow(() -> new ResourceNotFoundException("调度器不存在"));
        return fire(deployment, input, DeploymentTriggerType.WEBHOOK);
    }

    /**
     * 轮询领取并触发到期的定时调度器（调度线程入口，无认证上下文）：
     * <ol>
     *   <li>粗筛到期候选（active、未归档、有 schedule 且 {@code next_run_at} 已到期）；</li>
     *   <li>逐条以「CAS 推进 {@code next_run_at}」领取——CAS 失败即被其他实例领取，跳过；
     *       新到期时间按当前时刻重算，天然跳过 misfire 欠账窗口；</li>
     *   <li>领取成功后触发（cron 方式）；触发异常仅记日志，该窗口已消费不补触发。</li>
     * </ol>
     *
     * @param now   当前时刻
     * @param limit 单轮最多领取条数
     * @return 本轮实际触发成功的条数
     */
    public int triggerDueDeployments(OffsetDateTime now, int limit) {
        List<Deployment> dueDeployments = deploymentRepository.findDue(now, limit);
        int fired = 0;
        for (Deployment deployment : dueDeployments) {
            OffsetDateTime expected = deployment.nextRunAt();
            if (expected == null || deployment.schedule() == null) {
                continue;
            }
            OffsetDateTime next = deployment.schedule().nextAfter(now);
            if (!deploymentRepository.advanceNextRun(deployment.deploymentId(), expected, next)) {
                continue;
            }
            try {
                // 内存对象同步推进后的到期时间，避免后续全量更新回写旧值
                fire(deployment.advanceNextRun(now), null, DeploymentTriggerType.CRON);
                fired++;
            } catch (Exception e) {
                log.warn("调度器 cron 触发失败（本窗口已领取，跳过不补）: deploymentId={}, dueAt={}",
                        deployment.deploymentId(), expected, e);
            }
        }
        return fired;
    }

    /**
     * 创建时版本固定解析：省略版本取激活版本（{@code active_version}，可回滚）；
     * 显式 pin 校验版本台账存在性。解析结果落库固化，触发链不再动态跟随任何版本指针。
     */
    private int resolveVersionToPin(String agentId, Integer pinnedVersion, Long ownerId) {
        if (pinnedVersion == null) {
            return Integer.parseInt(agentVersionAssemblyPort.activeVersionNumber(agentId, ownerId));
        }
        agentVersionRepository.findByAgentIdAndVersionNumber(agentId, pinnedVersion)
                .orElseThrow(() -> new ResourceNotFoundException("Agent版本不存在"));
        return pinnedVersion;
    }

    /**
     * 触发执行核心：调度防重（协调层 fire lease）→ 以创建时固定的版本经
     * {@link SchedulerSessionApi} 新建打标 Session 并驱动首个 turn（透传调度器挂载材料）→
     * 落运行记录（running 初始态）→ 刷新最近运行快照（{@code last_run_at / last_session_id / last_status}）。
     *
     * <p>fire lease 语义（D8 协调层收口防重）：窗口内同一调度器仅一个触发可进入执行编排，
     * 重复触发（双击 / 重复回调）被拒；触发流程结束（含异常）显式释放，异常退出未释放时
     * 由租约 TTL 过期兜底失效。</p>
     */
    private DeploymentTriggerResult fire(Deployment deployment, String input, DeploymentTriggerType triggerKind) {
        // 调度防重：获取 fire lease 成功才可进入执行编排（窗口内拒绝重复触发，409 语义）
        if (!coordinationLeaseApi.tryAcquireFireLease(deployment.deploymentId())) {
            throw new ResourceConflictException("调度器正在触发中，请勿重复提交");
        }
        try {
            // 装配版本：一律使用创建时固化的版本（触发不随后续发布漂移）；
            // owner 经调度器归属透传装配端口（调度链路无 ThreadLocal 认证上下文）
            String sessionId = schedulerSessionApi.launch(new SchedulerLaunchDTO(
                    String.valueOf(deployment.ownerId()),
                    deployment.agentId(),
                    String.valueOf(deployment.agentVersion()),
                    deployment.environmentId(),
                    input,
                    triggerKind.getValue(),
                    deployment.deploymentId(),
                    deployment.environmentVariables(),
                    deployment.resources(),
                    deployment.vaultIds(),
                    deployment.initialEvents(),
                    deployment.metadata()
            ));
            DeploymentRun run = deploymentRunRepository.save(DeploymentRun.start(
                    DeploymentRun.RUN_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                    deployment.deploymentId(), sessionId, triggerKind));
            // 窄列回写最近运行（审查修复 F08）：不整行回写陈旧内存快照，避免撤销并发 pause / update
            deploymentRepository.updateLastRun(deployment.deploymentId(), sessionId,
                    run.status().getValue(), OffsetDateTime.now(DEFAULT_ZONE));
            return new DeploymentTriggerResult(deployment.deploymentId(), run.runId(), sessionId);
        } finally {
            coordinationLeaseApi.releaseFireLease(deployment.deploymentId());
        }
    }

    /**
     * 按触发会话回写运行终态（跨 BC 契约 {@code DeploymentRunLifecycleApi} 的执行体）。
     * <p>episode 口径幂等收口（D4）：单事务内先按 {@code (session_id, running)} CAS 将运行行
     * 终态化（{@code running → 终态 + finished_at}），命中（受影响 1 行）后同事务窄列刷新调度器
     * {@code last_status} 快照（{@code last_session_id} 守卫，避免迟到回写覆盖新触发）；
     * 无 running 运行行（非调度会话 / 已终态）则整体空操作。outcome 非法值抛
     * {@link IllegalArgumentException}（进程内契约，消费者侧 try/catch 仅告警）。</p>
     *
     * @param sessionId 触发会话ID
     * @param outcome   终态契约值（succeeded / failed / terminated）
     */
    public void completeRunByTriggerSession(String sessionId, String outcome) {
        DeploymentRunStatus terminal = parseTerminalOutcome(outcome);
        transactionTemplate.executeWithoutResult(status -> {
            DeploymentRun running = deploymentRunRepository.findRunningBySessionId(sessionId).orElse(null);
            if (running == null) {
                // 非调度会话或本 episode 已收口：幂等空操作
                return;
            }
            boolean completed = deploymentRunRepository.casCompleteBySessionId(
                    sessionId, terminal, OffsetDateTime.now(DEFAULT_ZONE));
            if (completed) {
                deploymentRepository.refreshLastStatusForTrigger(
                        running.deploymentId(), sessionId, terminal.getValue());
            }
        });
    }

    /**
     * 解析并校验终态回写 outcome：仅容 {@code succeeded / failed / terminated} 三终态，
     * {@code running} 与非法值均抛 {@link IllegalArgumentException}（回写语义只落终态）。
     */
    private static DeploymentRunStatus parseTerminalOutcome(String outcome) {
        DeploymentRunStatus parsed = DeploymentRunStatus.fromValue(outcome);
        if (parsed == DeploymentRunStatus.RUNNING) {
            throw new IllegalArgumentException("运行终态回写不接受 running 值: " + outcome);
        }
        return parsed;
    }

    /**
     * 按业务ID查询并校验归属：仅 owner 可见（不含则 404，不泄露存在性）。
     */
    private Deployment requireOwnedDeployment(String deploymentId) {
        if (StringUtils.isBlank(deploymentId)) {
            throw new ResourceNotFoundException("调度器不存在");
        }
        Deployment deployment = deploymentRepository.findByDeploymentId(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("调度器不存在"));
        if (!deployment.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("调度器不存在");
        }
        return deployment;
    }

    /**
     * 按业务ID查询运行记录并校验归属（6.5 管理面）：deployment_run 表无 owner 列，
     * 经所属调度器反查归属；记录 / 所属调度器任一缺失或非本人 → 404（不泄露存在性）。
     */
    private DeploymentRun requireOwnedRun(String runId, Long ownerId) {
        DeploymentRun run = StringUtils.isBlank(runId)
                ? null
                : deploymentRunRepository.findByRunId(runId).orElse(null);
        if (run == null) {
            throw new ResourceNotFoundException("运行记录不存在");
        }
        Deployment deployment = deploymentRepository.findByDeploymentId(run.deploymentId())
                .orElseThrow(() -> new ResourceNotFoundException("运行记录不存在"));
        if (!deployment.ownerId().equals(ownerId)) {
            throw new ResourceNotFoundException("运行记录不存在");
        }
        return run;
    }

    /**
     * 元数据浅合并（merge-patch 键级语义）：既有 JSON 对象叠加增量（同名键覆盖），
     * 增量中值为 null 的键表示<b>删除该键</b>；解析失败的历史脏值收敛为空对象，
     * 不阻断更新（与 runtime 会话元数据合并的容错策略一致）。
     */
    private String mergeMetadata(String existingJson, String mergeJson) {
        Map<String, Object> merged = parseJsonObject(existingJson);
        parseJsonObject(mergeJson).forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        try {
            return OBJECT_MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            throw new IllegalStateException("元数据合并结果序列化失败: " + e.getMessage(), e);
        }
    }

    /** JSON 对象文本 → 可变键值对象（空白 / 非法收敛为空对象）。 */
    private Map<String, Object> parseJsonObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 挂载引用校验（审查修复 F10）：环境 / 保管库在落库前必须存在且归属一致，
     * 不存在 / 越权统一 404（不泄露存在性），避免调度器带悬空引用落库后触发链静默停摆。
     *
     * @param environmentId 运行环境业务 ID（空白跳过——会话可后挂环境）
     * @param vaultIds      保管库 ID 列表（空集合跳过）
     * @param ownerId       调度器归属用户 ID
     */
    private void validateMounts(String environmentId, List<String> vaultIds, Long ownerId) {
        if (StringUtils.isNotBlank(environmentId)) {
            environmentRepository.findByEnvironmentId(environmentId)
                    .filter(env -> env.ownerId().equals(ownerId))
                    .orElseThrow(() -> new ResourceNotFoundException("运行环境不存在"));
        }
        List<String> requested = vaultIds == null ? List.of()
                : vaultIds.stream().filter(StringUtils::isNotBlank).distinct().toList();
        if (requested.isEmpty()) {
            return;
        }
        Set<String> resolved = vaultReferenceApi.resolveByIds(ownerId, requested).stream()
                .map(VaultReferenceDTO::vaultId)
                .collect(Collectors.toSet());
        if (resolved.size() < requested.size()) {
            throw new ResourceNotFoundException("保管库不存在");
        }
    }

    /**
     * 校验调度器所属 Agent 归属：仅 owner 可见（不含则 404）。
     */
    private AgentDefinition requireOwnedAgent(String agentId) {
        AgentDefinition definition = agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        if (!definition.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("Agent不存在");
        }
        return definition;
    }
}
