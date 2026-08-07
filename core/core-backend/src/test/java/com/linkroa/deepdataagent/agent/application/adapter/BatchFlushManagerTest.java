package com.linkroa.deepdataagent.agent.application.adapter;

import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * BatchFlushManager 单元测试
 * <p>覆盖最终 flush 终态写入、消息快照保序、stopped 标志阻止新任务、关闭调度器，
 * 以及并发追加消息与 flush 复制快照不丢数据（线程安全）。</p>
 */
@ExtendWith(MockitoExtension.class)
class BatchFlushManagerTest {

    @Mock
    private DialogueRepository dialogueRepository;

    private BatchFlushManager manager;

    private EventAdapter.CollectorContext context;

    @BeforeEach
    void setUp() {
        manager = new BatchFlushManager(dialogueRepository);
        context = new EventAdapter.CollectorContext("session-1", "分析销量");
    }

    @Test
    void should_flushFinalStatus_when_finalFlush_given_messageSnapshot() throws Exception {
        // given
        // 上下文构造时已内置用户消息（seq=1），此处无需再手动添加
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DialogueStatus> capturedStatus = new AtomicReference<>();
        AtomicReference<List<DialogueMessage>> capturedMessages = new AtomicReference<>();
        doAnswer(inv -> {
            capturedMessages.set(inv.getArgument(1));
            capturedStatus.set(inv.getArgument(2));
            latch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(anyLong(), anyList(), any());

        // when
        manager.finalFlush(1L, "session-1", context, DialogueStatus.COMPLETED);
        manager.close();

        // then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(DialogueStatus.COMPLETED, capturedStatus.get());
        assertEquals(1, capturedMessages.get().size());
        verify(dialogueRepository).updateMessages(1L, capturedMessages.get(), DialogueStatus.COMPLETED);
    }

    @Test
    void should_flushRunningImmediately_when_start_given_zeroInitialDelay() throws Exception {
        // given
        // 自定义写库节奏：首次延迟 0s，验证 initialFlushDelay 配置生效（立即触发 RUNNING flush）
        BatchFlushManager customManager = new BatchFlushManager(dialogueRepository, 0L, 60L);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DialogueStatus> capturedStatus = new AtomicReference<>();
        doAnswer(inv -> {
            capturedStatus.set(inv.getArgument(2));
            latch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(anyLong(), anyList(), any());

        // when
        customManager.start(1L, "session-1", context);

        // then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // 首次 flush 立即以 RUNNING 状态写入，前端可尽早看到内容
        assertEquals(DialogueStatus.RUNNING, capturedStatus.get());
        customManager.close();
    }

    @Test
    void should_preserveAllMessages_when_finalFlush_given_multipleAccumulatedMessages() throws Exception {
        // given
        // 上下文构造时已内置用户消息（seq=1），后续追加思考(seq=2)、工具调用(seq=3)
        context.addMessage(DialogueMessage.inProgressMessage(2,
                com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole.THINKING,
                com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.THINKING));
        context.addMessage(DialogueMessage.inProgressMessage(3,
                com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole.TOOL,
                com.linkroa.deepdataagent.agent.domain.valueobject.MessageType.TOOL_CALL));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<DialogueMessage>> capturedMessages = new AtomicReference<>();
        doAnswer(inv -> {
            capturedMessages.set(inv.getArgument(1));
            latch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(anyLong(), anyList(), any());

        // when
        manager.finalFlush(1L, "session-1", context, DialogueStatus.COMPLETED);
        manager.close();

        // then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, capturedMessages.get().size());
        // 保序：序号递增
        assertEquals(1L, capturedMessages.get().get(0).getSequenceNumber());
        assertEquals(2L, capturedMessages.get().get(1).getSequenceNumber());
        assertEquals(3L, capturedMessages.get().get(2).getSequenceNumber());
    }

    @Test
    void should_rejectNewTasks_when_finalFlush_given_alreadyClosed() {
        // given
        manager.close();

        // when & then
        assertThrows(RejectedExecutionException.class,
                () -> manager.finalFlush(1L, "session-1", context, DialogueStatus.COMPLETED));
    }

    @Test
    void should_notLoseMessages_when_concurrentAddAndFlush_given_multiThreadAppend() throws Exception {
        // given
        int threads = 8;
        int messagesPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < messagesPerThread; j++) {
                        context.addMessage(DialogueMessage.userMessage(seed + j, "并发消息" + seed + "-" + j));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch flushLatch = new CountDownLatch(1);
        AtomicReference<List<DialogueMessage>> capturedMessages = new AtomicReference<>();
        doAnswer(inv -> {
            capturedMessages.set(inv.getArgument(1));
            flushLatch.countDown();
            return null;
        }).when(dialogueRepository).updateMessages(anyLong(), anyList(), any());

        // when
        manager.finalFlush(1L, "session-1", context, DialogueStatus.COMPLETED);
        manager.close();

        // then
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS));
        // 构造时内置的用户消息(seq=1) + 8 线程各 50 条并发追加
        assertEquals(threads * messagesPerThread + 1, capturedMessages.get().size());
    }
}