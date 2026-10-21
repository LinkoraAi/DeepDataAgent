package com.linkroa.deepdataagent.rag.controller.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;

/**
 * {@link AnswerDeltaCoalescer} 单元测试。
 * <p>钉住三条不变量：① 首个增量立即下发（不牺牲首字延迟）；② 窗口内增量合并为更少的帧；
 * ③ 各帧内容按序拼接恒等于输入顺序拼接（不丢字、不重字）。窗口调度以 Mock 承接，
 * 由测试显式调用 {@link AnswerDeltaCoalescer#flush()} 模拟窗口到期，避免依赖墙钟。</p>
 *
 * @author DeepDataAgent
 */
class AnswerDeltaCoalescerTest {

    /** 合帧窗口（毫秒） */
    private static final long WINDOW_MS = 50L;

    /**
     * 场景：窗口内容纳多个增量。
     * 预期：首帧立即下发，其余在窗口到期（flush）时合并为一帧。
     */
    @Test
    void should_flushFirstDeltaImmediatelyAndCoalesceRest_when_onDelta_givenMultipleWithinWindow() {
        // given
        List<String> frames = new ArrayList<>();
        AtomicLong now = new AtomicLong(1_000L);
        AnswerDeltaCoalescer coalescer = new AnswerDeltaCoalescer(WINDOW_MS, frames::add,
                mock(ScheduledExecutorService.class), now::get);

        // when
        coalescer.onDelta("A");
        now.addAndGet(10L);
        coalescer.onDelta("B");
        now.addAndGet(10L);
        coalescer.onDelta("C");
        coalescer.flush();

        // then：首帧立即下发，B/C 合并为第二帧
        assertEquals(List.of("A", "BC"), frames);
    }

    /**
     * 场景：窗口已过。
     * 预期：到达的增量立即下发，不再等待窗口。
     */
    @Test
    void should_flushImmediately_when_onDelta_givenWindowElapsed() {
        // given
        List<String> frames = new ArrayList<>();
        AtomicLong now = new AtomicLong(1_000L);
        AnswerDeltaCoalescer coalescer = new AnswerDeltaCoalescer(WINDOW_MS, frames::add,
                mock(ScheduledExecutorService.class), now::get);

        // when
        coalescer.onDelta("A");
        now.addAndGet(WINDOW_MS);
        coalescer.onDelta("B");

        // then
        assertEquals(List.of("A", "B"), frames);
    }

    /**
     * 场景：流结束（close）时仍有未下发内容。
     * 预期：close 先刷出尾段再关闭，内容不丢失。
     */
    @Test
    void should_flushRemaining_when_close_givenBufferedDelta() {
        // given
        List<String> frames = new ArrayList<>();
        AtomicLong now = new AtomicLong(1_000L);
        AnswerDeltaCoalescer coalescer = new AnswerDeltaCoalescer(WINDOW_MS, frames::add,
                mock(ScheduledExecutorService.class), now::get);
        coalescer.onDelta("首帧");
        now.addAndGet(1L);
        coalescer.onDelta("尾段");

        // when
        coalescer.close();
        coalescer.onDelta("关闭后增量");

        // then：尾段被刷出，关闭后的增量被丢弃
        assertEquals(List.of("首帧", "尾段"), frames);
    }

    /**
     * 场景：大量增量分批到达。
     * 预期：各帧按序拼接与输入完全一致（合帧无损）。
     */
    @Test
    void should_preserveContentOrder_when_onDelta_givenManyDeltas() {
        // given
        StringBuilder received = new StringBuilder();
        AtomicLong now = new AtomicLong(1_000L);
        AnswerDeltaCoalescer coalescer = new AnswerDeltaCoalescer(WINDOW_MS, received::append,
                mock(ScheduledExecutorService.class), now::get);
        StringBuilder expected = new StringBuilder();

        // when：每 3 个增量推进一次窗口
        for (int index = 0; index < 30; index++) {
            String delta = "片段" + index + ";";
            expected.append(delta);
            coalescer.onDelta(delta);
            now.addAndGet(20L);
            if (index % 3 == 0) {
                coalescer.flush();
            }
        }
        coalescer.close();

        // then
        assertEquals(expected.toString(), received.toString());
        assertTrue(received.length() > 0);
    }

    /**
     * 场景：下游写失败（客户端已断开）。
     * 预期：合帧器自行关闭，后续增量不再抛错。
     */
    @Test
    void should_closeSilently_when_sink_givenThrows() {
        // given
        AtomicLong now = new AtomicLong(1_000L);
        AnswerDeltaCoalescer coalescer = new AnswerDeltaCoalescer(WINDOW_MS, chunk -> {
            throw new IllegalStateException("连接已断开");
        }, mock(ScheduledExecutorService.class), now::get);

        // when & then：不向外抛异常
        coalescer.onDelta("A");
        now.addAndGet(100L);
        coalescer.onDelta("B");
        coalescer.close();
    }

    /**
     * 场景：空白增量。
     * 预期：被忽略，不产生空帧。
     */
    @Test
    void should_ignoreBlankDelta_when_onDelta_givenEmptyText() {
        // given
        List<String> frames = new ArrayList<>();
        AnswerDeltaCoalescer coalescer = new AnswerDeltaCoalescer(WINDOW_MS, frames::add,
                mock(ScheduledExecutorService.class), () -> 1_000L);

        // when
        coalescer.onDelta("");
        coalescer.onDelta(null);
        coalescer.flush();

        // then
        assertTrue(frames.isEmpty());
    }
}