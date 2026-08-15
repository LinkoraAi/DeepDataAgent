package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapperImpl;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.AgentSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcAgentSessionRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcAgentSessionRepositoryTest {

    @Mock
    private AgentSessionMapper mapper;

    private RuntimePersistenceMapper persistenceMapper;
    private JdbcAgentSessionRepository repository;

    @BeforeEach
    void setUp() {
        persistenceMapper = new RuntimePersistenceMapperImpl();
        repository = new JdbcAgentSessionRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        ReflectionTestUtils.setField(repository, "persistenceMapper", persistenceMapper);
    }

    @Test
    void should_saveNewSession_when_save_given_entityWithoutId() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", "标题");
        AgentSessionEntity entity = persistenceMapper.toEntity(session);
        when(mapper.findBySessionId(session.sessionId())).thenReturn(entity);

        // when
        AgentSession saved = repository.save(session);

        // then
        assertEquals(session.sessionId(), saved.sessionId());
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(AgentSessionEntity.class));
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any(AgentSessionEntity.class));
    }

    @Test
    void should_updateExistingSession_when_save_given_entityWithId() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", "标题");
        AgentSession withId = new AgentSession(
                1L, session.sessionId(), session.userId(), session.agentId(), session.agentVersion(),
                session.status(), session.metadata(), session.sandboxId(), session.title(),
                session.lastActiveAt(), session.createdAt(), session.updatedAt(), session.createdBy(),
                session.updatedBy());

        // when
        repository.save(withId);

        // then
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(AgentSessionEntity.class));
        verify(mapper).updateById(org.mockito.ArgumentMatchers.any(AgentSessionEntity.class));
    }

    @Test
    void should_findById_when_findBySessionId_given_existingSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", "标题");
        when(mapper.findBySessionId(session.sessionId())).thenReturn(persistenceMapper.toEntity(session));

        // when
        Optional<AgentSession> found = repository.findBySessionId(session.sessionId());

        // then
        assertTrue(found.isPresent());
        assertEquals(session.sessionId(), found.get().sessionId());
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
    void should_returnPageEvents_when_findByUserId_given_user() {
        // given
        AgentSession s1 = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        AgentSession s2 = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        when(mapper.findByUserId("u-1", 1, 20)).thenReturn(List.of(
                persistenceMapper.toEntity(s1), persistenceMapper.toEntity(s2)));

        // when
        List<AgentSession> found = repository.findByUserId("u-1", 1, 20);

        // then
        assertEquals(2, found.size());
        assertEquals(s1.sessionId(), found.get(0).sessionId());
    }

    @Test
    void should_returnCount_when_countByUserId_given_user() {
        // given
        when(mapper.countByUserId("u-1")).thenReturn(5L);

        // when
        long count = repository.countByUserId("u-1");

        // then
        assertEquals(5L, count);
    }

    @Test
    void should_returnTrue_when_tryMarkRunning_given_affectedOneRow() {
        // given
        when(mapper.tryMarkRunning("s-1")).thenReturn(1);

        // when
        boolean grabbed = repository.tryMarkRunning("s-1");

        // then
        assertTrue(grabbed);
    }

    @Test
    void should_returnFalse_when_tryMarkRunning_given_noRowAffected() {
        // given
        when(mapper.tryMarkRunning("s-1")).thenReturn(0);

        // when
        boolean grabbed = repository.tryMarkRunning("s-1");

        // then
        assertFalse(grabbed);
    }

    @Test
    void should_delegate_when_markIdleAndUpdateStatus_given_sessionId() {
        // when
        repository.markIdle("s-1");
        repository.updateStatus("s-1", AgentSessionStatus.TERMINATED);

        // then
        verify(mapper).markIdle("s-1");
        verify(mapper).updateStatus("s-1", "TERMINATED");
    }

    @Test
    void should_returnRunningIds_when_findRunningSessionIds_given_sessions() {
        // given
        when(mapper.findRunningSessionIds()).thenReturn(List.of("s-1", "s-2"));

        // when
        List<String> ids = repository.findRunningSessionIds();

        // then
        assertEquals(List.of("s-1", "s-2"), ids);
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

        // then
        assertEquals(session.sessionId(), saved.sessionId());
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any(AgentSessionEntity.class));
    }

    @Test
    void should_findByUserIdWithPagination_when_findByUserId_given_pageAndSize() {
        // when
        repository.findByUserId("u-1", 2, 50);

        // then
        verify(mapper).findByUserId("u-1", 2, 50);
    }
}