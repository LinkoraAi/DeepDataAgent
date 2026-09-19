package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextBlockAccumulator} 单测（原 {@code AgentRunStateTest} 的文本 / 思考累积、
 * 进行中流、delta 聚合、跨线程只读面断言原样平移）。
 */
class TextBlockAccumulatorTest {

    @Test
    void should_accumulateOutputAndFallbackToFinalResult_when_output_given_textAndAgentResult() {
        // given
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.appendOutput("你好");
        state.appendOutput("，");
        state.setFinalResultText("不该被使用");

        // when
        String output = state.output();

        // then（增量累积优先）
        assertEquals("你好，", output);
    }

    @Test
    void should_fallbackToAgentResult_when_output_given_noTextDeltas() {
        // given
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.setFinalResultText("最终答案");

        // when
        String output = state.output();

        // then
        assertEquals("最终答案", output);
    }

    @Test
    void should_trackAndConditionallyClearInFlightStream_when_inFlightStream_given_matchingEventId() {
        // given
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.markInFlightStream("evt_1", ChatEventType.AGENT_MESSAGE, "blk-1", 9L);

        // when & then（装载可读）
        assertEquals("evt_1", state.inFlightStream().eventId());
        assertEquals(ChatEventType.AGENT_MESSAGE, state.inFlightStream().targetType());
        assertEquals("blk-1", state.inFlightStream().blockId());
        assertEquals(9L, state.inFlightStream().baseSeq());

        // when（误清防护：ID 不匹配 / null 不清）
        state.clearInFlightStream("evt_other");
        state.clearInFlightStream(null);

        // then（仍为原进行中流）
        assertEquals("evt_1", state.inFlightStream().eventId());

        // when（ID 匹配才清空）
        state.clearInFlightStream("evt_1");

        // then
        assertNull(state.inFlightStream());
    }

    @Test
    void should_rejectInvalid_when_InFlightStream_given_blankEventId() {
        // when & then（进行中流契约不变量：事件 ID 非空）
        assertThrows(IllegalArgumentException.class,
                () -> new TextBlockAccumulator.InFlightStream(" ", ChatEventType.AGENT_MESSAGE, "blk-1", 1L));
    }

    @Test
    void should_flushFirstDeltaImmediatelyAndAggregateRest_when_shouldFlushDelta_given_largeInterval() {
        // given（极大刷写间隔：首段立发、后续片段进聚合缓冲）
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.appendPendingDelta("你");

        // when & then（从未刷写 → 首段恒可刷）
        assertTrue(state.shouldFlushDelta(60_000L));
        assertEquals("你", state.drainPendingDelta());

        // when（刷写后间隔未达）
        state.appendPendingDelta("好");

        // then（不立发，进缓冲）
        assertFalse(state.shouldFlushDelta(60_000L));
        // 间隔非正数视为逐段立发
        assertTrue(state.shouldFlushDelta(0L));
        assertEquals("好", state.drainPendingDelta());
        // 缓冲已排空
        assertEquals("", state.drainPendingDelta());
    }

    @Test
    void should_ignoreBlankDeltas_when_appendPendingDelta_given_nullAndEmpty() {
        // given
        TextBlockAccumulator state = new TextBlockAccumulator();

        // when
        state.appendPendingDelta(null);
        state.appendPendingDelta("");

        // then
        assertEquals("", state.drainPendingDelta());
    }

    @Test
    void should_keepBlockTextForTake_when_accumulatedText_given_streamingBlock() {
        // given（累积文本只读快照，不移除——一段重连回补后可正常收尾落库）
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.appendText("blk-1", "你好");
        state.appendText("blk-1", "，世界");

        // when & then
        assertEquals("你好，世界", state.accumulatedText("blk-1"));
        assertEquals("", state.accumulatedText("blk-x"));
        assertEquals("你好，世界", state.takeText("blk-1"));
    }

    @Test
    void should_dropInFlightAndPending_when_clearInFlightStreamOnSuspend_given_activeStream() {
        // given（文本块流式中途挂起：进行中流快照与未刷完的增量缓冲均在位）
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.markInFlightStream("evt_1", ChatEventType.AGENT_MESSAGE, "blk-1", 5L);
        state.appendPendingDelta("半块");

        // when（挂起即物理轮终局：不再有 TEXT_END 收尾，显式丢弃快照）
        state.clearInFlightStreamOnSuspend();

        // then（重连判定依据为空 → 不回补永远等不到收尾的 event_start；缓冲同步丢弃）
        assertNull(state.inFlightStream());
        assertEquals("", state.drainPendingDelta());
    }

    @Test
    void should_keepConsistentSnapshot_when_accumulatedText_given_concurrentAppend() throws Exception {
        // given（流线程独占写 + HTTP 线程并发读同一 builder：读到的必须是已追加内容的完整前缀）
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.appendText("blk-1", "seed");
        int rounds = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // when
        pool.execute(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    state.appendText("blk-1", "x");
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            try {
                for (int i = 0; i < rounds * 2; i++) {
                    String snapshot = state.accumulatedText("blk-1");
                    boolean torn = !snapshot.startsWith("seed")
                            || !snapshot.chars().skip(4).allMatch(c -> c == 'x');
                    if (torn) {
                        throw new IllegalStateException("读到撕裂快照: " + snapshot);
                    }
                    reads.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // then（两侧均正常收尾、无异常逃逸、写侧内容完整、消费后仍可移除）
        assertTrue(finished);
        assertNull(failure.get());
        assertTrue(reads.get() > rounds);
        assertEquals("seed" + "x".repeat(rounds), state.accumulatedText("blk-1"));
        assertEquals("seed" + "x".repeat(rounds), state.takeText("blk-1"));
        assertEquals("", state.accumulatedText("blk-1"));
    }

    @Test
    void should_returnEmpty_when_blockAccessors_given_nullBlockId() {
        // given（累积表改 ConcurrentHashMap 后不再接受 null 键，空串语义须由领域面自守）
        TextBlockAccumulator state = new TextBlockAccumulator();
        state.appendThinking("blk-1", "思考");

        // when // then
        assertEquals("", state.appendText(null, "x"));
        assertEquals("", state.appendThinking(null, "x"));
        assertEquals("", state.accumulatedText(null));
        assertEquals("", state.takeText(null));
        assertEquals("思考", state.takeThinking("blk-1"));
        assertEquals("", state.takeThinking(null));
    }
}
