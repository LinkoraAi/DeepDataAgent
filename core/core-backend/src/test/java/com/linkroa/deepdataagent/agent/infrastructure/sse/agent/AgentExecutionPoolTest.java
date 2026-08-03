package com.linkroa.deepdataagent.agent.infrastructure.sse.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link AgentExecutionPool} 的单元测试
 * <p>验证 Agent 执行池的核心功能：任务执行、容量控制、计数器管理、并发处理。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentExecutionPoolTest {

    @Mock
    private AgentExecutionPoolProperties properties;

    private AgentExecutionPool pool;

    @BeforeEach
    void setUp() {
        // 默认配置：非虚拟线程模式（CachedThreadPool 便于测试），最大活跃会话数为 5
        lenient().when(properties.virtualThreads()).thenReturn(false);
        lenient().when(properties.maxActiveSessions()).thenReturn(5);
        pool = new AgentExecutionPool(properties);
    }

    @Test
    void should_executeTaskAndDecrementCounter_when_execute_given_availableCapacity() throws Exception {
        // given
        CountDownLatch taskLatch = new CountDownLatch(1);
        AtomicBoolean taskExecuted = new AtomicBoolean(false);

        // when
        boolean accepted = pool.execute("session1", () -> {
            taskExecuted.set(true);
            taskLatch.countDown();
        });

        // then
        assertTrue(accepted, "Task should be accepted when capacity is available");
        assertTrue(taskLatch.await(5, TimeUnit.SECONDS), "Task should complete within timeout");
        assertTrue(taskExecuted.get(), "Task should have been executed");
        assertEquals(0, pool.getActiveSessionCount(), "Active session count should return to 0 after task completion");
    }

    @Test
    void should_returnFalse_when_execute_given_maxActiveSessionsReached() throws Exception {
        // given
        when(properties.maxActiveSessions()).thenReturn(1);
        pool = new AgentExecutionPool(properties);
        CountDownLatch blockLatch = new CountDownLatch(1);

        // 第一个任务占用会话槽位（任务被阻塞，不会调用 onComplete）
        pool.execute("session1", () -> {
            try {
                blockLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 确保第一个任务已提交，计数器已递增
        assertEquals(1, pool.getActiveSessionCount());

        // when - 第二个任务因达到上限被拒绝
        boolean accepted = pool.execute("session2", () -> { });

        // then
        assertFalse(accepted, "Task should be rejected when max active sessions reached");
        assertEquals(1, pool.getActiveSessionCount(), "Active session count should remain unchanged");

        // 清理阻塞任务
        blockLatch.countDown();
    }

    @Test
    void should_decrementCounter_when_onComplete() throws Exception {
        // given
        CountDownLatch blockLatch = new CountDownLatch(1);
        pool.execute("session1", () -> {
            try {
                blockLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 任务被阻塞，计数器为 1
        assertEquals(1, pool.getActiveSessionCount());

        // when - 直接调用 onComplete 模拟完成
        pool.onComplete("session1");

        // then
        assertEquals(0, pool.getActiveSessionCount(), "Counter should decrement by 1 after onComplete");

        // 清理阻塞任务
        blockLatch.countDown();
    }

    @Test
    void should_decrementCounter_when_onCancel() throws Exception {
        // given
        CountDownLatch blockLatch = new CountDownLatch(1);
        pool.execute("session1", () -> {
            try {
                blockLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 任务被阻塞，计数器为 1
        assertEquals(1, pool.getActiveSessionCount());

        // when - 直接调用 onCancel 模拟取消
        pool.onCancel("session1");

        // then
        assertEquals(0, pool.getActiveSessionCount(), "Counter should decrement by 1 after onCancel");

        // 清理阻塞任务
        blockLatch.countDown();
    }

    @Test
    void should_returnOne_when_getActiveSessionCount_given_oneBlockedTask() throws Exception {
        // given
        CountDownLatch blockLatch = new CountDownLatch(1);
        pool.execute("session1", () -> {
            try {
                blockLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // when
        int count = pool.getActiveSessionCount();

        // then
        assertEquals(1, count, "Active session count should be 1 when one task is blocked");

        // 清理
        blockLatch.countDown();
    }

    @Test
    void should_handleConcurrentExecution_when_multipleTasksSubmitted() throws Exception {
        // given
        int taskCount = 10;
        CountDownLatch completionLatch = new CountDownLatch(taskCount);

        // when
        for (int i = 0; i < taskCount; i++) {
            String sessionId = "session" + i;
            boolean accepted = pool.execute(sessionId, completionLatch::countDown);
            assertTrue(accepted, "All tasks should be accepted when within capacity limit");
        }

        // then - 所有任务应在超时前完成
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "All tasks should complete within timeout");
        assertEquals(0, pool.getActiveSessionCount(), "Active session count should return to 0 after all tasks complete");
    }
}