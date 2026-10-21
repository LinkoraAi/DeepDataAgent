package com.linkroa.deepdataagent.rag.controller.stream;

import com.linkroa.deepdataagent.rag.application.contract.AnswerStream;
import com.linkroa.deepdataagent.rag.infrastructure.config.RetrievalStreamProperties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次检索答案流会话（SseEmitter 生命周期 + 增量下发 + 断开取消的唯一管理者）。
 *
 * <p><b>事件顺序</b>：先下发 {@code answer.started} → 订阅增量流（期间执行 Stage 0~4 并
 * 周期发送保活注释帧）→ 正常完成时刷出剩余增量并发 {@code answer.completed}；
 * 异常时发 {@code answer.error}。一次流只会以其中一种方式收尾。</p>
 *
 * <p><b>取消语义</b>：{@link SseEmitter#onCompletion} 在正常完成时同样会触发，故用
 * {@link #finished} 原子标志区分「本会话已主动收尾」与「客户端提前断开」：
 * 仅后者才 dispose 模型订阅并停止继续消耗 token；前者不做任何取消动作，
 * 保证答案缓存回写等收尾逻辑不被误伤。</p>
 *
 * @author DeepDataAgent
 */
public final class AnswerStreamSession {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(AnswerStreamSession.class);

    /** 保活调度器（守护线程，进程内单例） */
    private static final ScheduledExecutorService KEEPALIVE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rag-answer-keepalive");
                thread.setDaemon(true);
                return thread;
            });

    /** 保活注释帧内容 */
    private static final String KEEPALIVE_COMMENT = "keepalive";

    /** SSE 连接 */
    private final SseEmitter emitter;

    /** 检索答案流（增量流） */
    private final AnswerStream answerStream;

    /** 流参数（合帧窗口、保活间隔） */
    private final RetrievalStreamProperties properties;

    /** 知识库主键（仅用于日志归因） */
    private final Long kbId;

    /** 会话是否已收尾（正常完成、异常终结或客户端断开，三选一） */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /** 模型订阅句柄（用于断开取消） */
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    /** 保活任务句柄 */
    private final AtomicReference<ScheduledFuture<?>> keepaliveTask = new AtomicReference<>();

    /** 已产出答案累积（用于终结帧全文） */
    private final StringBuilder answer = new StringBuilder();

    /** 增量合帧器（订阅建立后创建） */
    private volatile AnswerDeltaCoalescer coalescer;

    /**
     * 构造会话并注册连接回调。
     *
     * @param emitter       SSE 连接
     * @param answerStream  检索答案流
     * @param properties    流参数
     * @param kbId          知识库主键（日志归因）
     */
    public AnswerStreamSession(SseEmitter emitter, AnswerStream answerStream,
                               RetrievalStreamProperties properties, Long kbId) {
        this.emitter = emitter;
        this.answerStream = answerStream;
        this.properties = properties;
        this.kbId = kbId;
        emitter.onCompletion(this::onEmitterTerminated);
        emitter.onTimeout(this::onEmitterTerminated);
        emitter.onError(error -> onEmitterTerminated());
    }

    /**
     * 启动会话：发送开始事件、启动保活、订阅增量流。
     * <p>本方法在专用虚拟线程内调用；内部不向外抛异常（异常转为 {@code answer.error} 帧）。</p>
     */
    public void start() {
        try {
            send(AnswerStreamEventCodec.started());
            startKeepalive();
            coalescer = new AnswerDeltaCoalescer(properties.getDeltaFlushIntervalMs(), this::sendDelta);
            subscription.set(answerStream.deltas().subscribe(this::onDelta, this::onError, this::onComplete));
        } catch (RuntimeException e) {
            onError(e);
        }
    }

    /**
     * 增量到达：累积全文并交给合帧器，由其决定何时真正写 SSE。
     *
     * @param delta 增量文本
     */
    private void onDelta(String delta) {
        if (finished.get()) {
            return;
        }
        answer.append(delta);
        AnswerDeltaCoalescer current = coalescer;
        if (current != null) {
            current.onDelta(delta);
        }
    }

    /**
     * 正常完成：刷出剩余增量、发送终结帧并关闭连接。
     */
    private void onComplete() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        stopKeepalive();
        AnswerDeltaCoalescer current = coalescer;
        if (current != null) {
            current.close();
        }
        try {
            send(AnswerStreamEventCodec.completed(answer.toString()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("答案终结帧发送失败（客户端可能已断开）: kbId={} message={}", kbId, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 异常终结：停止保活、发送错误帧并关闭连接。
     *
     * @param error 流内异常
     */
    private void onError(Throwable error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        stopKeepalive();
        AnswerDeltaCoalescer current = coalescer;
        if (current != null) {
            current.close();
        }
        log.warn("检索答案流异常终结: kbId={} message={}", kbId, error.getMessage(), error);
        try {
            send(AnswerStreamEventCodec.error(AnswerStreamEventCodec.ERROR_CODE_INTERNAL,
                    readableMessage(error)));
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 连接终止回调（正常完成、超时、错误、客户端断开共用）。
     * <p>仅当会话尚未由自身收尾时才视为客户端提前断开，此时取消模型订阅。</p>
     */
    private void onEmitterTerminated() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        stopKeepalive();
        Disposable disposable = subscription.getAndSet(null);
        if (disposable != null) {
            disposable.dispose();
        }
        log.info("检索答案流客户端断开，已取消模型订阅: kbId={} emittedChars={}", kbId, answer.length());
    }

    /**
     * 启动准备阶段保活（间隔非正数表示关闭）。
     */
    private void startKeepalive() {
        long intervalMs = properties.getKeepaliveInterval().toMillis();
        if (intervalMs <= 0L) {
            return;
        }
        keepaliveTask.set(KEEPALIVE_SCHEDULER.scheduleAtFixedRate(() -> {
            if (finished.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment(KEEPALIVE_COMMENT));
            } catch (Exception e) {
                onEmitterTerminated();
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS));
    }

    /**
     * 停止保活任务（幂等）。
     */
    private void stopKeepalive() {
        ScheduledFuture<?> task = keepaliveTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 写单个增量帧（合帧器的下游消费者）。
     *
     * @param chunk 合并后的增量文本
     */
    private void sendDelta(String chunk) {
        send(AnswerStreamEventCodec.delta(chunk));
    }

    /**
     * 写一个 SSE 事件帧。
     *
     * @param frame 事件帧
     * @throws IllegalStateException 写入失败（连接已不可用）
     */
    private void send(AnswerStreamEventCodec.Frame frame) {
        try {
            emitter.send(SseEmitter.event().name(frame.event()).data(frame.data()));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 异常 → 可读错误信息（空消息回落异常类型名）。
     *
     * @param error 异常
     * @return 可读信息
     */
    private static String readableMessage(Throwable error) {
        if (error == null) {
            return "答案生成失败";
        }
        return StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
    }
}