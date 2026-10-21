package com.linkroa.deepdataagent.rag.controller.stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 答案增量合帧器：把模型逐 token 的增量按时间窗口合并为更少的 SSE 帧。
 * <p>规则：<b>首个增量立即下发</b>（不为凑窗口牺牲首字延迟）；此后窗口内到达的增量累积，
 * 窗口到期或达到容量上限时一次下发。合帧只改变帧的粒度，MUST NOT 改变内容顺序或丢失字符——
 * 各帧内容按序拼接恒等于全部输入增量按序拼接。</p>
 * <p>线程语义：{@link #onDelta(String)}、{@link #flush()}、{@link #close()} 均线程安全；
 * 窗口刷写由共享的单线程守护调度器驱动。下游写失败（如客户端断开）时自动置为关闭，
 * 后续增量被丢弃，不再向上抛错。</p>
 *
 * @author DeepDataAgent
 */
public final class AnswerDeltaCoalescer implements AutoCloseable {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(AnswerDeltaCoalescer.class);

    /** 共享窗口调度器（守护线程，进程内单例；任务极轻，不占用业务线程） */
    private static final ScheduledExecutorService SHARED_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rag-answer-delta-flush");
                thread.setDaemon(true);
                return thread;
            });

    /** 合帧窗口毫秒（非正数收敛为 1ms，等价于逐增量下发） */
    private final long windowMs;

    /** 合帧结果消费者（实际写 SSE 的动作） */
    private final Consumer<String> sink;

    /** 窗口调度器 */
    private final ScheduledExecutorService scheduler;

    /** 当前时钟（毫秒），便于单测注入可控时间源 */
    private final LongSupplier clock;

    /** 待下发缓冲区 */
    private final StringBuilder buffer = new StringBuilder();

    /** 上次下发时间戳（0 表示尚未下发，首帧立即触发） */
    private long lastFlushAt;

    /** 已排期的窗口刷写任务（同一时刻最多一个） */
    private ScheduledFuture<?> pendingFlush;

    /** 是否已关闭（下游写失败或显式 close） */
    private boolean closed;

    /**
     * 生产构造器：使用共享调度器与系统时钟。
     *
     * @param windowMs 合帧窗口毫秒
     * @param sink     合帧结果消费者
     */
    public AnswerDeltaCoalescer(long windowMs, Consumer<String> sink) {
        this(windowMs, sink, SHARED_SCHEDULER, System::currentTimeMillis);
    }

    /**
     * 测试缝构造器：注入调度器与时钟。
     *
     * @param windowMs  合帧窗口毫秒
     * @param sink      合帧结果消费者
     * @param scheduler 窗口调度器
     * @param clock     毫秒时钟
     */
    AnswerDeltaCoalescer(long windowMs, Consumer<String> sink, ScheduledExecutorService scheduler,
                         LongSupplier clock) {
        this.windowMs = Math.max(1L, windowMs);
        this.sink = sink;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /**
     * 接收一个增量：首帧立即下发，其余按窗口合并。
     *
     * @param delta 本次增量文本（空白增量被忽略）
     */
    public synchronized void onDelta(String delta) {
        if (closed || StringUtils.isEmpty(delta)) {
            return;
        }
        buffer.append(delta);
        long now = clock.getAsLong();
        if (lastFlushAt == 0L || now - lastFlushAt >= windowMs) {
            flushNow();
            return;
        }
        if (pendingFlush == null) {
            pendingFlush = scheduler.schedule(this::flushQuietly, windowMs - (now - lastFlushAt),
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 立即下发缓冲区中尚未发送的内容（流结束时调用，保证不丢尾段）。
     */
    public synchronized void flush() {
        flushNow();
    }

    /**
     * 关闭合帧器：先刷出剩余内容，再停止接收新增量。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        flushNow();
        closed = true;
    }

    /**
     * 调度器回调入口：加锁后执行刷写（异常不外抛，避免打断调度线程）。
     */
    private void flushQuietly() {
        synchronized (this) {
            try {
                flushNow();
            } catch (RuntimeException e) {
                log.warn("答案增量窗口刷写异常，关闭合帧器: {}", e.getMessage());
                closed = true;
            }
        }
    }

    /**
     * 实际刷写：取消待执行任务、取走缓冲内容并交给消费者。
     * <p>消费者异常视为下游不可用（客户端断开），置关闭并静默丢弃后续内容。</p>
     */
    private void flushNow() {
        cancelPending();
        if (buffer.isEmpty()) {
            return;
        }
        String chunk = buffer.toString();
        buffer.setLength(0);
        lastFlushAt = clock.getAsLong();
        try {
            sink.accept(chunk);
        } catch (RuntimeException e) {
            closed = true;
            log.debug("答案增量下发失败（下游可能已断开），合帧器关闭: {}", e.getMessage());
        }
    }

    /**
     * 取消尚未执行的窗口刷写任务。
     */
    private void cancelPending() {
        if (pendingFlush != null) {
            pendingFlush.cancel(false);
            pendingFlush = null;
        }
    }
}