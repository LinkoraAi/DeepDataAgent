package com.linkroa.deepdataagent.runtime.infrastructure.persistence;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link BatchChatEventPersister} 异步批量落库单测（不启动后台线程，靠 flush 确定性排空）。
 */
@ExtendWith(MockitoExtension.class)
class BatchChatEventPersisterTest {

    @Mock
    private ChatEventRepository repository;

    @Test
    void should_notPersistImmediately_when_enqueue_given_event() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent event = event(1L);

        // when
        persister.enqueue(event);

        // then（异步入队不阻塞调用方：不立即落库）
        verify(repository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_persistAllInOrder_when_flush_given_enqueuedEvents() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        persister.enqueue(event(1L));
        persister.enqueue(event(2L));
        persister.enqueue(event(3L));

        // when
        persister.flush();

        // then（按入队顺序 FIFO 落库）
        ArgumentCaptor<ChatEvent> captor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(repository, times(3)).save(captor.capture());
        List<ChatEvent> saved = captor.getAllValues();
        assertEquals(1L, saved.get(0).sequenceNum());
        assertEquals(2L, saved.get(1).sequenceNum());
        assertEquals(3L, saved.get(2).sequenceNum());
    }

    @Test
    void should_continuePersisting_when_flush_given_saveFailsForOne() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent fail = event(1L);
        ChatEvent ok = event(2L);
        doThrow(new RuntimeException("db down")).when(repository).save(fail);
        persister.enqueue(fail);
        persister.enqueue(ok);

        // when
        persister.flush();

        // then（单条失败不中断后续事件落库）
        verify(repository).save(fail);
        verify(repository).save(ok);
    }

    @Test
    void should_doNothing_when_flush_given_emptyQueue() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);

        // when
        persister.flush();

        // then
        verify(repository, never()).save(any(ChatEvent.class));
    }

    private ChatEvent event(long sequenceNum) {
        return ChatEvent.create("s-1", "r-1", ChatEventType.MESSAGE, "{}", sequenceNum);
    }
}