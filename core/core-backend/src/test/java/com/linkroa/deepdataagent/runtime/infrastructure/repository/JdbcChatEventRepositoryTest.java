package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapperImpl;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.ChatEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcChatEventRepository} 仓储实现单测（mock MyBatis Mapper）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcChatEventRepositoryTest {

    @Mock
    private ChatEventMapper mapper;

    private RuntimePersistenceMapper persistenceMapper;
    private JdbcChatEventRepository repository;

    @BeforeEach
    void setUp() {
        persistenceMapper = new RuntimePersistenceMapperImpl();
        repository = new JdbcChatEventRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        ReflectionTestUtils.setField(repository, "persistenceMapper", persistenceMapper);
    }

    @Test
    void should_insertEvent_when_save_given_validEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.THINKING, "{\"delta\":\"推理\"}", 1L);

        // when
        ChatEvent saved = repository.save(event);

        // then（事件原样返回，不再回查 DB）
        assertEquals(event, saved);
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(ChatEventEntity.class));
    }

    @Test
    void should_skipRoundLookup_when_save_given_validEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.RUN_START, "{}", 1L);

        // when
        repository.save(event);

        // then（save 仅插入不进行 findByRound 冗余回查）
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(ChatEventEntity.class));
        verify(mapper, org.mockito.Mockito.never()).findByRound(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void should_allocateNextSequence_when_nextSequenceNum_given_currentMax() {
        // given
        when(mapper.maxSequenceNum("s-1")).thenReturn(7L);

        // when
        long next = repository.nextSequenceNum("s-1");

        // then
        assertEquals(8L, next);
    }

    @Test
    void should_startFromOne_when_nextSequenceNum_given_noEvents() {
        // given
        when(mapper.maxSequenceNum("s-1")).thenReturn(0L);

        // when
        long next = repository.nextSequenceNum("s-1");

        // then
        assertEquals(1L, next);
    }

    @Test
    void should_findAfterSequence_when_findBySessionAfter_given_position() {
        // given
        ChatEvent e1 = ChatEvent.create("s-1", "r-1", ChatEventType.MESSAGE, "{\"delta\":\"a\"}", 2L);
        ChatEvent e2 = ChatEvent.create("s-1", "r-1", ChatEventType.MESSAGE, "{\"delta\":\"b\"}", 3L);
        when(mapper.findBySessionAfter("s-1", 1L)).thenReturn(List.of(
                persistenceMapper.toEntity(e1), persistenceMapper.toEntity(e2)));

        // when
        List<ChatEvent> events = repository.findBySessionAfter("s-1", 1L);

        // then
        assertEquals(2, events.size());
        assertEquals(2L, events.get(0).sequenceNum());
        assertEquals(3L, events.get(1).sequenceNum());
    }

    @Test
    void should_findByRound_when_findByRound_given_roundId() {
        // given
        ChatEvent e1 = ChatEvent.create("s-1", "r-1", ChatEventType.THINKING, "{\"delta\":\"x\"}", 1L);
        when(mapper.findByRound("r-1")).thenReturn(List.of(persistenceMapper.toEntity(e1)));

        // when
        List<ChatEvent> events = repository.findByRound("r-1");

        // then
        assertEquals(1, events.size());
        assertEquals(ChatEventType.THINKING, events.get(0).eventType());
    }
}