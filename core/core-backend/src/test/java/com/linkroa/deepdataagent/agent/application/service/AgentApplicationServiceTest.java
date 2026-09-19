package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateAgentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentListFilter;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.domain.service.AgentVersionDomainService;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.skill.api.SkillAssetApi;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 创建 / 发布 / 归档 / 删除应用服务单测（含并发发布串行化语义验证）
 */
@ExtendWith(MockitoExtension.class)
class AgentApplicationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 钉版技能版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";
    /** 模型引用字符串简写形态。 */
    private static final String MODEL_SHORTHAND = "\"ultimate\"";

    @Mock private AgentDefinitionRepository agentDefinitionRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private ModelProfileRepository modelProfileRepository;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SkillAssetApi skillAssetApi;

    private AgentApplicationService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        AgentVersionDomainService versionDomainService = new AgentVersionDomainService();
        service = new AgentApplicationService();
        ReflectionTestUtils.setField(service, "agentDefinitionRepository", agentDefinitionRepository);
        ReflectionTestUtils.setField(service, "agentVersionRepository", agentVersionRepository);
        ReflectionTestUtils.setField(service, "modelProfileRepository", modelProfileRepository);
        ReflectionTestUtils.setField(service, "modelCatalogService", modelCatalogService);
        ReflectionTestUtils.setField(service, "skillAssetApi", skillAssetApi);
        ReflectionTestUtils.setField(service, "versionDomainService", versionDomainService);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private AgentDefinition buildDefinition(String agentId, String name, int latestVersion, boolean archived) {
        OffsetDateTime timestamp = OffsetDateTime.now(ZONE);
        return AgentDefinition.restore(
                1L, agentId, name, null, archived ? timestamp : null, latestVersion, latestVersion, 1L,
                timestamp, timestamp, null, null);
    }

    private CreateAgentCommand buildCreateCommand(String name) {
        return new CreateAgentCommand(name, null, "你是助手", MODEL_SHORTHAND,
                null, null, null, null, null);
    }

    @Test
    void should_createDefinitionWithV1_when_createAgent_given_validCommand() {
        // given（创建即首版：definition + version=1 同事务落库）
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentVersionRepository.findMaxVersionNumber(anyString())).thenReturn(0);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        AgentDefinition created = service.createAgent(buildCreateCommand("销售助手"));

        // then
        assertTrue(created.agentId().startsWith("agent_"));
        assertEquals("销售助手", created.name());
        assertEquals(1, created.latestVersion());
        assertEquals(1, created.activeVersion());
        verify(agentVersionRepository).save(any());
    }

    @Test
    void should_allowDuplicateName_when_createAgent_given_sameNameAsExisting() {
        // given（名称不再做 owner 内唯一约束：同名可共存）
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentVersionRepository.findMaxVersionNumber(anyString())).thenReturn(0);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        AgentDefinition created = service.createAgent(buildCreateCommand("销售助手"));

        // then
        assertEquals("销售助手", created.name());
        verify(agentDefinitionRepository).save(any());
    }

    @Test
    void should_throwInvalidArgument_when_createAgent_given_modelNotInCatalog() {
        // given（模型引用不在目录 → 400 拒绝创建，不落任何数据）
        org.mockito.Mockito.doThrow(new IllegalArgumentException("模型目录中不存在该模型: ultimate"))
                .when(modelCatalogService).validateModel(any());

        // when / then
        assertThrows(IllegalArgumentException.class, () -> service.createAgent(buildCreateCommand("销售助手")));
        verify(agentDefinitionRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_createAgent_given_mappedProfileMissing() {
        // given（内部供应商映射命中但配置不存在 → 404，不落任何数据）
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn("profile-1");
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.createAgent(buildCreateCommand("销售助手")));
        verify(agentDefinitionRepository, never()).save(any());
    }

    @Test
    void should_publishNewVersionWithIncrementedNumber_when_publishVersion_given_existingVersions() {
        // given
        AgentDefinition definition = buildDefinition("agent-1", "销售助手", 2, false);
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(definition));
        when(agentVersionRepository.findMaxVersionNumber("agent-1")).thenReturn(2);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v3", null, "新版系统提示", MODEL_SHORTHAND, null, null, null, null, null);

        // when
        AgentVersion published = service.publishVersion(command);

        // then
        assertEquals(3, published.versionNumber());
        assertEquals("新版系统提示", published.systemPrompt());
        verify(agentDefinitionRepository).update(any());
    }

    @Test
    void should_publish_when_publishVersion_given_existingSkillMount() {
        // given（技能引用配方随版本快照持久化；epoch 版本键钉版校验）
        AgentDefinition definition = buildDefinition("agent-1", "销售助手", 1, false);
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(definition));
        when(agentVersionRepository.findMaxVersionNumber("agent-1")).thenReturn(1);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // 发布期技能绑定校验通过（custom 技能资产 + epoch 版本存在）
        when(skillAssetApi.exists("skill_1", 1L)).thenReturn(true);
        when(skillAssetApi.versionExists("skill_1", EPOCH, 1L)).thenReturn(true);

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND, null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_1\",\"version\":\"1759178010641129\"}]", null, null);

        // when
        AgentVersion published = service.publishVersion(command);

        // then
        assertEquals(2, published.versionNumber());
        assertTrue(published.skillsJson().contains("skill_1"));
        assertTrue(published.skillsJson().contains(EPOCH));
    }

    @Test
    void should_publishDynamicBinding_when_publishVersion_given_skillVersionOmitted() {
        // given（动态版绑定：省略版本键不落钉版，发版时不校验具体版本）
        AgentDefinition definition = buildDefinition("agent-1", "销售助手", 1, false);
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(definition));
        when(agentVersionRepository.findMaxVersionNumber("agent-1")).thenReturn(1);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillAssetApi.exists("skill_1", 1L)).thenReturn(true);

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND, null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_1\"}]", null, null);

        // when
        AgentVersion published = service.publishVersion(command);

        // then（动态版不落 version 键、不发起版本存在性校验）
        assertEquals("[{\"type\":\"custom\",\"skill_id\":\"skill_1\"}]", published.skillsJson());
        verify(skillAssetApi, never()).versionExists(anyString(), anyString(), any());
    }

    @Test
    void should_throwConflict_when_publishVersion_given_archivedAgent() {
        // given
        AgentDefinition archived = buildDefinition("agent-1", "销售助手", 1, true);
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(archived));

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND, null, null, null, null, null);

        // when / then
        assertThrows(ResourceConflictException.class, () -> service.publishVersion(command));
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_publishVersion_given_missingAgent() {
        // given
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.empty());

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND, null, null, null, null, null);

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.publishVersion(command));
    }

    @Test
    void should_throwNotFound_when_publishVersion_given_missingSkillReference() {
        // given（发布期技能绑定存在性校验：custom 技能资产不存在 → 404，拒绝发布）
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(skillAssetApi.exists("skill_ghost", 1L)).thenReturn(false);

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND, null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_ghost\"}]", null, null);

        // when / then（无任何数据变更）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> service.publishVersion(command));
        assertEquals("技能引用不存在: skill_ghost", ex.getMessage());
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_throwInvalidArgument_when_publishVersion_given_mcpToolsetReferencingUndeclaredServer() {
        // given（mcp_toolset 引用未声明的 MCP 服务器：跨列表结构校验须在开事务前拒绝发布）
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND,
                "[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"ghost_server\"}]", null, null, null, null);

        // when / then（无任何数据变更：事务未开启、未锁行、未落版本）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.publishVersion(command));
        assertTrue(ex.getMessage().contains("未声明的 MCP 服务器"));
        verify(transactionTemplate, never()).execute(any());
        verify(agentDefinitionRepository, never()).findByAgentIdForUpdate(anyString());
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_throwInvalidArgument_when_publishVersion_given_nonEmptyMultiagent() {
        // given（multiagent 本期不实现：非空提交 400，事务未开启）
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", MODEL_SHORTHAND, null, null, null,
                "{\"type\":\"coordinator\"}", null);

        // when / then
        assertThrows(IllegalArgumentException.class, () -> service.publishVersion(command));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void should_throwNotFound_when_getAgent_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-missing")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.getAgent("agent-missing"));
    }

    @Test
    void should_archiveAgent_when_archiveAgent_given_existingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 1, false)));

        // when
        service.archiveAgent("agent-1");

        // then（归档仅写 archived_at 时间戳）
        verify(agentDefinitionRepository).updateArchivedAt(eq("agent-1"), any());
    }

    @Test
    void should_skipUpdate_when_archiveAgent_given_alreadyArchivedAgent() {
        // given（幂等：已归档不刷新首次归档时间）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 1, true)));

        // when
        service.archiveAgent("agent-1");

        // then
        verify(agentDefinitionRepository, never()).updateArchivedAt(eq("agent-1"), any());
    }

    @Test
    void should_deleteAgentAndVersions_when_deleteAgent_given_existingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 1, false)));

        // when
        service.deleteAgent("agent-1");

        // then
        verify(agentDefinitionRepository).deleteByAgentId("agent-1");
        verify(agentVersionRepository).deleteByAgentId("agent-1");
    }

    @Test
    void should_throwNotFound_when_deleteAgent_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-missing")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteAgent("agent-missing"));
    }

    @Test
    void should_generateDistinctVersionNumbers_when_publishVersion_given_concurrentPublish() {
        // given
        // 模拟两个并发发布：第二个发布时 MAX 为 1（第一个已落库），会同源递增出互不相同的版本号
        AgentDefinition definition = buildDefinition("agent-1", "销售助手", 1, false);
        when(modelCatalogService.resolveProfileId("ultimate", 1L)).thenReturn(null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(definition));
        when(agentVersionRepository.findMaxVersionNumber("agent-1"))
                .thenReturn(0)
                .thenReturn(1);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v", null, "system", MODEL_SHORTHAND, null, null, null, null, null);

        // when
        AgentVersion first = service.publishVersion(command);
        AgentVersion second = service.publishVersion(command);

        // then
        assertEquals(1, first.versionNumber());
        assertEquals(2, second.versionNumber());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.versionNumber(), second.versionNumber());
    }

    // ==================== 激活 / 回滚版本 ====================

    @Test
    void should_activateVersion_when_activateVersion_given_versionWithinLedger() {
        // given（锁行读取归属 Agent，激活第 2 版）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 3, false)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2))
                .thenReturn(Optional.of(AgentVersion.create("v-2", "agent-1", 2, "v2", null, "sys",
                        "mp-1", null, null, null, null, null, null)));
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(AgentDefinition.restore(1L, "agent-1", "销售助手", null,
                        null, 3, 2, 1L, null, null, null, null)));

        // when
        AgentDefinition activated = service.activateVersion("agent-1", 2);

        // then（单列更新激活指针后回读最新态）
        assertEquals(2, activated.activeVersion());
        assertEquals(3, activated.latestVersion());
        verify(agentDefinitionRepository).updateActiveVersion("agent-1", 2);
    }

    @Test
    void should_throwInvalidArgument_when_activateVersion_given_outOfLedgerRange() {
        // given（激活号超过最新台账号 → 领域不变量拒绝，不发起更新）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 3, false)));

        // when / then
        assertThrows(IllegalArgumentException.class, () -> service.activateVersion("agent-1", 4));
        verify(agentDefinitionRepository, never()).updateActiveVersion(anyString(), anyInt());
    }

    @Test
    void should_throwNotFound_when_activateVersion_given_missingVersionRow() {
        // given（区间内台账缺行 → 404，不发起更新）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 3, false)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.activateVersion("agent-1", 2));
        verify(agentDefinitionRepository, never()).updateActiveVersion(anyString(), anyInt());
    }

    @Test
    void should_throwConflict_when_activateVersion_given_archivedAgent() {
        // given（已归档 Agent 拒绝激活）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 3, true)));

        // when / then
        assertThrows(ResourceConflictException.class, () -> service.activateVersion("agent-1", 2));
        verify(agentDefinitionRepository, never()).updateActiveVersion(anyString(), anyInt());
    }

    @Test
    void should_throwNotFound_when_activateVersion_given_foreignAgent() {
        // given（非 owner 与不存在不可区分 → 404）
        AgentDefinition foreign = AgentDefinition.restore(1L, "agent-1", "销售助手", null,
                null, 3, 3, 2L, null, null, null, null);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(foreign));

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.activateVersion("agent-1", 2));
        verify(agentDefinitionRepository, never()).updateActiveVersion(anyString(), anyInt());
    }

    // ==================== 游标列表 / 版本查询 / OCC 更新（6.2） ====================

    private ListAgentQuery buildListQuery(String status, CursorPageParams cursor) {
        return new ListAgentQuery(null, status, null, null, null, cursor);
    }

    @Test
    void should_returnCursorPageWithHasMore_when_listAgents_given_probeRowReturned() {
        // given（limit=2，仓储按 limit+1 探针返回 3 行 → has_more=true 且当页截断为 2 行）
        when(agentDefinitionRepository.findByCursor(eq(1L), any(), eq(3))).thenReturn(List.of(
                buildDefinition("agent-c", "C", 1, false),
                buildDefinition("agent-b", "B", 1, false),
                buildDefinition("agent-a", "A", 1, false)));

        // when
        CursorPage<AgentDefinition> page = service.listAgents(
                buildListQuery(null, new CursorPageParams(2, null, null)));

        // then（游标 ID 取当页首尾；缺省状态过滤 = 仅未归档）
        assertEquals(2, page.data().size());
        assertEquals("agent-c", page.firstId());
        assertEquals("agent-b", page.lastId());
        assertTrue(page.hasMore());
        ArgumentCaptor<AgentListFilter> captor = ArgumentCaptor.forClass(AgentListFilter.class);
        verify(agentDefinitionRepository).findByCursor(eq(1L), captor.capture(), eq(3));
        assertEquals(Boolean.FALSE, captor.getValue().archived());
        assertNull(captor.getValue().cursorAgentId());
    }

    @Test
    void should_reverseRowsWithArchivedFilter_when_listAgents_given_beforeIdCursor() {
        // given（status=archived + before_id：升序读取更新侧行位后由应用层翻转回降序）
        AgentDefinition at = buildDefinition("agent-b", "B", 1, true);
        when(agentDefinitionRepository.findByAgentId("agent-b")).thenReturn(Optional.of(at));
        when(agentDefinitionRepository.findByCursor(eq(1L), any(), eq(3))).thenReturn(List.of(
                buildDefinition("agent-d", "D", 1, true),
                buildDefinition("agent-c", "C", 1, true)));

        // when
        CursorPage<AgentDefinition> page = service.listAgents(
                buildListQuery("archived", new CursorPageParams(2, null, "agent-b")));

        // then
        assertEquals(List.of("agent-c", "agent-d"),
                page.data().stream().map(AgentDefinition::agentId).toList());
        assertFalse(page.hasMore());
        ArgumentCaptor<AgentListFilter> captor = ArgumentCaptor.forClass(AgentListFilter.class);
        verify(agentDefinitionRepository).findByCursor(eq(1L), captor.capture(), eq(3));
        assertEquals(Boolean.TRUE, captor.getValue().archived());
        assertTrue(captor.getValue().reverse());
        assertEquals("agent-b", captor.getValue().cursorAgentId());
        assertEquals(at.createdAt(), captor.getValue().cursorCreatedAt());
    }

    @Test
    void should_throwNotFound_when_listAgents_given_missingCursorAgent() {
        // given（after_id 游标指向不存在的 Agent → 404）
        when(agentDefinitionRepository.findByAgentId("agent_ghost")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.listAgents(
                buildListQuery(null, new CursorPageParams(20, "agent_ghost", null))));
    }

    @Test
    void should_returnLatestSnapshot_when_getVersion_given_nullVersionNumber() {
        // given（省略 version 参数 → 解析最新版本号取快照）
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 2, false)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.of(
                AgentVersion.create("v-2", "agent-1", 2, "v2", null, "sys",
                        "mp-1", null, null, null, null, null, null)));

        // when
        AgentVersion result = service.getVersion("agent-1", null);

        // then
        assertEquals(2, result.versionNumber());
    }

    @Test
    void should_returnNull_when_getVersion_given_noPublishedVersion() {
        // given（latest_version=0 → 无任何版本快照）
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 0, false)));

        // when / then
        assertNull(service.getVersion("agent-1", null));
    }

    @Test
    void should_throwNotFound_when_getVersion_given_absentVersionNumber() {
        // given（显式指定 ?version=5 但台账缺行 → 404）
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 2, false)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 5)).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.getVersion("agent-1", 5));
    }

    @Test
    void should_resolveVersionNumberCursor_when_listVersionPage_given_afterId() {
        // given（公开契约：游标为版本号字符串，"3" 即 keyset 位点 3）
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 5, false)));
        when(agentVersionRepository.findByAgentIdCursor("agent-1", 3, false, 3)).thenReturn(List.of(
                AgentVersion.create("v-2", "agent-1", 2, "v2", null, "sys",
                        "mp-1", null, null, null, null, null, null),
                AgentVersion.create("v-1", "agent-1", 1, "v1", null, "sys",
                        "mp-1", null, null, null, null, null, null)));

        // when
        CursorPage<AgentVersion> page = service.listVersionPage("agent-1", new CursorPageParams(2, "3", null));

        // then（发布号降序；first_id / last_id 为版本号字符串；无余量 next_page 缺省）
        assertEquals(List.of(2, 1), page.data().stream().map(AgentVersion::versionNumber).toList());
        assertEquals("2", page.firstId());
        assertEquals("1", page.lastId());
        assertFalse(page.hasMore());
        assertNull(page.nextPage());
        // then（游标解析不再经 version_id 反查）
        verify(agentVersionRepository, never()).findByVersionId(anyString());
    }

    @Test
    void should_emitNextPageCursor_when_listVersionPage_given_probeRemainder() {
        // given（探针余量 → has_more 真；契约要求 next_page = last_id）
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 5, false)));
        when(agentVersionRepository.findByAgentIdCursor("agent-1", null, false, 3)).thenReturn(List.of(
                AgentVersion.create("v-3", "agent-1", 3, "v3", null, "sys",
                        "mp-1", null, null, null, null, null, null),
                AgentVersion.create("v-2", "agent-1", 2, "v2", null, "sys",
                        "mp-1", null, null, null, null, null, null),
                AgentVersion.create("v-1", "agent-1", 1, "v1", null, "sys",
                        "mp-1", null, null, null, null, null, null)));

        // when
        CursorPage<AgentVersion> page = service.listVersionPage("agent-1", new CursorPageParams(2, null, null));

        // then（当页头两条，next_page 回传当页 last_id）
        assertEquals(List.of(3, 2), page.data().stream().map(AgentVersion::versionNumber).toList());
        assertEquals("3", page.firstId());
        assertEquals("2", page.lastId());
        assertTrue(page.hasMore());
        assertEquals("2", page.nextPage());
    }

    @Test
    void should_throwInvalidArgument_when_listVersionPage_given_nonPositiveIntegerCursor() {
        // given（契约：游标非正整数 → 400 invalid_request_error）
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 5, false)));

        // when / then
        assertThrows(IllegalArgumentException.class, () -> service.listVersionPage(
                "agent-1", new CursorPageParams(20, "v-x", null)));
        assertThrows(IllegalArgumentException.class, () -> service.listVersionPage(
                "agent-1", new CursorPageParams(20, "0", null)));
    }

    @Test
    void should_throwConflict_when_updateAgent_given_staleVersion() {
        // given（请求携带 version=2，当前最新 3 → OCC 409 conflict_error）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 3, false)));

        // when / then（无任何版本写入）
        assertThrows(ResourceConflictException.class, () -> service.updateAgent(
                new UpdateAgentCommand("agent-1", "新名称", null, 2)));
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_publishSnapshotAndBumpLatest_when_updateAgent_given_matchingVersion() {
        // given（version=2 与当前最新一致；缺省描述沿用现值，复制最新快照全量配置发布 v3）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 2, false)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.of(
                AgentVersion.create("v-2", "agent-1", 2, "v2", null, "sys",
                        "mp-1", MODEL_SHORTHAND, null, null, null, null, "{\"team\":\"data\"}")));
        when(agentVersionRepository.findMaxVersionNumber("agent-1")).thenReturn(2);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        AgentDefinition updated = service.updateAgent(new UpdateAgentCommand("agent-1", "新名称", null, 2));

        // then（改名生效并产生新版本快照，latest_version 同步递增）
        assertEquals("新名称", updated.name());
        assertEquals(3, updated.latestVersion());
        ArgumentCaptor<AgentVersion> captor = ArgumentCaptor.forClass(AgentVersion.class);
        verify(agentVersionRepository).save(captor.capture());
        assertEquals(3, captor.getValue().versionNumber());
        assertEquals("新名称", captor.getValue().name());
        assertEquals("sys", captor.getValue().systemPrompt());
        assertEquals("{\"team\":\"data\"}", captor.getValue().metadataJson());
    }

    @Test
    void should_throwConflict_when_updateAgent_given_archivedAgent() {
        // given（归档 Agent 拒绝更新，与发布同口径 409）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 2, true)));

        // when / then
        assertThrows(ResourceConflictException.class, () -> service.updateAgent(
                new UpdateAgentCommand("agent-1", "新名称", null, 2)));
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_updateAgent_given_missingLatestSnapshot() {
        // given（latest_version 指向的台账缺行 → 404，不落任何版本）
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 2, false)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.updateAgent(
                new UpdateAgentCommand("agent-1", "新名称", null, 2)));
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_keepSnapshotFields_when_updateAgent_given_nameOnlyChange() {
        // given（仅改名：非改名属性整体复制最新快照；定义当前描述即「旧描述」）
        OffsetDateTime timestamp = OffsetDateTime.now(ZONE);
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1"))
                .thenReturn(Optional.of(AgentDefinition.restore(
                        1L, "agent-1", "销售助手", "旧描述", null, 2, 2, 1L,
                        timestamp, timestamp, null, null)));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.of(
                AgentVersion.create("v-2", "agent-1", 2, "v2", "旧描述", "sys", "mp-1",
                        MODEL_SHORTHAND, "[{\"type\":\"custom\",\"skill_id\":\"skill_1\"}]",
                        "[{\"name\":\"github\",\"type\":\"url\",\"url\":\"https://mcp.example.com\"}]",
                        "[{\"type\":\"custom\",\"skill_id\":\"skill_1\"}]", null, null)));
        when(agentVersionRepository.findMaxVersionNumber("agent-1")).thenReturn(2);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.updateAgent(new UpdateAgentCommand("agent-1", "新名称", null, 2));

        // then（新快照沿用旧描述与全量配置）
        ArgumentCaptor<AgentVersion> captor = ArgumentCaptor.forClass(AgentVersion.class);
        verify(agentVersionRepository).save(captor.capture());
        assertEquals("旧描述", captor.getValue().description());
        assertEquals("[{\"type\":\"custom\",\"skill_id\":\"skill_1\"}]", captor.getValue().toolsJson());
        assertNotNull(captor.getValue().mcpServersJson());
    }
}