package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionListFilter;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.infrastructure.convert.RuntimePersistenceConvert;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.AgentSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcAgentSessionRepository} 仓储实现单测（mock MyBatis Mapper）。
 * <p>双列状态机 + 正交归档下仓储只做「委托 + 行数 / Optional 收敛」：int（{@code transition}
 * 与 {@code archive} 两个 CAS 落点）、boolean（{@code isCancelling} 只读谓词）、长整型
 * （countBy*）的收敛正确性是本案重点；状态 / 相位一律以领域枚举的小写规范值经迁移声明下传
 * mapper，仓储内不出现状态字面量。</p>
 */
@ExtendWith(MockitoExtension.class)
class JdbcAgentSessionRepositoryTest {

    @Mock
    private AgentSessionMapper mapper;

    private JdbcAgentSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcAgentSessionRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
    }

    @Test
    void should_saveNewSession_when_save_given_entityWithoutId() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", "标题");
        AgentSessionEntity entity = RuntimePersistenceConvert.INSTANCE.toEntity(session);
        when(mapper.findBySessionId(session.sessionId())).thenReturn(entity);

        // when
        AgentSession saved = repository.save(session);

        // then
        assertEquals(session.sessionId(), saved.sessionId());
        verify(mapper).insert(any(AgentSessionEntity.class));
        verify(mapper, never()).updateById(any(AgentSessionEntity.class));
    }

    @Test
    void should_updateExistingSession_when_save_given_entityWithId() {
        // given
        AgentSession withId = persistedSession(1L, AgentSessionStatus.RUNNING, TurnPhase.RUNNING);

        // when
        repository.save(withId);

        // then
        verify(mapper, never()).insert(any(AgentSessionEntity.class));
        verify(mapper).updateById(any(AgentSessionEntity.class));
    }

    @Test
    void should_keepMountedFields_when_save_given_environmentAndResources() {
        // given（已落库会话带环境 / 保管库 / 记忆库 / 挂载资源，save 应原样回写实体）
        AgentSession withId = persistedSession(9L, AgentSessionStatus.RUNNING, TurnPhase.RUNNING);
        when(mapper.findBySessionId(withId.sessionId()))
                .thenReturn(RuntimePersistenceConvert.INSTANCE.toEntity(withId));

        // when
        AgentSession saved = repository.save(withId);

        // then（往返后挂载字段不丢失）
        assertEquals("env_1", saved.environmentId());
        assertEquals(TurnPhase.RUNNING, saved.turnPhase());
        assertEquals(List.of("vault_1"), saved.vaultIds());
        assertEquals(List.of("ms_1"), saved.memoryStoreIds());
        assertEquals(1, saved.resources().size());
        assertEquals("file_1", saved.resources().get(0).fileId());
    }

    @Test
    void should_findById_when_findBySessionId_given_existingSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", "标题");
        when(mapper.findBySessionId(session.sessionId()))
                .thenReturn(RuntimePersistenceConvert.INSTANCE.toEntity(session));

        // when
        Optional<AgentSession> found = repository.findBySessionId(session.sessionId());

        // then（新会话初始态为 idle / idle）
        assertTrue(found.isPresent());
        assertEquals(session.sessionId(), found.get().sessionId());
        assertEquals(AgentSessionStatus.IDLE, found.get().status());
        assertEquals(TurnPhase.IDLE, found.get().turnPhase());
    }

    @Test
    void should_returnEmpty_when_findBySessionId_given_notExist() {
        // given
        when(mapper.findBySessionId("nope")).thenReturn(null);

        // when
        Optional<AgentSession> found = repository.findBySessionId("nope");

        // then
        assertFalse(found.isPresent());
    }

    @Test
    void should_returnSessions_when_findByCursor_given_noCursor() {
        // given
        AgentSession s1 = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        AgentSession s2 = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        SessionListFilter filter = emptyFilter();
        when(mapper.selectByCursor("u-1", filter, 21)).thenReturn(List.of(
                RuntimePersistenceConvert.INSTANCE.toEntity(s1),
                RuntimePersistenceConvert.INSTANCE.toEntity(s2)));

        // when
        List<AgentSession> found = repository.findByCursor("u-1", filter, 21);

        // then
        assertEquals(2, found.size());
        assertEquals(s1.sessionId(), found.get(0).sessionId());
    }

    @Test
    void should_delegateFilterAsIs_when_findByCursor_given_filtersAndCursorRow() {
        // given（游标行位点与过滤条件由应用层装配，仓储原样下传 mapper）
        OffsetDateTime cursor = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");
        SessionListFilter filter = new SessionListFilter(
                "agent-a", "1.0.0", null, null,
                List.of(AgentSessionStatus.RUNNING, AgentSessionStatus.IDLE),
                "{\"k\":\"v\"}", false,
                null, null, null, null, true, cursor, 7L, true);

        // when
        repository.findByCursor("u-1", filter, 21);

        // then
        verify(mapper).selectByCursor("u-1", filter, 21);
    }

    @Test
    void should_passDeclarationThrough_when_transition_given_namedConstant() {
        // given（CAS 面唯一入口：迁移声明原样透传给 Mapper，行数原样透出）
        when(mapper.transition("s-1", Transition.BEGIN_TURN)).thenReturn(1);

        // when / then
        assertEquals(1, repository.transition("s-1", Transition.BEGIN_TURN));
        verify(mapper).transition("s-1", Transition.BEGIN_TURN);
    }

    @Test
    void should_returnZero_when_transition_given_predecessorMismatch() {
        // given（前置状态 / 相位不匹配：CAS 未命中，调用方据此判定空操作）
        when(mapper.transition("s-1", Transition.TERMINATE)).thenReturn(0);

        // when / then
        assertEquals(0, repository.transition("s-1", Transition.TERMINATE));
    }

    @Test
    void should_passArchiveThrough_when_archive_given_sessionId() {
        // given（归档已移出状态机：唯一落点为 Mapper.archive，只写 archived_at）
        when(mapper.archive("s-1")).thenReturn(1);

        // when / then
        assertEquals(1, repository.archive("s-1"));
        verify(mapper).archive("s-1");
    }

    @Test
    void should_returnZero_when_archive_given_alreadyArchived() {
        // given（并发归档仅一方命中 archived_at IS NULL 守卫）
        when(mapper.archive("s-1")).thenReturn(0);

        // when / then
        assertEquals(0, repository.archive("s-1"));
    }

    @Test
    void should_returnStatus_when_currentStatus_given_enumValuedColumn() {
        // given（终态事务窄读：存量取值为规范小写状态值）
        when(mapper.selectStatusValue("s-1")).thenReturn("rescheduling");

        // when
        Optional<AgentSessionStatus> status = repository.currentStatus("s-1");

        // then
        assertTrue(status.isPresent());
        assertEquals(AgentSessionStatus.RESCHEDULING, status.get());
    }

    @Test
    void should_returnEmpty_when_currentStatus_given_rowMissing() {
        // given（会话行不存在 / 已逻辑删除：读不到即放弃前置判定，终态资格仍由 CAS 仲裁）
        when(mapper.selectStatusValue("s-ghost")).thenReturn(null);

        // when / then
        assertTrue(repository.currentStatus("s-ghost").isEmpty());
    }

    @Test
    void should_returnEmpty_when_currentStatus_given_legacyDirtyValue() {
        // given（存量脏值不在四态值域内：不得因决策表读状态而抛错）
        when(mapper.selectStatusValue("s-1")).thenReturn("archived");

        // when / then
        assertTrue(repository.currentStatus("s-1").isEmpty());
    }

    @Test
    void should_returnTrue_when_isCancelling_given_cancellingPhaseRow() {
        // given（进行中取消的持久痕迹：终态决策表跨进程判定「取消进行中」的权威依据）
        when(mapper.isCancelling("s-1")).thenReturn(true);

        // when / then
        assertTrue(repository.isCancelling("s-1"));
    }

    @Test
    void should_returnFalse_when_isCancelling_given_noCancellingPhaseRow() {
        // given
        when(mapper.isCancelling("s-1")).thenReturn(false);

        // when / then
        assertFalse(repository.isCancelling("s-1"));
    }

    @Test
    void should_delegate_when_updateProfile_given_titleMetadataAndEnvVars() {
        // when（titlePresent=true 才写 title；null 列 = 不更新，条件 set 语义由 mapper 承担）
        repository.updateProfile("s-1", "新标题", true, "{\"k\":\"v\"}", null);

        // then
        verify(mapper).updateProfile("s-1", "新标题", true, "{\"k\":\"v\"}", null);
    }

    @Test
    void should_returnAffectedRows_when_deleteBySessionId_given_mapperDeletes() {
        // given（@TableLogic 逻辑删除命中 1 行）
        when(mapper.deleteBySessionId("s-1")).thenReturn(1);

        // when / then
        assertEquals(1, repository.deleteBySessionId("s-1"));
    }

    @Test
    void should_serializeResourcesWithIds_when_updateResources_given_fileMounts() {
        // given（领域资源经工厂构造，自动补齐 sesr_ 业务 ID）
        List<SessionResource> resources = List.of(SessionResource.file("file_1", "mounts/a.txt"));

        // when
        repository.updateResources("s-1", resources);

        // then（整列覆盖：snake_case JSON 文本，资源项携带 sesr_ 资源 ID）
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateResources(eq("s-1"), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("\"id\":\"" + SessionResource.RESOURCE_ID_PREFIX));
        assertTrue(jsonCaptor.getValue().contains("\"file_id\":\"file_1\""));
        assertTrue(jsonCaptor.getValue().contains("\"mount_path\":\"mounts/a.txt\""));
    }

    @Test
    void should_writeEmptyArrayJson_when_updateResources_given_emptyList() {
        // when
        repository.updateResources("s-1", List.of());

        // then（空列表序列化为 "[]"，直传 mapper）
        verify(mapper).updateResources("s-1", "[]");
    }

    @Test
    void should_returnActiveIds_when_findActiveExecutionSessionIds_given_sessions() {
        // given（启动恢复枚举：对外 running/rescheduling 且内部相位 running/cancelling）
        when(mapper.findActiveExecutionSessionIds()).thenReturn(List.of("s-1", "s-2"));

        // when
        List<String> ids = repository.findActiveExecutionSessionIds();

        // then
        assertEquals(List.of("s-1", "s-2"), ids);
    }

    @Test
    void should_returnCount_when_countByEnvironmentId_given_mapperReturnsCount() {
        // given
        when(mapper.countByEnvironmentId("env_1")).thenReturn(3L);

        // when / then
        assertEquals(3L, repository.countByEnvironmentId("env_1"));
    }

    @Test
    void should_returnZero_when_countByEnvironmentId_given_mapperReturnsNull() {
        // given（selectCount 理论上不返回 null，此处兜底收敛为 0 避免 NPE）
        when(mapper.countByEnvironmentId("env_1")).thenReturn(null);

        // when / then
        assertEquals(0L, repository.countByEnvironmentId("env_1"));
    }

    @Test
    void should_returnCount_when_countByMemoryStoreId_given_mapperReturnsCount() {
        // given（记忆库被会话引用的计数，供删除前 409 引用守卫使用）
        when(mapper.countByMemoryStoreId("ms_1")).thenReturn(2L);

        // when / then
        assertEquals(2L, repository.countByMemoryStoreId("ms_1"));
    }

    @Test
    void should_returnZero_when_countByMemoryStoreId_given_mapperReturnsNull() {
        // given（selectCount 兜底收敛为 0 避免 NPE）
        when(mapper.countByMemoryStoreId("ms_1")).thenReturn(null);

        // when / then
        assertEquals(0L, repository.countByMemoryStoreId("ms_1"));
    }

    @Test
    void should_delegate_when_touchLastActive_given_sessionId() {
        // when
        repository.touchLastActive("s-1");

        // then
        verify(mapper).touchLastActive("s-1");
    }

    @Test
    void should_notTouchMapper_when_save_given_findReturnsNull() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        when(mapper.findBySessionId(session.sessionId())).thenReturn(null);

        // when
        AgentSession saved = repository.save(session);

        // then（查不到时回退入参会话视图，不吞掉新建结果）
        assertEquals(session.sessionId(), saved.sessionId());
        verify(mapper, never()).updateById(any(AgentSessionEntity.class));
    }

    /** 无过滤、无游标的首页查询条件（15 参数全量装配）。 */
    private static SessionListFilter emptyFilter() {
        return new SessionListFilter(null, null, null, null, List.of(), null, false,
                null, null, null, null, false, null, null, false);
    }

    /**
     * 构造已落库会话（含自增主键、双列状态与完整挂载字段，用于 save 更新分支与字段保真验证）。
     */
    private AgentSession persistedSession(Long id, AgentSessionStatus status, TurnPhase turnPhase) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return AgentSession.restore(
                id, "s-" + id, "u-1", "agent-a", "1.0.0",
                status, turnPhase, "{}", "标题",
                "env_1", List.of("vault_1"), List.of("ms_1"), "{\"K\":\"V\"}",
                List.of(SessionResource.file("file_1", null)),
                null, null, now, null, now, now, null, null);
    }
}