package com.linkroa.deepdataagent.runtime.infrastructure.persistence;

import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天事件异步批量落库实现（单通道 FIFO + 会话级落库失败标记 + 严格排空协议）。
 * <p>单后台 drain 线程轮询内存队列，按 FIFO（即入队顺序）批量写事件表，
 * 保证同一会话内 {@code seq} 单调不变序；单条写失败重试后不中断其余 drain。</p>
 * <p><b>两档落库语义</b>（见 {@link ChatEventPersister}）：</p>
 * <ul>
 *   <li><b>尽力而为</b>（{@link #enqueue} / {@link #flush}）：重试耗尽仅记结构化 ERROR 后丢弃，
 *       由 {@code (session_id, seq)} 唯一索引幂等 + 断线重连回放去重兜底；</li>
 *   <li><b>严格排空协议</b>（{@link #flush} + {@link #isPoisoned}）：重试耗尽的事件所属会话被置入
 *       {@code poisonedSessionIds}（落库失败标记）。终态 / HITL 挂起路径先在事务<b>外</b>
 *       {@link #flush()} 整队排空（{@code persistLock} 内逐条落库、各自独立提交——他会话事件行
 *       不进入本会话 JDBC 事务、不随本会话回滚丢失），再于事务首行 {@link #isPoisoned} 按会话
 *       校验落库失败标记，命中由调用方抛 {@link DeepDataAgentException} 整批回滚，杜绝「状态已迁移而事件表缺
 *       中间事件」。落库失败标记在该会话新一轮启动 CAS 提交成功后经 {@link #clearPoisonFlag} 清除
 *       （旧轮故障不继承，集合按 sessionId 隔离、无跨会话误判）。</li>
 * </ul>
 */
@Component
public class BatchChatEventPersister implements ChatEventPersister {

    private static final Logger log = LoggerFactory.getLogger(BatchChatEventPersister.class);
    private static final int BATCH_SIZE = 200;
    private static final int MAX_WRITE_ATTEMPTS = 2;

    private final ChatEventRepository repository;
    private final BlockingQueue<ChatEvent> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 出队 + 落库共锁（审查修复 F14）：保证 persist 顺序严格等于 enqueue 顺序，drain 与 flush 并发不再漏项 / 乱序。 */
    private final Object persistLock = new Object();
    /**
     * 持久化失败标记（会话级）：任一 buffered 事件重试耗尽未落库即置位，
     * 严格排空协议（事务首行 {@link #isPoisoned} 校验落库失败标记）据此拒绝为该会话迁移状态。
     * 置位 / 清除均在 {@code persistLock} 内（由 writer 或代其排空的 flush 调用线程执行）；
     * 校验落库失败标记发生在调用方完成整队排空之后，与批写全序无竞态窗口。
     */
    private final Set<String> poisonedSessionIds = ConcurrentHashMap.newKeySet();
    private Thread writerThread;

    /**
     * 以事件仓储构造异步批量落库器；写线程不在此时启动（由 {@link #start()} 启动，纯单测靠 {@link #flush()} 确定性排空）。
     *
     * @param repository 聊天事件仓储
     */
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
                // 取队与落库同锁（F14）：poll 出的 head 与后继 drainTo 段必须同批按序 persist，
                // 并发 flush 不得插队把后入队事件先落库（否则 seq 空洞回放错序）
                synchronized (persistLock) {
                    ChatEvent head = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (head == null) {
                        continue;
                    }
                    batch.add(head);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    persist(batch);
                    batch.clear();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        flush();
    }

    /**
     * 逐条落库，写失败按上限重试；超出上限标记该会话落库失败后记结构化 ERROR 并继续 drain
     * （不中断其余事件——丢哪条已不可挽回，但后续事件仍应尽量入事件表）。
     * <p>日志字段固定为 sessionId / eventId / seq / type / attempts，不含 payload（脱敏口径同
     * {@code LogMasker}）；该 ERROR 是异步通道唯一的失败留痕，MUST NOT 静默丢弃。</p>
     */
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
                        markPoisoned(event);
                        log.error("聊天事件异步落库失败（已重试 {} 次，丢弃并标记会话落库失败）: "
                                        + "sessionId={}, eventId={}, seq={}, type={}, attempts={}",
                                attempt, event.sessionId(), event.eventId(), event.seq(),
                                event.type().value(), attempt, ex);
                        break;
                    }
                    log.warn("聊天事件异步落库失败，将重试（第 {} 次）: sessionId={}, seq={}",
                            attempt, event.sessionId(), event.seq());
                }
            }
        }
    }

    /**
     * 排空整条队列（严格排空协议·事务前段，在终态 / 挂起事务开启<b>外</b>调用）。
     * <p>尽力而为：写失败仅记日志并由 {@code persist} 标记该会话落库失败，不在本方法抛出；
     * 关键路径完整性由调用方事务首行 {@link #isPoisoned} 校验落库失败标记判定。与 drainLoop 共锁（F14）：
     * flush 排空段与后台批量段的全序由 persistLock 保证；逐条落库各自独立提交，
     * 他会话事件行不进入调用方 JDBC 事务、不随其回滚丢失。</p>
     */
    @Override
    public void flush() {
        // 与 drainLoop 共锁（F14）：flush 排空段与后台批量段的全序由 persistLock 保证
        synchronized (persistLock) {
            drainToPersist();
        }
    }

    /**
     * 按会话校验落库失败标记（严格排空协议·事务首段）：判定该会话是否存在重试耗尽仍未落库的事件，
     * 命中由调用方在状态迁移事务首行抛异常整批回滚。
     * <p>落库失败标记仅在 {@code persist} 持 {@code persistLock} 写失败的瞬间置位；调用方先行
     * {@link #flush()} 返回时全队列已在同一把锁下排空，故校验落库失败标记的结果与排空结果一致、无竞态窗口。
     * 并发集合读无需持锁；集合按 sessionId 隔离，不存在跨会话误判。
     * 标记落库失败对事件类型不加区分（设计 D1 显式取舍）：任一 buffered 事件写失败即污染整会话，
     * 「事件表缺一行即不可信」对回放契约而言是类型无关的整体判断。</p>
     */
    @Override
    public boolean isPoisoned(String sessionId) {
        return sessionId != null && poisonedSessionIds.contains(sessionId);
    }

    /** 清除会话落库失败标记（新一轮启动 CAS 提交成功后调用；幂等，锁内执行以免与进行中的批写竞态）。 */
    @Override
    public void clearPoisonFlag(String sessionId) {
        if (sessionId == null) {
            return;
        }
        synchronized (persistLock) {
            poisonedSessionIds.remove(sessionId);
        }
    }

    /** 排空整条队列并按 FIFO 落库（调用方须持有 {@code persistLock}；失败事件由 persist 标记落库失败）。 */
    private void drainToPersist() {
        List<ChatEvent> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            persist(batch);
        }
    }

    /** 置位会话级持久化失败标志（写失败重试耗尽的唯一状态防护，严格排空协议据此拒绝状态迁移）。 */
    private void markPoisoned(ChatEvent event) {
        poisonedSessionIds.add(event.sessionId());
    }

    /**
     * 关闭后台写线程：置停运行标记、中断并限时等待收敛（退出前由 drain 循环兜底排空残留事件）。
     */
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
