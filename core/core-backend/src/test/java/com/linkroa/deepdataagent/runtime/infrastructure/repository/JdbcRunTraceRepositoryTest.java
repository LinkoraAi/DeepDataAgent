package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapperImpl;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.RunTraceEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.RunTraceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcRunTraceRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcRunTraceRepositoryTest {

    @Mock
    private RunTraceMapper mapper;

    private RuntimePersistenceMapper persistenceMapper;
    private JdbcRunTraceRepository repository;

    @BeforeEach
    void setUp() {
        persistenceMapper = new RuntimePersistenceMapperImpl();
        repository = new JdbcRunTraceRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        ReflectionTestUtils.setField(repository, "persistenceMapper", persistenceMapper);
    }

    @Test
    void should_insertRootSpan_when_save_given_entityWithoutId() {
        // given
        RunTrace root = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when
        RunTrace saved = repository.save(root);

        // then
        assertEquals(root.spanId(), saved.spanId());
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(RunTraceEntity.class));
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any(RunTraceEntity.class));
    }

    @Test
    void should_updateFinishedSpan_when_save_given_entityWithId() {
        // given
        RunTrace root = RunTrace.createRoot("trace-1", "r-1", "agent.run");
        RunTrace finished = root.finish(root.startTime().plusSeconds(2));
        RunTrace withId = new RunTrace(
                1L, finished.traceId(), finished.spanId(), finished.parentSpanId(), finished.roundId(),
                finished.spanName(), finished.spanKind(), finished.status(),
                finished.startTime(), finished.endTime(), finished.durationMs(),
                finished.inputTokens(), finished.outputTokens(), finished.modelName(), finished.estimatedCost(),
                finished.toolName(), finished.toolInput(), finished.toolOutput(), finished.attributes(),
                finished.createdAt(), finished.updatedAt(), finished.createdBy(), finished.updatedBy());

        // when
        repository.save(withId);

        // then
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(RunTraceEntity.class));
        verify(mapper).updateById(org.mockito.ArgumentMatchers.any(RunTraceEntity.class));
    }

    @Test
    void should_findByRound_when_exists() {
        // given
        RunTrace root = RunTrace.createRoot("trace-1", "r-1", "agent.run");
        RunTrace child = RunTrace.createChild("trace-1", root.spanId(), "r-1", "tool.call", "query_datasource",
                root.startTime());
        when(mapper.findByRound("r-1")).thenReturn(List.of(
                persistenceMapper.toEntity(root), persistenceMapper.toEntity(child)));

        // when
        List<RunTrace> spans = repository.findByRound("r-1");

        // then
        assertEquals(2, spans.size());
        assertEquals(SpanKind.INTERNAL, spans.get(0).spanKind());
        assertEquals("tool.call", spans.get(1).spanName());
    }
}