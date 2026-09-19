package com.linkroa.deepdataagent.runtime.infrastructure.persistence;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BatchChatEventPersister} 异步批量落库单测（不启动后台线程，靠 {@code flush} 确定性排空、
 * {@code isPoisoned} 查毒，验证「事务外排空 + 事务内查毒」严格排空协议的落库器侧行为）。
 * <p>断言一律以「入队时的同一实例」为比对基准：{@link ChatEvent} 为含时间戳的 record，
 * 另建同字段实例不相等。</p>
 */
@ExtendWith(MockitoExtension.class)
class BatchChatEventPersisterTest {

    @Mock
    private ChatEventRepository repository;

    @Test
    void should_notPersistImmediately_when_enqueue_given_event() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent event = event("s-1", 1L);

        // when
        persister.enqueue(event);

        // then（异步入队不阻塞调用方：不立即落库）
        verify(repository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_persistAllInOrder_when_flush_given_enqueuedEvents() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        persister.enqueue(event("s-1", 1L));
        persister.enqueue(event("s-1", 2L));
        persister.enqueue(event("s-1", 3L));

        // when
        persister.flush();

        // then（按入队顺序 FIFO 落库）
        ArgumentCaptor<ChatEvent> captor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(repository, times(3)).save(captor.capture());
        List<ChatEvent> saved = captor.getAllValues();
        assertEquals(1L, saved.get(0).seq());
        assertEquals(2L, saved.get(1).seq());
        assertEquals(3L, saved.get(2).seq());
    }

    @Test
    void should_continuePersisting_when_flush_given_saveFailsForOne() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent fail = event("s-1", 1L);
        ChatEvent ok = event("s-1", 2L);
        doThrow(new RuntimeException("db down")).when(repository).save(fail);
        persister.enqueue(fail);
        persister.enqueue(ok);

        // when
        persister.flush();

        // then（单条失败不中断后续事件落库：失败事件按 MAX_WRITE_ATTEMPTS=2 重试一次后丢弃，
        // 因此 save(fail) 共调用 2 次；后续事件正常落库）
        verify(repository, times(2)).save(fail);
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

    // ==================== 会话级毒标志 + 严格排空协议（flush 排空 + isPoisoned 查毒） ====================

    @Test
    void should_markPoisonedAndDrainOthers_when_flush_given_sessionPersistExhausted() {
        // given（队列内本会话一条事件写库必失败：重试耗尽置毒后仍须排空其余事件）
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent failing = event("s-1", 1L);
        ChatEvent following = event("s-1", 2L);
        doThrow(new RuntimeException("db down")).when(repository).save(failing);
        persister.enqueue(failing);
        persister.enqueue(following);

        // when（协议·事务前段：尽力而为整队排空，本方法不抛异常）
        persister.flush();

        // then（队列已排空——同批后继事件照常落库；失败事件按重试上限写 2 次后丢弃并置毒，
        //       调用方据此在事务首行查毒命中抛异常回滚状态迁移事务）
        verify(repository, times(2)).save(failing);
        verify(repository).save(following);
        assertTrue(persister.isPoisoned("s-1"));
        // when（再次排空：已出队事件不重复写）
        persister.flush();
        // then（毒标志持续至显式清除：再次查毒仍命中，防悬挂协议不因重试窗口松动）
        assertTrue(persister.isPoisoned("s-1"));
        verify(repository, times(2)).save(failing);
        verify(repository, times(1)).save(following);
    }

    @Test
    void should_reportNoPoison_when_flush_given_allEventsPersisted() {
        // given（无毒标志：全部事件可正常落库）
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent first = event("s-1", 1L);
        ChatEvent second = event("s-2", 1L);
        persister.enqueue(first);
        persister.enqueue(second);

        // when（协议·事务前段排空 + 首行查毒段）
        persister.flush();

        // then（两会话事件均落库、查毒均为假，队列已清空——再次排空不重复写）
        verify(repository).save(first);
        verify(repository).save(second);
        assertFalse(persister.isPoisoned("s-1"));
        assertFalse(persister.isPoisoned("s-2"));
        persister.flush();
        verify(repository, times(1)).save(first);
    }

    @Test
    void should_persistOtherSessions_when_flush_given_oneSessionWriteFails() {
        // given（同批混合两会话：s-1 首条写失败，s-2 全部正常）
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent broken = event("s-1", 1L);
        ChatEvent otherSession = event("s-2", 7L);
        doThrow(new RuntimeException("db down")).when(repository).save(broken);
        persister.enqueue(broken);
        persister.enqueue(otherSession);

        // when（事务前整队排空：逐条独立提交，语义与改造前一致）
        persister.flush();

        // then（失败事件不中断 drain：他会话事件照常落库、s-2 查毒为假——毒集合按 sessionId
        //       隔离，本会话毒发不波及他会话已落库事实）
        verify(repository).save(otherSession);
        assertFalse(persister.isPoisoned("s-2"));
        // then（毒仅属故障会话：s-1 查毒命中，调用方据此拒绝其状态迁移）
        assertTrue(persister.isPoisoned("s-1"));
    }

    @Test
    void should_passNextRoundPoisonCheck_when_clearPoisonFlag_given_sessionPreviouslyPoisoned() {
        // given（上一轮某事件写失败 → 会话被置毒，查毒命中）
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent broken = event("s-1", 1L);
        doThrow(new RuntimeException("db down")).when(repository).save(broken);
        persister.enqueue(broken);
        persister.flush();
        assertTrue(persister.isPoisoned("s-1"));

        // when（新一轮开跑 CAS 提交成功后清毒，并排入新一轮事件后排空）
        persister.clearPoisonFlag("s-1");
        ChatEvent nextRound = event("s-1", 2L);
        persister.enqueue(nextRound);
        persister.flush();

        // then（旧轮故障不继承：新一轮查毒通过，新事件正常落库）
        assertFalse(persister.isPoisoned("s-1"));
        verify(repository, times(2)).save(broken);
        verify(repository).save(nextRound);
    }

    @Test
    void should_keepPoisonFlagFalse_when_clearPoisonFlag_given_sessionNeverPoisoned() {
        // given
        BatchChatEventPersister persister = new BatchChatEventPersister(repository);
        ChatEvent event = event("s-9", 1L);

        // when（清毒幂等：未置毒会话重复清除 / 空 ID 清除均无副作用）
        persister.clearPoisonFlag("s-9");
        assertDoesNotThrow(() -> persister.clearPoisonFlag("s-9"));
        assertDoesNotThrow(() -> persister.clearPoisonFlag(null));
        persister.enqueue(event);
        persister.flush();

        // then（查毒为假、事件正常落库；空 ID 查询同样返回假不抛异常）
        assertFalse(persister.isPoisoned("s-9"));
        assertFalse(persister.isPoisoned(null));
        verify(repository).save(event);
    }

    @Test
    void should_keepStrictFlushProtocolLatencyBounded_when_flush_given_50SessionsDraining()
            throws Exception {
        // given（50 会话并发入队 + 各自执行严格排空协议「事务外 flush + 事务首行 isPoisoned」；
        //       账本单行写入固定 20µs 忙等以暴露 persistLock 争用面
        //       ——不用 parkNanos：Windows 时钟粒度会把亚毫秒 park 放大到毫秒级，测到的是调度器而非锁）
        int sessions = 50;
        int eventsPerSession = 20;
        AtomicInteger persisted = new AtomicInteger();
        ChatEventRepository slowLedger = mock(ChatEventRepository.class);
        when(slowLedger.save(any(ChatEvent.class))).thenAnswer(inv -> {
            spinMicros(20L);
            persisted.incrementAndGet();
            return inv.getArgument(0);
        });
        BatchChatEventPersister persister = new BatchChatEventPersister(slowLedger);
        ExecutorService pool = Executors.newFixedThreadPool(sessions);
        CountDownLatch ready = new CountDownLatch(sessions);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> latencies = new ArrayList<>(sessions);
        for (int i = 0; i < sessions; i++) {
            String sessionId = "sess_" + i;
            latencies.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                long begin = System.nanoTime();
                for (int j = 1; j <= eventsPerSession; j++) {
                    persister.enqueue(event(sessionId, j));
                }
                // 模拟终态路径严格排空协议：事务前整队排空 + 事务首行查毒
                // （全局队列 → 每次排空仍与他会话争 persistLock，但排空段已不在 JDBC 事务内）
                persister.flush();
                assertFalse(persister.isPoisoned(sessionId));
                return TimeUnit.MICROSECONDS.convert(System.nanoTime() - begin, TimeUnit.NANOSECONDS);
            }));
        }
        ready.await();
        start.countDown();

        // when（全部排空 + 查毒收敛：无死锁、无毒标志误置）
        long maxLatencyMicros = 0L;
        for (Future<Long> latency : latencies) {
            maxLatencyMicros = Math.max(maxLatencyMicros, latency.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // then（1000 条事件全部落库：并发排空不丢事件、不误置毒标志）
        assertEquals(sessions * eventsPerSession, persisted.get());
        // then（压测结论：50 并发轮次 × 单轮 20 条在队事件（合计 1000 行）+ 单行写入 20µs 的口径下，
        //       persistLock 串行的理论写入总量仅 20ms，实测最差单次「入队 + 排空 + 查毒」协议时延
        //       仍为几十毫秒级（余量为 50 线程争用与调度开销），远低于 500ms 门槛——排空移至事务外后
        //       时延结论不变，且事务持锁时长不再包含排空段 → 全局单队列 + persistLock 不构成
        //       50 并发下的显著放大项，per-session 分区队列维持计划后续项登记，本变更不扩大范围）
        assertTrue(maxLatencyMicros < 500_000L,
                "严格排空协议锁等待放大超阈值，实测最差单次（微秒）: " + maxLatencyMicros);
    }

    /** 定长忙等（模拟单行写入耗时，纳秒级可控，不受系统时钟粒度影响）。 */
    private static void spinMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            // 自旋消耗 CPU，等价于一行 INSERT 的服务端耗时占位
        }
    }

    private ChatEvent event(String sessionId, long sequenceNum) {
        return ChatEvent.create(sessionId, ChatEventType.AGENT_MESSAGE, "{}", sequenceNum);
    }
}
