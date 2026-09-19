package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.infrastructure.convert.RuntimePersistenceConvert;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.ChatEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcChatEventRepository} 仓储实现单测（mock MyBatis Mapper）。
 * <p>事件溯源模型下仓储只承担保存 / seq 分配 / 按会话回放（seq 游标 + types 过滤）三类语义，
 * 轮次查询（findByRound）已随 execution_round 表删除。</p>
 */
@ExtendWith(MockitoExtension.class)
class JdbcChatEventRepositoryTest {

    @Mock
    private ChatEventMapper mapper;

    private JdbcChatEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcChatEventRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
    }

    @Test
    void should_insertEvent_when_save_given_validEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_THINKING, "{\"text\":\"推理\"}", 1L);

        // when
        ChatEvent saved = repository.save(event);

        // then（事件原样返回，不再回查 DB）
        assertEquals(event, saved);
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(ChatEventEntity.class));
    }

    @Test
    void should_allocateNextSequence_when_nextSequenceNum_given_currentMax() {
        // given
        when(mapper.maxSeq("s-1")).thenReturn(7L);

        // when
        long next = repository.nextSequenceNum("s-1");

        // then
        assertEquals(8L, next);
    }

    @Test
    void should_startFromOne_when_nextSequenceNum_given_noEvents() {
        // given
        when(mapper.maxSeq("s-1")).thenReturn(0L);

        // when
        long next = repository.nextSequenceNum("s-1");

        // then
        assertEquals(1L, next);
    }

    @Test
    void should_findAfterSequence_when_findBySessionAfter_given_positionAndTypes() {
        // given（seq 游标 + types 过滤透传 Mapper）
        ChatEvent e1 = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{\"text\":\"a\"}", 2L);
        ChatEvent e2 = ChatEvent.create("s-1", ChatEventType.SESSION_STATUS_IDLE, "{\"status\":\"idle\"}", 3L);
        List<String> types = List.of("agent.message", "session.status_idle");
        when(mapper.findBySessionAfter("s-1", 1L, types, null)).thenReturn(List.of(
                RuntimePersistenceConvert.INSTANCE.toEntity(e1), RuntimePersistenceConvert.INSTANCE.toEntity(e2)));

        // when
        List<ChatEvent> events = repository.findBySessionAfter("s-1", 1L, types, null);

        // then（按 seq 升序回放，信封字段完整回读）
        assertEquals(2, events.size());
        assertEquals(2L, events.get(0).seq());
        assertEquals(3L, events.get(1).seq());
        assertEquals(ChatEventType.AGENT_MESSAGE, events.get(0).type());
        assertEquals(ChatEventType.SESSION_STATUS_IDLE, events.get(1).type());
    }

    @Test
    void should_delegateSeqLookup_when_findSeqByEventId_given_hitAndMiss() {
        // given（evt_ 断点游标换算：命中返回 seq，未命中返回 empty）
        when(mapper.findSeqByEventId("s-1", "evt_hit")).thenReturn(OptionalLong.of(5L));
        when(mapper.findSeqByEventId("s-1", "evt_miss")).thenReturn(OptionalLong.empty());

        // when & then
        OptionalLong hit = repository.findSeqByEventId("s-1", "evt_hit");
        assertTrue(hit.isPresent());
        assertEquals(5L, hit.getAsLong());
        assertFalse(repository.findSeqByEventId("s-1", "evt_miss").isPresent());
    }

    @Test
    void should_findAllAfterSequence_when_findBySessionAfter_given_nullTypes() {
        // given（types 为空表示不过滤）
        ChatEvent e1 = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 1L);
        when(mapper.findBySessionAfter("s-1", 0L, List.of(), null)).thenReturn(List.of(
                RuntimePersistenceConvert.INSTANCE.toEntity(e1)));

        // when
        List<ChatEvent> events = repository.findBySessionAfter("s-1", 0L, List.of(), null);

        // then
        assertEquals(1, events.size());
        assertEquals(1L, events.get(0).seq());
    }
}