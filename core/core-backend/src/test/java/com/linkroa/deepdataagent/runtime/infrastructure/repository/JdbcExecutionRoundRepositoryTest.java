package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapperImpl;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ExecutionRoundEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.ExecutionRoundMapper;
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
 * {@link JdbcExecutionRoundRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcExecutionRoundRepositoryTest {

    @Mock
    private ExecutionRoundMapper mapper;

    private RuntimePersistenceMapper persistenceMapper;
    private JdbcExecutionRoundRepository repository;

    @BeforeEach
    void setUp() {
        persistenceMapper = new RuntimePersistenceMapperImpl();
        repository = new JdbcExecutionRoundRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        ReflectionTestUtils.setField(repository, "persistenceMapper", persistenceMapper);
    }

    @Test
    void should_insertNewRound_when_save_given_entityWithoutId() {
        // given
        ExecutionRound round = ExecutionRound.create("s-1", "run-1", 1, "你好");
        when(mapper.findByRoundId(round.roundId())).thenReturn(persistenceMapper.toEntity(round));

        // when
        ExecutionRound saved = repository.save(round);

        // then
        assertEquals(round.roundId(), saved.roundId());
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(ExecutionRoundEntity.class));
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any(ExecutionRoundEntity.class));
    }

    @Test
    void should_updateCompletedRound_when_save_given_entityWithId() {
        // given
        ExecutionRound completed = ExecutionRound.create("s-1", "run-1", 1, "你好")
                .complete("最终输出", RoundStatus.COMPLETED);
        ExecutionRound withId = new ExecutionRound(
                1L, completed.roundId(), completed.sessionId(), completed.runId(), completed.roundNumber(),
                completed.input(), completed.output(), completed.status(), completed.replayedFromRoundId(),
                completed.createdAt(), completed.updatedAt(), completed.createdBy(), completed.updatedBy());

        // when
        repository.save(withId);

        // then
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(ExecutionRoundEntity.class));
        verify(mapper).updateById(org.mockito.ArgumentMatchers.any(ExecutionRoundEntity.class));
    }

    @Test
    void should_findByRoundId_when_exists() {
        // given
        ExecutionRound round = ExecutionRound.create("s-1", "run-1", 1, "你好");
        when(mapper.findByRoundId(round.roundId())).thenReturn(persistenceMapper.toEntity(round));

        // when
        Optional<ExecutionRound> found = repository.findByRoundId(round.roundId());

        // then
        assertTrue(found.isPresent());
        assertEquals(round.roundId(), found.get().roundId());
    }

    @Test
    void should_returnEmpty_when_findByRoundId_given_notExist() {
        // given
        when(mapper.findByRoundId("nope")).thenReturn(null);

        // when
        Optional<ExecutionRound> found = repository.findByRoundId("nope");

        // then
        assertFalse(found.isPresent());
    }

    @Test
    void should_findBySessionId_when_exists() {
        // given
        ExecutionRound r1 = ExecutionRound.create("s-1", "run-1", 1, "你好");
        ExecutionRound r2 = ExecutionRound.create("s-1", "run-2", 2, "再见");
        when(mapper.findBySessionId("s-1")).thenReturn(List.of(
                persistenceMapper.toEntity(r1), persistenceMapper.toEntity(r2)));

        // when
        List<ExecutionRound> found = repository.findBySessionId("s-1");

        // then
        assertEquals(2, found.size());
        assertEquals(1, found.get(0).roundNumber());
        assertEquals(2, found.get(1).roundNumber());
    }

    @Test
    void should_returnNextNumber_when_nextRoundNumber_given_maxThree() {
        // given
        when(mapper.maxRoundNumber("s-1")).thenReturn(3);

        // when
        int next = repository.nextRoundNumber("s-1");

        // then
        assertEquals(4, next);
    }

    @Test
    void should_returnInterruptedCount_when_updateRunningToInterrupted_given_sessionIds() {
        // given
        when(mapper.updateRunningToInterrupted(List.of("s-1"))).thenReturn(2);

        // when
        int count = repository.updateRunningToInterrupted(List.of("s-1"));

        // then
        assertEquals(2, count);
    }
}