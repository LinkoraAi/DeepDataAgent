package com.linkroa.deepdataagent.agent.application.context;

import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.infrastructure.collector.AnalysisEventBuffer;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RunningAnalysisRegistry 单元测试
 * <p>覆盖注册、获取、移除、isRunning、会话 ID 集合、数量以及并发读写。</p>
 */
class RunningAnalysisRegistryTest {

    private RunningAnalysisRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RunningAnalysisRegistry();
    }

    private RunningExecution execution(String sessionId) {
        HarnessAgent agent = mock(HarnessAgent.class);
        Disposable subscription = mock(Disposable.class);
        DataAnalysisCommand command = new DataAnalysisCommand(sessionId, 200L, "100", "分析销量", false, false);
        return new RunningExecution(1L, agent, subscription, command, new AnalysisEventBuffer());
    }

    @Test
    void should_registerAndGet_when_executionAdded_given_validSession() {
        // given
        RunningExecution exec = execution("session-1");

        // when
        registry.register("session-1", exec);

        // then
        assertSame(exec, registry.get("session-1"));
        assertEquals(1, registry.size());
        assertTrue(registry.isRunning("session-1"));
    }

    @Test
    void should_ignoreNull_when_register_given_nullSessionOrExecution() {
        // given
        RunningExecution exec = execution("session-1");

        // when
        registry.register(null, exec);
        registry.register("session-2", null);

        // then
        assertEquals(0, registry.size());
        assertFalse(registry.isRunning(null));
    }

    @Test
    void should_remove_when_removed_given_existingSession() {
        // given
        registry.register("session-1", execution("session-1"));

        // when
        registry.remove("session-1");

        // then
        assertNull(registry.get("session-1"));
        assertFalse(registry.isRunning("session-1"));
        assertEquals(0, registry.size());
    }

    @Test
    void should_ignoreRemove_when_removed_given_nullSession() {
        // when
        registry.remove(null);

        // then
        assertEquals(0, registry.size());
    }

    @Test
    void should_returnRunningSessionIds_when_queried_given_multipleSessions() {
        // given
        registry.register("session-1", execution("session-1"));
        registry.register("session-2", execution("session-2"));

        // when
        var ids = registry.getRunningSessionIds();

        // then
        assertEquals(2, ids.size());
        assertTrue(ids.contains("session-1"));
        assertTrue(ids.contains("session-2"));
    }

    @Test
    void should_surviveConcurrentReadWrite_when_accessedConcurrently_given_manyThreads() throws Exception {
        // given
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        // when
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    String sessionId = "session-" + (base + i);
                    registry.register(sessionId, execution(sessionId));
                    assertTrue(registry.isRunning(sessionId));
                    registry.remove(sessionId);
                    assertFalse(registry.isRunning(sessionId));
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // then
        assertEquals(0, registry.size());
    }
}