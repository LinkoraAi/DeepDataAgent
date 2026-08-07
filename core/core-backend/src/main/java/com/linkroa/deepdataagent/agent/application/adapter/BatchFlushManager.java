package com.linkroa.deepdataagent.agent.application.adapter;

import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 攒批持久化管理器
 * <p>基于单线程 {@link ScheduledExecutorService} 实现时间窗口触发的消息攒批写入，
 * 避免流式过程中频繁写库。单线程 FIFO 调度天然保序，消除定时 flush 与最终 flush 的竞态。</p>
 *
 * <p>写库节奏：首次 flush 延迟 {@code initialFlushDelaySeconds}（默认 1s，让前端尽早看到 RUNNING 内容），
 * 之后按固定间隔 {@code flushIntervalSeconds}（默认 5s）周期 flush。</p>
 *
 * <p>线程安全：flush 时在 {@code synchronized (context)} 锁内复制消息列表快照，
 * 锁外执行序列化与数据库写入，不阻塞事件接收线程。</p>
 */
public class BatchFlushManager {

    private static final Logger log = LoggerFactory.getLogger(BatchFlushManager.class);

    /** 默认首次 flush 延迟（秒） */
    private static final long DEFAULT_INITIAL_FLUSH_DELAY_SECONDS = 1L;

    /** 默认 flush 间隔（秒） */
    private static final long DEFAULT_FLUSH_INTERVAL_SECONDS = 5L;

    /** 单线程调度器，FIFO 保序 */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    /** 停止标志：最终 flush 后阻止新的定时任务提交 */
    private volatile boolean stopped = false;

    private final DialogueRepository dialogueRepository;

    /** 首次 flush 延迟（秒） */
    private final long initialFlushDelaySeconds;

    /** 固定 flush 间隔（秒） */
    private final long flushIntervalSeconds;

    /**
     * 构造方法（使用默认写库节奏）
     *
     * @param dialogueRepository 对话轮次仓储
     */
    public BatchFlushManager(DialogueRepository dialogueRepository) {
        this(dialogueRepository, DEFAULT_INITIAL_FLUSH_DELAY_SECONDS, DEFAULT_FLUSH_INTERVAL_SECONDS);
    }

    /**
     * 构造方法（自定义写库节奏）
     *
     * @param dialogueRepository      对话轮次仓储
     * @param initialFlushDelaySeconds 首次 flush 延迟（秒）
     * @param flushIntervalSeconds    固定 flush 间隔（秒）
     */
    public BatchFlushManager(DialogueRepository dialogueRepository,
                             long initialFlushDelaySeconds,
                             long flushIntervalSeconds) {
        this.dialogueRepository = dialogueRepository;
        this.initialFlushDelaySeconds = initialFlushDelaySeconds;
        this.flushIntervalSeconds = flushIntervalSeconds;
    }

    /**
     * 启动定时 flush
     * <p>首次 flush 延迟 {@code initialFlushDelaySeconds}，之后按 {@code flushIntervalSeconds} 周期 flush，
     * 将当前消息列表写入数据库（状态保持 RUNNING），若已停止（最终 flush 后）则不再调度新任务。</p>
     *
     * @param dialogueId 对话轮次 ID
     * @param sessionId  会话 ID（仅用于日志）
     * @param context    收集器上下文
     */
    public void start(Long dialogueId, String sessionId, EventAdapter.CollectorContext context) {
        executor.scheduleAtFixedRate(
                () -> {
                    if (!stopped) {
                        flush(dialogueId, sessionId, context, DialogueStatus.RUNNING);
                    }
                },
                initialFlushDelaySeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 最终 flush：先标记停止（阻止新定时任务提交），再提交最终任务。
     * <p>单线程 FIFO 保证最终任务排在所有已提交的定时任务之后执行，不丢数据。</p>
     *
     * @param dialogueId  对话轮次 ID
     * @param sessionId   会话 ID（仅用于日志）
     * @param context     收集器上下文
     * @param finalStatus 终态状态（COMPLETED / FAILED / CANCELLED）
     */
    public void finalFlush(Long dialogueId, String sessionId, EventAdapter.CollectorContext context,
                           DialogueStatus finalStatus) {
        stopped = true;
        executor.submit(() -> flush(dialogueId, sessionId, context, finalStatus));
    }

    /**
     * 关闭调度器，释放线程资源。
     * <p>shutdown 会等待已提交任务（含最终 flush）执行完毕，不中断进行中的写库。</p>
     */
    public void close() {
        stopped = true;
        executor.shutdown();
    }

    /**
     * 执行一次 flush
     * <p>锁内复制消息列表快照，锁外序列化写库，避免持有锁期间进行 IO。</p>
     *
     * @param dialogueId 对话轮次 ID
     * @param sessionId  会话 ID（仅用于日志）
     * @param context    收集器上下文
     * @param status     写入时的对话状态
     */
    private void flush(Long dialogueId, String sessionId, EventAdapter.CollectorContext context,
                       DialogueStatus status) {
        List<DialogueMessage> snapshot;
        synchronized (context) {
            snapshot = List.copyOf(context.getMessages());
        }
        try {
            dialogueRepository.updateMessages(dialogueId, snapshot, status);
        } catch (Exception e) {
            log.error("BatchFlushManager: failed to flush messages for dialogue={}, session={}",
                    dialogueId, sessionId, e);
        }
    }
}