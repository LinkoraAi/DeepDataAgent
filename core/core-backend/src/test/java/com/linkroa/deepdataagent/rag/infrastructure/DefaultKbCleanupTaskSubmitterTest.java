package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.rag.application.service.KbCleanupOrchestrationService;
import com.linkroa.deepdataagent.rag.application.task.CleanupInFlightRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultKbCleanupTaskSubmitter} 契约适配单测。
 * <p>覆盖：投递即在虚拟线程执行器上登记清退任务（在飞键为 {@code kb:{kbId}}、任务体委托清退编排服务）、
 * 在飞去重命中幂等跳过、执行器停机异常按契约原样上抛（由调用方仅 ERROR 留痕）、kbId 为空参数防御。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultKbCleanupTaskSubmitterTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 61L;

    /** 知识库清退在飞键 */
    private static final String KB_IN_FLIGHT_KEY = "kb:61";

    @Mock
    private DeletionCleanupTaskExecutor deletionCleanupTaskExecutor;

    @Mock
    private KbCleanupOrchestrationService kbCleanupOrchestrationService;

    @InjectMocks
    private DefaultKbCleanupTaskSubmitter submitter;

    @Test
    void should_submitCleanupTaskWithKbKey_when_submit_given_knowledgeBaseId() {
        // given：执行器受理新任务
        when(deletionCleanupTaskExecutor.submit(anyString(), any(Runnable.class))).thenReturn(true);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        // when
        submitter.submit(KB_ID);

        // then：以 kb: 前缀在飞键登记
        verify(deletionCleanupTaskExecutor).submit(keyCaptor.capture(), taskCaptor.capture());
        assertEquals(CleanupInFlightRegistry.kbKey(KB_ID), keyCaptor.getValue());

        // when：执行捕获到的任务体（模拟虚拟线程内异步推进）
        taskCaptor.getValue().run();

        // then：任务体委托整库清退编排服务
        verify(kbCleanupOrchestrationService).cleanup(KB_ID);
    }

    @Test
    void should_skipIdempotently_when_submit_given_taskAlreadyInFlight() {
        // given：该库清退任务已在飞，执行器去重返回 false
        when(deletionCleanupTaskExecutor.submit(eq(KB_IN_FLIGHT_KEY), any(Runnable.class))).thenReturn(false);

        // when // then：幂等跳过——不抛异常，任务体未被执行故不触达编排服务
        assertDoesNotThrow(() -> submitter.submit(KB_ID));
        verify(deletionCleanupTaskExecutor).submit(eq(KB_IN_FLIGHT_KEY), any(Runnable.class));
        verifyNoInteractions(kbCleanupOrchestrationService);
    }

    @Test
    void should_propagateIllegalState_when_submit_given_executorShuttingDown() {
        // given：执行器停机拒收（契约要求原样上抛，由受理侧仅 ERROR 留痕、库停留 DELETING）
        when(deletionCleanupTaskExecutor.submit(anyString(), any(Runnable.class)))
                .thenThrow(new IllegalStateException("删除清退执行器已停机"));

        // when // then
        assertThrows(IllegalStateException.class, () -> submitter.submit(KB_ID));
        verifyNoInteractions(kbCleanupOrchestrationService);
    }

    @Test
    void should_throwIllegalArgument_when_submit_given_nullKnowledgeBaseId() {
        // when // then：空ID即拒绝，不触碰执行器（避免生成 kb:null 脏键）
        assertThrows(IllegalArgumentException.class, () -> submitter.submit(null));
        verifyNoInteractions(deletionCleanupTaskExecutor);
        verifyNoInteractions(kbCleanupOrchestrationService);
    }
}
