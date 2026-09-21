package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.rag.application.task.IngestionTaskQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultIngestionCancellationApi} 契约适配器单测。
 * <p>覆盖：有效入参原样透传队列终止确认并回传结果、空超时退化为
 * {@code Duration.ZERO} 仅探测委托、空文档ID视为无任务直接返回 true 且与队列零交互。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultIngestionCancellationApiTest {

    /** 测试文档主键 */
    private static final Long DOCUMENT_ID = 42L;

    /** 排队撤下与在飞注册表持有方（摄入任务队列）Mock */
    @Mock
    private IngestionTaskQueue ingestionTaskQueue;

    /** 被测契约实现 */
    @InjectMocks
    private DefaultIngestionCancellationApi cancellationApi;

    /**
     * 场景：有效文档ID与超时，队列在时限内确认摄入任务终止（撤下排队或等待在飞自灭）。
     * 预期：入参原样透传，返回 true，除一次委托外无其它交互。
     */
    @Test
    void should_delegateWithSameArguments_when_awaitTermination_given_documentIdAndTimeout() {
        // given
        Duration timeout = Duration.ofSeconds(30L);
        when(ingestionTaskQueue.awaitTermination(DOCUMENT_ID, timeout)).thenReturn(true);

        // when
        boolean terminated = cancellationApi.awaitTermination(DOCUMENT_ID, timeout);

        // then
        assertTrue(terminated, "队列确认终止时契约实现应原样回传 true");
        verify(ingestionTaskQueue).awaitTermination(DOCUMENT_ID, timeout);
        verifyNoMoreInteractions(ingestionTaskQueue);
    }

    /**
     * 场景：文档ID有效但超时为空，队列零时长探测到在飞任务未终止返回 false。
     * 预期：超时退化为 {@code Duration.ZERO} 委托，等待超时 false 原样回传（不抛异常，调用方决定继续清退）。
     */
    @Test
    void should_delegateZeroTimeout_when_awaitTermination_given_nullTimeout() {
        // given
        when(ingestionTaskQueue.awaitTermination(DOCUMENT_ID, Duration.ZERO)).thenReturn(false);

        // when
        boolean terminated = cancellationApi.awaitTermination(DOCUMENT_ID, null);

        // then
        assertFalse(terminated, "等待超时应原样回传 false 而非抛异常");
        verify(ingestionTaskQueue).awaitTermination(DOCUMENT_ID, Duration.ZERO);
    }

    /**
     * 场景：文档ID为空。
     * 预期：视为「无任务」直接返回 true，与队列零交互（不触达任何等待逻辑）。
     */
    @Test
    void should_returnTrueWithoutDelegation_when_awaitTermination_given_nullDocumentId() {
        // when
        boolean terminated = cancellationApi.awaitTermination(null, Duration.ofSeconds(1L));

        // then
        assertTrue(terminated, "空文档ID应视为无任务直接返回 true");
        verifyNoInteractions(ingestionTaskQueue);
    }
}
