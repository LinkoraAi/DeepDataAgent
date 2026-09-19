package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.agent.application.port.SessionEnvironmentVariablesValidationPort;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentRunsQuery;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentsQuery;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentListFilter;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRunsFilter;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeploymentApplicationService} 单测：创建时版本固定解析（激活版本 / 显式 pin）、
 * webhook token 生成、游标列表（过滤装配 / 游标位点 / 方向翻转）、merge-patch 更新
 * （缺省保留 / 显式清空 / 元数据浅合并 / 调度重算）、暂停 / 恢复 / 归档、手动运行 run
 * （fire lease + 打标会话 + 运行记录落库 + 最近运行快照）、运行记录游标查询
 * （单调度器 / 全局作用域与归属校验）与 cron 轮询「CAS 领取 → 触发 → misfire 跳过」语义。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentApplicationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CRON_DAILY_9 = "0 0 9 * * *";
    private static final OffsetDateTime T_CURSOR = OffsetDateTime.parse("2026-09-01T08:00:00+08:00");

    @Mock private DeploymentRepository deploymentRepository;
    @Mock private DeploymentRunRepository deploymentRunRepository;
    @Mock private AgentDefinitionRepository agentDefinitionRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private AgentVersionAssemblyPort agentVersionAssemblyPort;
    @Mock private SessionEnvironmentVariablesValidationPort sessionEnvironmentVariablesValidationPort;
    @Mock private EnvironmentRepository environmentRepository;
    @Mock private VaultReferenceApi vaultReferenceApi;
    @Mock private SchedulerSessionApi schedulerSessionApi;
    @Mock private CoordinationLeaseApi coordinationLeaseApi;
    @Mock private TransactionTemplate transactionTemplate;

    private DeploymentApplicationService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        service = new DeploymentApplicationService();
        ReflectionTestUtils.setField(service, "deploymentRepository", deploymentRepository);
        ReflectionTestUtils.setField(service, "deploymentRunRepository", deploymentRunRepository);
        ReflectionTestUtils.setField(service, "agentDefinitionRepository", agentDefinitionRepository);
        ReflectionTestUtils.setField(service, "agentVersionRepository", agentVersionRepository);
        ReflectionTestUtils.setField(service, "agentVersionAssemblyPort", agentVersionAssemblyPort);
        ReflectionTestUtils.setField(service, "sessionEnvironmentVariablesValidationPort",
                sessionEnvironmentVariablesValidationPort);
        ReflectionTestUtils.setField(service, "environmentRepository", environmentRepository);
        ReflectionTestUtils.setField(service, "vaultReferenceApi", vaultReferenceApi);
        ReflectionTestUtils.setField(service, "schedulerSessionApi", schedulerSessionApi);
        ReflectionTestUtils.setField(service, "coordinationLeaseApi", coordinationLeaseApi);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** active 且已发布 3 版、激活第 2 版的归属 Agent */
    private AgentDefinition buildDefinition(Long ownerId) {
        return AgentDefinition.restore(1L, "agent-1", "agent", null, null, 3, 2,
                ownerId, null, null, null, null);
    }

    private AgentVersion buildVersion(int versionNumber) {
        return AgentVersion.create("v-" + versionNumber, "agent-1", versionNumber, "v", null, "sys",
                "mp-1", null, null, null, null, null, null);
    }

    private CreateDeploymentCommand buildCommand(Integer agentVersion, DeploymentSchedule schedule, boolean webhook) {
        return new CreateDeploymentCommand("调度器", null, "agent-1", agentVersion, null,
                null, null, null, null, null, schedule, webhook);
    }

    /** 手工调度器（无调度、指定状态与归属） */
    private Deployment buildManualDeployment(DeploymentStatus status, Long ownerId) {
        return new Deployment(1L, "dep-1", "手动调度", null, "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{}", null, null, null,
                status, null, null, null, null, ownerId, null, null, null, null, null);
    }

    /** 带时间戳行位的调度器（游标锚点断言用） */
    private Deployment buildTimestampedDeployment(String deploymentId, Long rowId, Long ownerId) {
        return new Deployment(rowId, deploymentId, "游标调度", null, "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{}", null, null, null,
                DeploymentStatus.ACTIVE, null, null, null, null, ownerId, null, T_CURSOR, T_CURSOR, null, null);
    }

    /** cron 调度器：到期时间为 nextRunAt，固定版本 2 */
    private Deployment buildCronDeployment(OffsetDateTime nextRunAt, DeploymentStatus status, Long ownerId) {
        return new Deployment(1L, "dep-1", "定时调度", null, "agent-1", 2, null,
                "{\"TZ\":\"UTC\"}", "[]", List.of("vault-a"), "[]", "{\"biz\":\"x\"}",
                new DeploymentSchedule(CRON_DAILY_9, null), nextRunAt, null,
                status, null, null, null, null, ownerId, null, null, null, null, null);
    }

    /** 运行记录夹具（指定行位与归属调度器） */
    private DeploymentRun buildRun(Long rowId, String runId, String deploymentId) {
        return new DeploymentRun(rowId, runId, deploymentId, "sess-1", DeploymentTriggerType.MANUAL,
                DeploymentRunStatus.RUNNING, T_CURSOR, null, T_CURSOR, T_CURSOR);
    }

    /** 缺省全不提供的更新命令基底（present 成对装配小工具） */
    private UpdateCommandBuilder updateBuilder(String deploymentId) {
        return new UpdateCommandBuilder(deploymentId);
    }

    private static final class UpdateCommandBuilder {
        private final String deploymentId;
        private String name;
        private String description;
        private boolean descriptionPresent;
        private String environmentId;
        private boolean environmentIdPresent;
        private String environmentVariables;
        private boolean environmentVariablesPresent;
        private String resources;
        private boolean resourcesPresent;
        private List<String> vaultIds;
        private boolean vaultIdsPresent;
        private String initialEvents;
        private boolean initialEventsPresent;
        private String metadataMerge;
        private boolean metadataPresent;
        private DeploymentSchedule schedule;
        private boolean schedulePresent;

        private UpdateCommandBuilder(String deploymentId) {
            this.deploymentId = deploymentId;
        }

        private UpdateCommandBuilder name(String value) {
            this.name = value;
            return this;
        }

        private UpdateCommandBuilder environmentId(String value) {
            this.environmentId = value;
            this.environmentIdPresent = true;
            return this;
        }

        private UpdateCommandBuilder description(String value) {
            this.description = value;
            this.descriptionPresent = true;
            return this;
        }

        private UpdateCommandBuilder environmentVariables(String value) {
            this.environmentVariables = value;
            this.environmentVariablesPresent = true;
            return this;
        }

        private UpdateCommandBuilder vaultIds(List<String> value) {
            this.vaultIds = value;
            this.vaultIdsPresent = true;
            return this;
        }

        private UpdateCommandBuilder metadata(String mergeJson) {
            this.metadataMerge = mergeJson;
            this.metadataPresent = true;
            return this;
        }

        private UpdateCommandBuilder schedule(DeploymentSchedule value) {
            this.schedule = value;
            this.schedulePresent = true;
            return this;
        }

        private UpdateDeploymentCommand build() {
            return new UpdateDeploymentCommand(deploymentId, name, description, descriptionPresent,
                    environmentId, environmentIdPresent, environmentVariables, environmentVariablesPresent,
                    resources, resourcesPresent, vaultIds, vaultIdsPresent, initialEvents, initialEventsPresent,
                    metadataMerge, metadataPresent, schedule, schedulePresent);
        }
    }

    // ==================== create ====================

    @Test
    void should_pinActiveVersionAndMaterializeNextRunAt_when_create_given_scheduleWithOmittedVersion() {
        // given（省略版本：解析激活版本 2 并固化；有调度即物化首次到期时间）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(agentVersionAssemblyPort.activeVersionNumber("agent-1", 1L)).thenReturn("2");
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment deployment = service.create(buildCommand(null, new DeploymentSchedule(CRON_DAILY_9, null), false));

        // then（业务 ID 带 dep_ 语义前缀）
        assertTrue(deployment.deploymentId().startsWith("dep_"));
        assertEquals(2, deployment.agentVersion());
        assertEquals(DeploymentStatus.ACTIVE, deployment.status());
        assertNotNull(deployment.schedule());
        assertNotNull(deployment.nextRunAt());
        assertNull(deployment.webhookToken());
        verify(agentVersionAssemblyPort, never()).latestVersionNumber(anyString(), any());
    }

    @Test
    void should_keepManualSemantics_when_create_given_noScheduleNoWebhook() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(agentVersionAssemblyPort.activeVersionNumber("agent-1", 1L)).thenReturn("2");
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment deployment = service.create(buildCommand(null, null, false));

        // then（仅手动：无调度无到期时间无 token，JSON 载荷归一默认值）
        assertNull(deployment.schedule());
        assertNull(deployment.nextRunAt());
        assertNull(deployment.webhookToken());
        assertEquals("{}", deployment.environmentVariables());
        assertEquals("[]", deployment.resources());
    }

    @Test
    void should_rejectBeforePersist_when_create_given_invalidEnvironmentVariables() {
        // given（形态违规的触发会话环境变量：值非字符串——落库即等于创建出「永远无法触发」的调度器）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        doThrow(new IllegalArgumentException("会话环境变量值必须为字符串: COUNT"))
                .when(sessionEnvironmentVariablesValidationPort).validate("{\"COUNT\":5}");

        // when & then（创建口即 400 且零落库：与挂载引用校验同点前置，不把违规留给触发期）
        CreateDeploymentCommand command = new CreateDeploymentCommand("调度器", null, "agent-1", null, null,
                "{\"COUNT\":5}", null, null, null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> service.create(command));
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    void should_generateWebhookTokenAndPassThroughPayloads_when_create_given_webhookFlagWithMounts() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(agentVersionAssemblyPort.activeVersionNumber("agent-1", 1L)).thenReturn("2");
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault-a")))
                .thenReturn(List.of(new VaultReferenceDTO("vault-a", "凭证库")));
        CreateDeploymentCommand command = new CreateDeploymentCommand("回调调度", null, "agent-1", null, null,
                "{\"KEY\":\"v1\"}", "[{\"type\":\"file\",\"file_id\":\"file_1\"}]",
                List.of("vault-a"), "[{\"type\":\"user_message\",\"text\":\"开工\"}]",
                "{\"biz\":\"x\"}", null, true);

        // when
        Deployment deployment = service.create(command);

        // then（webhook=true 生成路径 token；透传载荷原样落位）
        assertNotNull(deployment.webhookToken());
        assertEquals("{\"KEY\":\"v1\"}", deployment.environmentVariables());
        assertEquals("[{\"type\":\"file\",\"file_id\":\"file_1\"}]", deployment.resources());
        assertEquals(List.of("vault-a"), deployment.vaultIds());
        assertEquals("[{\"type\":\"user_message\",\"text\":\"开工\"}]", deployment.initialEvents());
        assertEquals("{\"biz\":\"x\"}", deployment.metadata());
    }

    @Test
    void should_throwNotFound_when_create_given_missingEnvironment() {
        // given（审查修复 F10：环境引用不存在 → 404，不落库避免调度静默停摆）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(environmentRepository.findByEnvironmentId("env-ghost")).thenReturn(Optional.empty());
        CreateDeploymentCommand command = new CreateDeploymentCommand("调度器", null, "agent-1", null, "env-ghost",
                null, null, null, null, null, null, false);

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(command));
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_create_given_missingVault() {
        // given（保管库解析差集非空——不存在 / 越权 → 404，不落库）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault-ghost"))).thenReturn(List.of());
        CreateDeploymentCommand command = new CreateDeploymentCommand("调度器", null, "agent-1", null, null,
                null, null, List.of("vault-ghost"), null, null, null, false);

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(command));
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    void should_passMountValidation_when_create_given_ownedEnvironmentAndResolvedVaults() {
        // given（归属一致的环境 + 可解析保管库：校验通过正常落库）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(agentVersionAssemblyPort.activeVersionNumber("agent-1", 1L)).thenReturn("2");
        when(environmentRepository.findByEnvironmentId("env-1")).thenReturn(Optional.of(
                Environment.create("env-1", "默认环境", null, null, "{}", 1L)));
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault-a")))
                .thenReturn(List.of(new VaultReferenceDTO("vault-a", "凭证库")));
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateDeploymentCommand command = new CreateDeploymentCommand("调度器", null, "agent-1", null, "env-1",
                null, null, List.of("vault-a"), null, null, null, false);

        // when
        Deployment deployment = service.create(command);

        // then
        assertEquals("env-1", deployment.environmentId());
        assertEquals(List.of("vault-a"), deployment.vaultIds());
    }

    @Test
    void should_persistPinnedVersion_when_create_given_pinnedVersionExists() {
        // given（显式 pin：校验台账存在性，不解析激活版本）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 3)).thenReturn(Optional.of(buildVersion(3)));
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment deployment = service.create(buildCommand(3, null, false));

        // then
        assertEquals(3, deployment.agentVersion());
        verify(agentVersionAssemblyPort, never()).activeVersionNumber(anyString(), any());
    }

    @Test
    void should_throwNotFound_when_create_given_missingPinnedVersion() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(1L)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 99)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(buildCommand(99, null, false)));
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_create_given_foreignAgent() {
        // given（owner 隔离：非 owner Agent 与不存在不可区分 → 404）
        when(agentDefinitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(buildDefinition(2L)));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.create(buildCommand(null, null, false)));
        verify(deploymentRepository, never()).save(any());
    }

    // ==================== 游标列表 ====================

    private ListDeploymentsQuery buildListQuery(DeploymentStatus status, String agentId, boolean includeArchived,
                                                int limit, String afterId, String beforeId) {
        return new ListDeploymentsQuery(1L, status, agentId, null, null, includeArchived,
                new CursorPageParams(limit, afterId, beforeId));
    }

    @Test
    void should_returnCursorPage_when_list_given_defaultQuery() {
        // given（无游标首页：探针 limit+1 下传，未超页时 has_more=false）
        when(deploymentRepository.findByCursor(eq(1L), any(DeploymentListFilter.class), eq(21)))
                .thenReturn(List.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));

        // when
        CursorPage<Deployment> page = service.list(buildListQuery(null, null, false, 20, null, null));

        // then
        assertEquals(1, page.data().size());
        assertEquals("dep-1", page.data().get(0).deploymentId());
        assertEquals("dep-1", page.firstId());
        assertEquals("dep-1", page.lastId());
        assertFalse(page.hasMore());
    }

    @Test
    void should_assembleFilterWithCursorAnchor_when_list_given_filtersAndAfterId() {
        // given（游标位点：after_id 指向本行解析 (created_at, id) 下界）
        when(deploymentRepository.findByDeploymentId("dep-anchor"))
                .thenReturn(Optional.of(buildTimestampedDeployment("dep-anchor", 50L, 1L)));
        when(deploymentRepository.findByCursor(eq(1L), any(DeploymentListFilter.class), eq(3)))
                .thenReturn(List.of());

        // when
        CursorPage<Deployment> page = service.list(buildListQuery(DeploymentStatus.PAUSED, null, true, 2, "dep-anchor", null));

        // then
        ArgumentCaptor<DeploymentListFilter> captor = ArgumentCaptor.forClass(DeploymentListFilter.class);
        verify(deploymentRepository).findByCursor(eq(1L), captor.capture(), eq(3));
        DeploymentListFilter filter = captor.getValue();
        assertEquals(DeploymentStatus.PAUSED, filter.status());
        assertTrue(filter.includeArchived());
        assertEquals(T_CURSOR, filter.cursorCreatedAt());
        assertEquals(50L, filter.cursorRowId());
        assertFalse(filter.reverse());
        assertTrue(page.data().isEmpty());
        assertNull(page.firstId());
    }

    @Test
    void should_readAscendingAndFlip_when_list_given_beforeId() {
        // given（before 方向：升序读取后应用层翻转回降序）
        when(deploymentRepository.findByDeploymentId("dep-anchor"))
                .thenReturn(Optional.of(buildTimestampedDeployment("dep-anchor", 50L, 1L)));
        Deployment older = buildManualDeployment(DeploymentStatus.ACTIVE, 1L);
        Deployment newer = buildTimestampedDeployment("dep-new", 60L, 1L);
        when(deploymentRepository.findByCursor(eq(1L), any(DeploymentListFilter.class), eq(3)))
                .thenReturn(List.of(older, newer));

        // when
        CursorPage<Deployment> page = service.list(buildListQuery(null, null, false, 2, null, "dep-anchor"));

        // then（升序 [older, newer] 翻转回降序 [newer, older]）
        assertEquals("dep-new", page.data().get(0).deploymentId());
        assertEquals("dep-1", page.data().get(1).deploymentId());
        ArgumentCaptor<DeploymentListFilter> captor = ArgumentCaptor.forClass(DeploymentListFilter.class);
        verify(deploymentRepository).findByCursor(eq(1L), captor.capture(), eq(3));
        assertTrue(captor.getValue().reverse());
    }

    @Test
    void should_trimProbeRow_when_list_given_moreThanLimit() {
        // given（探针行超量：裁切至 limit 且 has_more=true）
        when(deploymentRepository.findByCursor(eq(1L), any(DeploymentListFilter.class), eq(2)))
                .thenReturn(List.of(
                        buildTimestampedDeployment("dep-a", 3L, 1L),
                        buildTimestampedDeployment("dep-b", 2L, 1L)));

        // when
        CursorPage<Deployment> page = service.list(buildListQuery(null, null, false, 1, null, null));

        // then
        assertEquals(1, page.data().size());
        assertTrue(page.hasMore());
        assertEquals("dep-a", page.lastId());
    }

    @Test
    void should_validateAgentOwnership_when_list_given_agentIdFilter() {
        // given（agent_id 过滤先校验 Agent 归属：非 owner → 404）
        when(agentDefinitionRepository.findByAgentId("agent-x")).thenReturn(Optional.of(buildDefinition(2L)));

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.list(buildListQuery(null, "agent-x", false, 20, null, null)));
        verify(deploymentRepository, never()).findByCursor(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void should_throwNotFound_when_list_given_unknownCursor() {
        // given（游标指向不存在的调度器 → 404）
        when(deploymentRepository.findByDeploymentId("dep-ghost")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.list(buildListQuery(null, null, false, 20, "dep-ghost", null)));
    }

    // ==================== merge-patch 更新 ====================

    @Test
    void should_keepUnprovidedFields_when_update_given_nameOnly() {
        // given（仅改名：其余字段回填原值，cron 调度器 nextRunAt 未被重算）
        Deployment current = buildCronDeployment(OffsetDateTime.parse("2026-09-05T09:00:00+08:00"),
                DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(current));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment updated = service.update(updateBuilder("dep-1").name("新名字").build());

        // then
        assertEquals("新名字", updated.name());
        assertEquals(current.description(), updated.description());
        assertEquals("{\"TZ\":\"UTC\"}", updated.environmentVariables());
        assertEquals(List.of("vault-a"), updated.vaultIds());
        assertEquals("{\"biz\":\"x\"}", updated.metadata());
        assertEquals(current.schedule(), updated.schedule());
        assertEquals(current.nextRunAt(), updated.nextRunAt());
    }

    @Test
    void should_validateEnvironmentVariables_when_update_given_environmentVariablesProvided() {
        // given（显式提供环境变量：与创建路径共用同一份形态判定）
        Deployment current = buildCronDeployment(OffsetDateTime.now(ZONE), DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(current));
        doThrow(new IllegalArgumentException("会话环境变量值必须为字符串: COUNT"))
                .when(sessionEnvironmentVariablesValidationPort).validate("{\"COUNT\":5}");

        // when & then（更新口不得成为绕过面：违规即 400 且零落库）
        assertThrows(IllegalArgumentException.class, () -> service.update(
                updateBuilder("dep-1").environmentVariables("{\"COUNT\":5}").build()));
        verify(deploymentRepository, never()).update(any());
    }

    @Test
    void should_skipValidation_when_update_given_environmentVariablesAbsent() {
        // given（仅改名：环境变量缺省不改，不产生新的判定面）
        Deployment current = buildCronDeployment(OffsetDateTime.now(ZONE), DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(current));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.update(updateBuilder("dep-1").name("新名字").build());

        // then（未提供该项即零调用）
        verify(sessionEnvironmentVariablesValidationPort, never()).validate(any());
    }

    @Test
    void should_clearPayloadsWithDefaults_when_update_given_explicitNulls() {
        // given（显式 null 清空：描述→空串、环境变量/首批事件→默认集、保管库→空列表）
        Deployment current = buildCronDeployment(OffsetDateTime.now(ZONE), DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(current));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment updated = service.update(updateBuilder("dep-1")
                .description(null).environmentVariables(null).vaultIds(null).build());

        // then（领域构造器归一清空形态）
        assertEquals("", updated.description());
        assertEquals("{}", updated.environmentVariables());
        assertEquals(List.of(), updated.vaultIds());
        assertEquals("[]", updated.resources());
    }

    @Test
    void should_mergeMetadataAndDropNullKeys_when_update_given_metadataIncrement() {
        // given（浅合并：同名键覆盖、增量中 null 值键删除；既有元数据两键）
        Deployment withMetadata = new Deployment(1L, "dep-1", "手动调度", null, "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{\"a\":\"1\",\"b\":\"2\"}", null, null, null,
                DeploymentStatus.ACTIVE, null, null, null, null, 1L, null, null, null, null, null);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(withMetadata));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when（a 删除、b 覆盖、c 新增）
        Deployment updated = service.update(updateBuilder("dep-1")
                .metadata("{\"a\":null,\"b\":\"9\",\"c\":\"3\"}").build());

        // then（LinkedHashMap 保序：b 原位更新、c 追加、a 移除）
        assertEquals("{\"b\":\"9\",\"c\":\"3\"}", updated.metadata());
    }

    @Test
    void should_clearMetadataEntirely_when_update_given_explicitNullMetadata() {
        // given（metadata 整体显式 null → 清空为 {}）
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildCronDeployment(OffsetDateTime.now(ZONE), DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment updated = service.update(updateBuilder("dep-1").metadata(null).build());

        // then
        assertEquals("{}", updated.metadata());
    }

    @Test
    void should_recomputeNextRunAt_when_update_given_newSchedule() {
        // given（调度变更：按当前时刻以新表达式重算到期时间）
        Deployment current = buildCronDeployment(OffsetDateTime.now(ZONE).minusDays(1),
                DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(current));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment updated = service.update(updateBuilder("dep-1")
                .schedule(new DeploymentSchedule("0 0 12 * * *", null)).build());

        // then（新到期时间落在 12 点且晚于当前时刻）
        assertNotNull(updated.nextRunAt());
        assertEquals(12, updated.nextRunAt().getHour());
        assertTrue(updated.nextRunAt().isAfter(OffsetDateTime.now(ZONE)));
    }

    @Test
    void should_clearScheduleAndNextRunAt_when_update_given_explicitNullSchedule() {
        // given（清空调度：schedule=null 且构造器强制 nextRunAt=null）
        Deployment current = buildCronDeployment(OffsetDateTime.now(ZONE).plusDays(1),
                DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(current));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment updated = service.update(updateBuilder("dep-1").schedule(null).build());

        // then
        assertNull(updated.schedule());
        assertNull(updated.nextRunAt());
        assertFalse(updated.schedulable());
    }

    @Test
    void should_throwNotFound_when_update_given_foreignScheduler() {
        // given（非本人调度器 → 404 且不落库）
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 2L)));

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.update(updateBuilder("dep-1").name("越权").build()));
        verify(deploymentRepository, never()).update(any());
    }

    @Test
    void should_throwNotFound_when_update_given_missingEnvironment() {
        // given（审查修复 F10：更新显式提供不存在的环境 → 404，不整行落库）
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(environmentRepository.findByEnvironmentId("env-ghost")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.update(updateBuilder("dep-1").environmentId("env-ghost").build()));
        verify(deploymentRepository, never()).update(any());
    }

    // ==================== 状态流转 ====================

    @Test
    void should_pauseWithReason_when_pause_given_ownedScheduler() {
        // given
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment paused = service.pause("dep-1", "节假日维护");

        // then
        assertEquals(DeploymentStatus.PAUSED, paused.status());
        assertEquals("节假日维护", paused.pausedReason());
    }

    @Test
    void should_unpauseScheduler_when_unpause_given_pausedScheduler() {
        // given
        Deployment paused = new Deployment(1L, "dep-1", "手动调度", null, "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{}", null, null, null, DeploymentStatus.PAUSED,
                "维护", null, null, null, 1L, null, null, null, null, null);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(paused));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment resumed = service.unpause("dep-1");

        // then（恢复清状态与原因）
        assertEquals(DeploymentStatus.ACTIVE, resumed.status());
        assertNull(resumed.pausedReason());
    }

    @Test
    void should_archiveScheduler_when_archive_given_ownedScheduler() {
        // given
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment archived = service.archive("dep-1");

        // then
        assertTrue(archived.archived());
        assertEquals(DeploymentStatus.PAUSED, archived.status());
    }

    @Test
    void should_throwNotFound_when_pause_given_foreignScheduler() {
        // given
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 2L)));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.pause("dep-1", null));
        verify(deploymentRepository, never()).update(any());
    }

    @Test
    void should_throwIllegalState_when_unpause_given_archivedScheduler() {
        // given
        Deployment archived = buildManualDeployment(DeploymentStatus.PAUSED, 1L).archive();
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(archived));

        // when // then
        assertThrows(IllegalStateException.class, () -> service.unpause("dep-1"));
        verify(deploymentRepository, never()).update(any());
    }

    @Test
    void should_getScheduler_when_get_given_ownedScheduler() {
        // given
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));

        // when // then（详情含已归档不做触发校验）
        assertEquals("dep-1", service.get("dep-1").deploymentId());
    }

    // ==================== 手动运行（run） ====================

    @Test
    void should_fireWithPinnedVersionAndSaveRun_when_run_given_activeScheduler() {
        // given（装配版本一律用创建时固定的 2 版，不动态解析任何版本指针）
        Deployment manual = new Deployment(1L, "dep-1", "手动调度", null, "agent-1", 2, null,
                "{\"K\":\"v\"}", "[{\"type\":\"file\",\"file_id\":\"file_1\"}]", List.of("vault-a"),
                "[{\"type\":\"user_message\",\"text\":\"开工\"}]", "{\"m\":\"1\"}", null, null, null,
                DeploymentStatus.ACTIVE, null, null, null, null, 1L, null, null, null, null, null);
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(manual));
        when(coordinationLeaseApi.tryAcquireFireLease("dep-1")).thenReturn(true);
        when(schedulerSessionApi.launch(any())).thenReturn("sess-1");
        when(deploymentRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.updateLastRun(eq("dep-1"), any(), any(), any())).thenReturn(true);

        // when
        DeploymentTriggerResult result = service.run("dep-1", "跑一下");

        // then（结果三元组 + 打标透传载荷 + 运行记录 running + 最近运行快照刷新）
        assertEquals("dep-1", result.deploymentId());
        assertTrue(result.runId().startsWith(DeploymentRun.RUN_ID_PREFIX));
        assertEquals("sess-1", result.sessionId());
        ArgumentCaptor<SchedulerLaunchDTO> launchCaptor = ArgumentCaptor.forClass(SchedulerLaunchDTO.class);
        verify(schedulerSessionApi).launch(launchCaptor.capture());
        SchedulerLaunchDTO launch = launchCaptor.getValue();
        assertEquals("2", launch.versionNumber());
        assertEquals("manual", launch.triggerType());
        assertEquals("dep-1", launch.triggerId());
        assertEquals("1", launch.ownerId());
        assertEquals("跑一下", launch.input());
        assertEquals("{\"K\":\"v\"}", launch.environmentVariables());
        assertEquals(List.of("vault-a"), launch.vaultIds());
        assertEquals("[{\"type\":\"user_message\",\"text\":\"开工\"}]", launch.initialEvents());
        assertEquals("{\"m\":\"1\"}", launch.metadata());
        ArgumentCaptor<DeploymentRun> runCaptor = ArgumentCaptor.forClass(DeploymentRun.class);
        verify(deploymentRunRepository).save(runCaptor.capture());
        assertEquals(DeploymentTriggerType.MANUAL, runCaptor.getValue().triggerKind());
        assertEquals(DeploymentRunStatus.RUNNING, runCaptor.getValue().status());
        assertEquals("sess-1", runCaptor.getValue().sessionId());
        // 窄列回写最近运行（F08）：不再整行 update 陈旧快照
        verify(deploymentRepository).updateLastRun(eq("dep-1"), eq("sess-1"), eq("running"), any());
        verify(deploymentRepository, never()).update(any());
        verify(coordinationLeaseApi).releaseFireLease("dep-1");
        verify(agentVersionAssemblyPort, never()).activeVersionNumber(anyString(), any());
        verify(agentVersionAssemblyPort, never()).latestVersionNumber(anyString(), any());
    }

    @Test
    void should_throwNotFound_when_run_given_pausedScheduler() {
        // given（暂停期间不可触发：与不存在不可区分 → 404，不进入执行编排）
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.PAUSED, 1L)));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.run("dep-1", null));
        verify(coordinationLeaseApi, never()).tryAcquireFireLease(anyString());
        verify(schedulerSessionApi, never()).launch(any());
    }

    @Test
    void should_throwNotFound_when_run_given_archivedScheduler() {
        // given（归档 = PAUSED + archived_at 双写：不可触发，与不存在不可区分 → 404）
        Deployment archived = buildManualDeployment(DeploymentStatus.ACTIVE, 1L).archive();
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(archived));

        // when // then（不领取租约、不进入执行编排）
        assertThrows(ResourceNotFoundException.class, () -> service.run("dep-1", null));
        verify(coordinationLeaseApi, never()).tryAcquireFireLease(anyString());
        verify(schedulerSessionApi, never()).launch(any());
    }

    @Test
    void should_throwNotFound_when_run_given_foreignScheduler() {
        // given
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 2L)));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.run("dep-1", null));
        verify(schedulerSessionApi, never()).launch(any());
    }

    @Test
    void should_rejectDuplicateFire_when_run_given_fireLeaseHeld() {
        // given（窗口内已有触发在途：协调层防重拒绝）
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(coordinationLeaseApi.tryAcquireFireLease("dep-1")).thenReturn(false);

        // when // then（fire lease 占用 = 重复触发冲突 → 409 语义）
        assertThrows(ResourceConflictException.class, () -> service.run("dep-1", null));
        verify(schedulerSessionApi, never()).launch(any());
        verify(deploymentRunRepository, never()).save(any());
        verify(coordinationLeaseApi, never()).releaseFireLease(anyString());
    }

    @Test
    void should_releaseFireLeaseAndSkipRun_when_run_given_launchFails() {
        // given（启动失败：不落运行记录、不刷新快照，finally 显式释放租约）
        when(deploymentRepository.findByDeploymentId("dep-1")).thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(coordinationLeaseApi.tryAcquireFireLease("dep-1")).thenReturn(true);
        when(schedulerSessionApi.launch(any())).thenThrow(new IllegalStateException("launch failed"));

        // when // then
        assertThrows(IllegalStateException.class, () -> service.run("dep-1", null));
        verify(deploymentRunRepository, never()).save(any());
        verify(deploymentRepository, never()).updateLastRun(anyString(), any(), any(), any());
        verify(coordinationLeaseApi).releaseFireLease("dep-1");
    }

    // ==================== webhook 触发 ====================

    @Test
    void should_triggerWithWebhookKind_when_triggerByWebhookToken_given_validToken() {
        // given（免 JWT：token 已由仓储收敛为「active 且未归档」可触发集合）
        Deployment webhook = new Deployment(1L, "dep-2", "回调调度", null, "agent-1", 2, null,
                "{}", "[]", List.of(), "[]", "{}", null, null, "tok-123",
                DeploymentStatus.ACTIVE, null, null, null, null, 1L, null, null, null, null, null);
        when(deploymentRepository.findByWebhookToken("tok-123")).thenReturn(Optional.of(webhook));
        when(coordinationLeaseApi.tryAcquireFireLease("dep-2")).thenReturn(true);
        when(schedulerSessionApi.launch(any())).thenReturn("sess-2");
        when(deploymentRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.updateLastRun(eq("dep-2"), any(), any(), any())).thenReturn(true);

        // when
        DeploymentTriggerResult result = service.triggerByWebhookToken("tok-123", null);

        // then（新会话打标 webhook + 调度器ID，可追溯）
        assertEquals("sess-2", result.sessionId());
        ArgumentCaptor<SchedulerLaunchDTO> captor = ArgumentCaptor.forClass(SchedulerLaunchDTO.class);
        verify(schedulerSessionApi).launch(captor.capture());
        assertEquals("webhook", captor.getValue().triggerType());
        assertEquals("dep-2", captor.getValue().triggerId());
        ArgumentCaptor<DeploymentRun> runCaptor = ArgumentCaptor.forClass(DeploymentRun.class);
        verify(deploymentRunRepository).save(runCaptor.capture());
        assertEquals(DeploymentTriggerType.WEBHOOK, runCaptor.getValue().triggerKind());
    }

    @Test
    void should_throwNotFound_when_triggerByWebhookToken_given_unknownToken() {
        // given（暂停 / 已归档 / 未知 token 与不存在不可区分 → 404）
        when(deploymentRepository.findByWebhookToken("bad-token")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.triggerByWebhookToken("bad-token", null));
        verify(schedulerSessionApi, never()).launch(any());
    }

    // ==================== 运行记录游标查询 ====================

    private ListDeploymentRunsQuery buildRunsQuery(String deploymentId, OffsetDateTime after,
                                                   int limit, String afterId, String beforeId) {
        return new ListDeploymentRunsQuery(deploymentId, 1L, after, null,
                new CursorPageParams(limit, afterId, beforeId));
    }

    @Test
    void should_delegateDeploymentScope_when_listRuns_given_deploymentScopedQuery() {
        // given（单调度器作用域：先校验归属，过滤条件不带 owner 子查询）
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRunRepository.findByCursor(any(DeploymentRunsFilter.class), eq(21)))
                .thenReturn(List.of(buildRun(9L, "drun_a", "dep-1")));

        // when
        CursorPage<DeploymentRun> page = service.listRuns(buildRunsQuery("dep-1", null, 20, null, null));

        // then
        ArgumentCaptor<DeploymentRunsFilter> captor = ArgumentCaptor.forClass(DeploymentRunsFilter.class);
        verify(deploymentRunRepository).findByCursor(captor.capture(), eq(21));
        assertEquals("dep-1", captor.getValue().deploymentId());
        assertNull(captor.getValue().ownerId());
        assertEquals(1, page.data().size());
        assertEquals("drun_a", page.firstId());
    }

    @Test
    void should_delegateOwnerScope_when_listRuns_given_globalQuery() {
        // given（全局作用域：无 deploymentId 时经归属子查询过滤 + 时间下界透传）
        when(deploymentRunRepository.findByCursor(any(DeploymentRunsFilter.class), eq(6)))
                .thenReturn(List.of());

        // when
        service.listRuns(buildRunsQuery(null, T_CURSOR, 5, null, null));

        // then
        ArgumentCaptor<DeploymentRunsFilter> captor = ArgumentCaptor.forClass(DeploymentRunsFilter.class);
        verify(deploymentRunRepository).findByCursor(captor.capture(), eq(6));
        assertNull(captor.getValue().deploymentId());
        assertEquals(1L, captor.getValue().ownerId());
        assertEquals(T_CURSOR, captor.getValue().createdAfter());
    }

    @Test
    void should_resolveRunCursorAnchor_when_listRuns_given_afterRunId() {
        // given（游标为运行记录业务ID：经归属校验后解析行位点）
        when(deploymentRunRepository.findByRunId("drun_cursor"))
                .thenReturn(Optional.of(buildRun(42L, "drun_cursor", "dep-1")));
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRunRepository.findByCursor(any(DeploymentRunsFilter.class), eq(3)))
                .thenReturn(List.of());

        // when
        service.listRuns(buildRunsQuery("dep-1", null, 2, "drun_cursor", null));

        // then
        ArgumentCaptor<DeploymentRunsFilter> captor = ArgumentCaptor.forClass(DeploymentRunsFilter.class);
        verify(deploymentRunRepository).findByCursor(captor.capture(), eq(3));
        assertEquals(T_CURSOR, captor.getValue().cursorCreatedAt());
        assertEquals(42L, captor.getValue().cursorRowId());
        assertFalse(captor.getValue().reverse());
    }

    @Test
    void should_throwNotFound_when_listRuns_given_cursorOfForeignRun() {
        // given（游标运行记录属他人调度器 → 404 不泄露存在性）
        when(deploymentRunRepository.findByRunId("drun_foreign"))
                .thenReturn(Optional.of(buildRun(42L, "drun_foreign", "dep-x")));
        when(deploymentRepository.findByDeploymentId("dep-x"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 2L)));

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.listRuns(buildRunsQuery(null, null, 20, "drun_foreign", null)));
    }

    @Test
    void should_returnRun_when_getRun_given_runOfSameDeployment() {
        // given
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRunRepository.findByRunId("drun_a")).thenReturn(Optional.of(buildRun(9L, "drun_a", "dep-1")));

        // when // then
        assertEquals("drun_a", service.getRun("dep-1", "drun_a").runId());
    }

    @Test
    void should_throwNotFound_when_getRun_given_runOfAnotherDeployment() {
        // given（记录不属于路径中的调度器 → 404）
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));
        when(deploymentRunRepository.findByRunId("drun_other"))
                .thenReturn(Optional.of(buildRun(9L, "drun_other", "dep-2")));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getRun("dep-1", "drun_other"));
    }

    @Test
    void should_returnRun_when_getRunGlobal_given_ownedRun() {
        // given（全局详情：经所属调度器反查归属）
        when(deploymentRunRepository.findByRunId("drun_a")).thenReturn(Optional.of(buildRun(9L, "drun_a", "dep-1")));
        when(deploymentRepository.findByDeploymentId("dep-1"))
                .thenReturn(Optional.of(buildManualDeployment(DeploymentStatus.ACTIVE, 1L)));

        // when // then
        assertEquals("drun_a", service.getRunGlobal("drun_a").runId());
    }

    @Test
    void should_throwNotFound_when_getRunGlobal_given_unknownRun() {
        // given
        when(deploymentRunRepository.findByRunId("drun_ghost")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getRunGlobal("drun_ghost"));
    }

    // ==================== cron 轮询领取 ====================

    @Test
    void should_claimAdvanceAndFireCron_when_triggerDueDeployments_given_casSuccess() {
        // given（到期候选：nextRunAt 已过期；CAS 领取成功后按当前时刻重算下一槽位并触发）
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");
        OffsetDateTime expired = now.minusHours(1);
        OffsetDateTime advanced = now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        Deployment due = buildCronDeployment(expired, DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findDue(now, 20)).thenReturn(List.of(due));
        when(deploymentRepository.advanceNextRun("dep-1", expired, advanced)).thenReturn(true);
        when(coordinationLeaseApi.tryAcquireFireLease("dep-1")).thenReturn(true);
        when(schedulerSessionApi.launch(any())).thenReturn("sess-cron");
        when(deploymentRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.updateLastRun(eq("dep-1"), any(), any(), any())).thenReturn(true);

        // when
        int fired = service.triggerDueDeployments(now, 20);

        // then（领取成功计数 + cron 打标触发）
        assertEquals(1, fired);
        ArgumentCaptor<SchedulerLaunchDTO> captor = ArgumentCaptor.forClass(SchedulerLaunchDTO.class);
        verify(schedulerSessionApi).launch(captor.capture());
        assertEquals("cron", captor.getValue().triggerType());
        assertEquals("dep-1", captor.getValue().triggerId());
        assertEquals("2", captor.getValue().versionNumber());
        assertEquals(List.of("vault-a"), captor.getValue().vaultIds());
        // 最近运行经窄列回写（F08）：到期时间已由 CAS advanceNextRun 推进，不再整行回写
        verify(deploymentRepository).updateLastRun(eq("dep-1"), eq("sess-cron"), eq("running"), any());
        verify(deploymentRepository, never()).update(any());
    }

    @Test
    void should_skipCandidate_when_triggerDueDeployments_given_casFailure() {
        // given（CAS 失败=已被其他实例领取，本实例静默跳过）
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");
        OffsetDateTime expired = now.minusHours(1);
        Deployment due = buildCronDeployment(expired, DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findDue(now, 20)).thenReturn(List.of(due));
        when(deploymentRepository.advanceNextRun(eq("dep-1"), eq(expired), any())).thenReturn(false);

        // when
        int fired = service.triggerDueDeployments(now, 20);

        // then
        assertEquals(0, fired);
        verify(schedulerSessionApi, never()).launch(any());
        verify(deploymentRepository, never()).update(any());
    }

    @Test
    void should_swallowFireError_when_triggerDueDeployments_given_triggerThrows() {
        // given（misfire 语义：窗口已领取，触发异常仅记日志，该窗口跳过不补，不影响轮询）
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");
        OffsetDateTime expired = now.minusHours(1);
        Deployment due = buildCronDeployment(expired, DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findDue(now, 20)).thenReturn(List.of(due));
        when(deploymentRepository.advanceNextRun(eq("dep-1"), eq(expired), any())).thenReturn(true);
        when(coordinationLeaseApi.tryAcquireFireLease("dep-1")).thenReturn(false);

        // when（fire lease 占用 → fire 抛业务异常，被吞掉）
        int fired = service.triggerDueDeployments(now, 20);

        // then
        assertEquals(0, fired);
        verify(schedulerSessionApi, never()).launch(any());
    }

    @Test
    void should_returnZero_when_triggerDueDeployments_given_noCandidate() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        when(deploymentRepository.findDue(now, 5)).thenReturn(List.of());

        // when // then
        assertEquals(0, service.triggerDueDeployments(now, 5));
        verify(deploymentRepository, never()).advanceNextRun(anyString(), any(), any());
    }

    @Test
    void should_ignoreStaleCandidate_when_triggerDueDeployments_given_nullScheduleOrNextRunAt() {
        // given（粗筛脏数据防御：schedule 或 nextRunAt 缺失的候选直接跳过，不发起 CAS）
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        Deployment stale = buildManualDeployment(DeploymentStatus.ACTIVE, 1L);
        when(deploymentRepository.findDue(now, 10)).thenReturn(List.of(stale));

        // when
        int fired = service.triggerDueDeployments(now, 10);

        // then
        assertEquals(0, fired);
        verify(deploymentRepository, never()).advanceNextRun(anyString(), any(), any());
        assertFalse(stale.schedulable());
    }

    // ==================== 按触发会话回写运行终态（write-back-deployment-run-terminal-state） ====================

    @Test
    void should_casCompleteAndRefreshLastStatus_when_completeRunByTriggerSession_given_runningRunExists() {
        // given：该触发会话存在 running 运行行，CAS 终态化命中
        wireExecuteWithoutResult();
        DeploymentRun running = DeploymentRun.start("drun_1", "dep-1", "sess-1", DeploymentTriggerType.CRON);
        when(deploymentRunRepository.findRunningBySessionId("sess-1")).thenReturn(Optional.of(running));
        when(deploymentRunRepository.casCompleteBySessionId(eq("sess-1"), eq(DeploymentRunStatus.SUCCEEDED), any()))
                .thenReturn(true);

        // when
        service.completeRunByTriggerSession("sess-1", "succeeded");

        // then：CAS 收窄列 + 同事务窄列刷新调度器 last_status（携 deploymentId 定位，值为契约小写终态）
        verify(deploymentRunRepository).casCompleteBySessionId(eq("sess-1"), eq(DeploymentRunStatus.SUCCEEDED), any());
        verify(deploymentRepository).refreshLastStatusForTrigger("dep-1", "sess-1", "succeeded");
    }

    @Test
    void should_doNothing_when_completeRunByTriggerSession_given_noRunningRun() {
        // given（非调度会话 / episode 已收口：无 running 运行行）
        wireExecuteWithoutResult();
        when(deploymentRunRepository.findRunningBySessionId("sess-1")).thenReturn(Optional.empty());

        // when
        service.completeRunByTriggerSession("sess-1", "succeeded");

        // then：幂等空操作——不触及 CAS 与快照
        verify(deploymentRunRepository, never()).casCompleteBySessionId(anyString(), any(), any());
        verify(deploymentRepository, never()).refreshLastStatusForTrigger(anyString(), anyString(), anyString());
    }

    @Test
    void should_skipLastStatusRefresh_when_completeRunByTriggerSession_given_casMissedByConcurrentCompletion() {
        // given：定位到 running 行但 CAS 未命中（并发方抢先终态化 / 已被新触发接管）
        wireExecuteWithoutResult();
        DeploymentRun running = DeploymentRun.start("drun_1", "dep-1", "sess-1", DeploymentTriggerType.CRON);
        when(deploymentRunRepository.findRunningBySessionId("sess-1")).thenReturn(Optional.of(running));
        when(deploymentRunRepository.casCompleteBySessionId(eq("sess-1"), eq(DeploymentRunStatus.FAILED), any()))
                .thenReturn(false);

        // when
        service.completeRunByTriggerSession("sess-1", "failed");

        // then：迟到回写不得覆盖新触发的 last_status 快照
        verify(deploymentRepository, never()).refreshLastStatusForTrigger(anyString(), anyString(), anyString());
    }

    @Test
    void should_throwIllegalArgument_when_completeRunByTriggerSession_given_runningOrUnknownOutcome() {
        // given：outcome 校验先于事务（running 与未知值均拒绝）

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.completeRunByTriggerSession("sess-1", "running"));
        assertThrows(IllegalArgumentException.class,
                () -> service.completeRunByTriggerSession("sess-1", "bogus"));

        // then：非法值不进入事务编排
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    /** executeWithoutResult 同步跑回调桩（回写路径专用）。 */
    private void wireExecuteWithoutResult() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }
}
