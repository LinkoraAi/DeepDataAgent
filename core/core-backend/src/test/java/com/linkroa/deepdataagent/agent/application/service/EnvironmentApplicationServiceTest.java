package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentApplicationServiceTest {

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository deploymentRepository;
    @Mock private SessionReferenceApi sessionReferenceApi;
    @Mock private TransactionTemplate transactionTemplate;

    private EnvironmentApplicationService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        service = new EnvironmentApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "environmentRepository", environmentRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "deploymentRepository", deploymentRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionReferenceApi", sessionReferenceApi);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private Environment buildEnvironment(String environmentId, String name) {
        return Environment.restore(null, environmentId, name, null, EnvironmentConfig.cloudDefault(), "{}",
                1L, null, null, null, null, null);
    }

    private CreateEnvironmentCommand buildCreateCommand(String name) {
        return new CreateEnvironmentCommand(name, null, EnvironmentConfig.cloudDefault(), "{}");
    }

    @Test
    void should_generateEnvironmentId_when_create_given_validCommand() {
        // given
        when(environmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Environment saved = service.create(buildCreateCommand("本地环境"));

        // then
        assertTrue(saved.environmentId().startsWith("env_"));
        assertEquals("本地环境", saved.name());
        assertEquals(EnvironmentType.CLOUD, saved.config().type());
    }

    @Test
    void should_persistAndReturnSelfHostedConfig_when_create_given_selfHostedCommand() {
        // given（自托管环境登记：值对象已通过领域不变量校验，服务透传落库）
        when(environmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateEnvironmentCommand command = new CreateEnvironmentCommand("自托管节点", null,
                new EnvironmentConfig(EnvironmentType.SELF_HOSTED, null, "apt-get update"), "{}");

        // when
        Environment saved = service.create(command);

        // then（env_ 前缀业务 ID + 自托管契约（无包声明、setup_script 保留）随保存回读）
        assertTrue(saved.environmentId().startsWith("env_"));
        assertEquals(EnvironmentType.SELF_HOSTED, saved.config().type());
        assertEquals("apt-get update", saved.config().setupScript());
        assertTrue(saved.config().packages().isEmpty());
    }

    @Test
    void should_returnEnvironment_when_get_given_ownedEnvironment() {
        // given
        when(environmentRepository.findByEnvironmentId("env_1"))
                .thenReturn(Optional.of(buildEnvironment("env_1", "云端环境")));

        // when // then（详情查询正例：命中即返回，不触发引用计数）
        assertEquals("env_1", service.get("env_1").environmentId());
    }

    @Test
    void should_throwNotFound_when_get_given_notExist() {
        // given
        when(environmentRepository.findByEnvironmentId("missing")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get("missing"));
    }

    @Test
    void should_throwConflict_when_delete_given_stillReferenced() {
        // given（删除守卫迁移到实时会话引用：仍有 2 个会话挂载该环境 → 409）
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(sessionReferenceApi.countSessionsByEnvironmentId("env-1")).thenReturn(2L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete("env-1"));
        assertTrue(ex.getMessage().contains("被 2 个会话引用"));
        verify(environmentRepository, never()).deleteByEnvironmentId(anyString());
    }

    @Test
    void should_delete_when_delete_given_noReference() {
        // given（无会话挂载该环境：守卫放行并删除）
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(sessionReferenceApi.countSessionsByEnvironmentId("env-1")).thenReturn(0L);
        when(deploymentRepository.countActiveByEnvironmentId("env-1")).thenReturn(0L);

        // when
        service.delete("env-1");

        // then
        verify(environmentRepository).deleteByEnvironmentId("env-1");
    }

    @Test
    void should_throwConflict_when_delete_given_referencedByDeployment() {
        // given（审查修复 F10：会话引用为零但仍有未归档调度器引用 → 409 拒绝删除）
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(sessionReferenceApi.countSessionsByEnvironmentId("env-1")).thenReturn(0L);
        when(deploymentRepository.countActiveByEnvironmentId("env-1")).thenReturn(2L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete("env-1"));
        assertTrue(ex.getMessage().contains("调度器"));
        verify(environmentRepository, never()).deleteByEnvironmentId(anyString());
    }

    @Test
    void should_trimProbeRow_when_list_given_hasMorePage() {
        // given（6.6 游标化：探针多取一行 → 裁切至 limit 且 has_more=true）
        java.util.List<Environment> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            rows.add(buildEnvironment("env_" + i, "环境" + i));
        }
        when(environmentRepository.findByCursor(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(EnvironmentListFilter.class),
                org.mockito.ArgumentMatchers.eq(21))).thenReturn(rows);
        ListEnvironmentQuery query = new ListEnvironmentQuery(1L, null, null, null,
                CursorPageParams.parse(null, null, null));

        // when
        CursorPage<Environment> page = service.list(query);

        // then
        assertEquals(20, page.data().size());
        assertTrue(page.hasMore());
        assertEquals("env_1", page.firstId());
        assertEquals("env_20", page.lastId());
    }

    @Test
    void should_resolveAnchorPosition_when_list_given_afterId() {
        // given（游标锚点经 owner 定位行位点，成对装配进过滤器）
        Environment anchor = Environment.restore(9L, "env_a", "锚点环境", null,
                EnvironmentConfig.cloudDefault(), "{}", 1L,
                null, java.time.OffsetDateTime.now(), null, null, null);
        when(environmentRepository.findByEnvironmentId("env_a")).thenReturn(Optional.of(anchor));
        when(environmentRepository.findByCursor(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(EnvironmentListFilter.class),
                org.mockito.ArgumentMatchers.eq(21))).thenReturn(java.util.List.of());
        ListEnvironmentQuery query = new ListEnvironmentQuery(1L, null, null, null,
                CursorPageParams.parse(null, "env_a", null));

        // when
        service.list(query);

        // then
        ArgumentCaptor<EnvironmentListFilter> captor = ArgumentCaptor.forClass(EnvironmentListFilter.class);
        verify(environmentRepository).findByCursor(org.mockito.ArgumentMatchers.eq(1L),
                captor.capture(), org.mockito.ArgumentMatchers.eq(21));
        assertEquals(anchor.createdAt(), captor.getValue().cursorCreatedAt());
        assertEquals(9L, captor.getValue().cursorRowId());
        assertFalse(captor.getValue().reverse());
    }

    @Test
    void should_throwNotFound_when_list_given_anchorNotOwned() {
        // given（游标锚点越权 → 404）
        Environment others = Environment.restore(9L, "env_x", "他人环境", null,
                EnvironmentConfig.cloudDefault(), "{}", 2L, null, null, null, null, null);
        when(environmentRepository.findByEnvironmentId("env_x")).thenReturn(Optional.of(others));
        ListEnvironmentQuery query = new ListEnvironmentQuery(1L, null, null, null,
                CursorPageParams.parse(null, "env_x", null));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.list(query));
    }

    @Test
    void should_setArchivedAt_when_archive_given_noReference() {
        // given（无会话 / 无调度器引用：归档写入 archived_at 时间戳）
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(sessionReferenceApi.countSessionsByEnvironmentId("env-1")).thenReturn(0L);
        when(deploymentRepository.countActiveByEnvironmentId("env-1")).thenReturn(0L);
        when(environmentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Environment archived = service.archive("env-1");

        // then
        assertTrue(archived.isArchived());
        assertNotNull(archived.archivedAt());
    }

    @Test
    void should_throwConflict_when_archive_given_stillReferenced() {
        // given（仍有会话挂载该环境 → 409 拒绝归档）
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(sessionReferenceApi.countSessionsByEnvironmentId("env-1")).thenReturn(2L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.archive("env-1"));
        assertTrue(ex.getMessage().contains("无法归档"));
    }
}