package com.linkroa.deepdataagent.runtime.infrastructure.persistence;

import com.linkroa.deepdataagent.runtime.application.service.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天事件异步批量落库实现。
 * <p>单后台 drain 线程轮询内存队列，按 FIFO（即入队顺序）批量写事件表，
 * 保证同一会话内 {@code sequence_number} 单调不变序；单条写失败重试后不中断其余 drain
 * （超出重试上限记录 ERROR，由 {@code (session_id, sequence_num)} 唯一索引兜底幂等 +
 * 断线重连回放去重）。</p>
 */
@Component
public class BatchChatEventPersister implements ChatEventPersister {

    private static final Logger log = LoggerFactory.getLogger(BatchChatEventPersister.class);
    private static final int BATCH_SIZE = 200;
    private static final int MAX_WRITE_ATTEMPTS = 2;

    private final ChatEventRepository repository;
    private final BlockingQueue<ChatEvent> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;

    public BatchChatEventPersister(ChatEventRepository repository) {
        this.repository = repository;
    }

    /** 启动后台写线程（Spring 生命周期管理；纯单测不触发，靠 {@link #flush()} 确定性排空）。 */
    @PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            writerThread = new Thread(this::drainLoop, "chat-event-batch-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }
    }

    @Override
    public void enqueue(ChatEvent event) {
        queue.offer(event);
    }

    /** 后台 drain 循环：批量取出 FIFO 事件并按序落库，退出前兜底排空残留。 */
    private void drainLoop() {
        List<ChatEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (running.get()) {
            try {
                ChatEvent head = queue.poll(200, TimeUnit.MILLISECONDS);
                if (head == null) {
                    continue;
                }
                batch.add(head);
                queue.drainTo(batch, BATCH_SIZE - 1);
                persist(batch);
                batch.clear();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        flush();
    }

    /** 逐条落库，写失败按上限重试，超出后仅记日志丢弃（依赖唯一索引幂等 + 回放去重兜底）。 */
    private void persist(List<ChatEvent> batch) {
        for (ChatEvent event : batch) {
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    repository.save(event);
                    break;
                } catch (RuntimeException ex) {
                    if (attempt >= MAX_WRITE_ATTEMPTS) {
                        log.error("聊天事件异步落库失败（已重试 {} 次，丢弃）: sessionId={}, eventType={}, sequence={}",
                                attempt, event.sessionId(), event.eventType(), event.sequenceNum(), ex);
                        break;
                    }
                    log.warn("聊天事件异步落库失败，将重试（第 {} 次）: sessionId={}, sequence={}",
                            attempt, event.sessionId(), event.sequenceNum());
                }
            }
        }
    }

    @Override
    public void flush() {
        List<ChatEvent> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            persist(batch);
        }
    }

    @PreDestroy
    public void close() {
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}