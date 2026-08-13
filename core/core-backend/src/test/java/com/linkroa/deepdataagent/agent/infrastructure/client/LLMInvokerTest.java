package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.exception.AnalysisCancelledException;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLMInvoker 单元测试
 * <p>覆盖流式文本累积、markdown 清理、空响应校验、异常直抛、用户取消语义及瞬时错误重试等行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class LLMInvokerTest {

    @Mock
    private ChatModelManager chatModelManager;

    @Mock
    private ChatModelBase chatModel;

    @InjectMocks
    private LLMInvoker llmInvoker;

    /**
     * 构造仅包含一个 TextBlock 的 ChatResponse mock
     *
     * @param text 文本内容
     * @return ChatResponse mock
     */
    private ChatResponse textResponse(String text) {
        TextBlock textBlock = org.mockito.Mockito.mock(TextBlock.class);
        when(textBlock.getText()).thenReturn(text);
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        when(response.getContent()).thenReturn(List.of(textBlock));
        return response;
    }

    @Test
    void should_returnText_when_invoke_given_textResponse() {
        // given
        when(chatModelManager.getChatModel(1L)).thenReturn(chatModel);
        Flux<ChatResponse> responseFlux = Flux.just(textResponse("SELECT 1"));
        when(chatModel.stream(anyList(), any(), any())).thenReturn(responseFlux);

        // when
        String result = llmInvoker.invoke(1L, "system", "user");

        // then
        assertEquals("SELECT 1", result);
        verify(chatModelManager).getChatModel(1L);
    }

    @Test
    void should_concatenateTextBlocks_when_invoke_given_multipleBlocksIncludingNonText() {
        // given
        TextBlock first = org.mockito.Mockito.mock(TextBlock.class);
        when(first.getText()).thenReturn("其中 ");
        TextBlock second = org.mockito.Mockito.mock(TextBlock.class);
        when(second.getText()).thenReturn("结果为 100");
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        // 混入非文本块（ToolUseBlock），应被过滤忽略
        when(response.getContent()).thenReturn(List.of(
                org.mockito.Mockito.mock(ToolUseBlock.class), first, second));
        when(chatModelManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any())).thenReturn(Flux.just(response));

        // when
        String result = llmInvoker.invoke(1L, "system", "user");

        // then: 仅文本块参与拼接
        assertEquals("其中 结果为 100", result);
    }

    @Test
    void should_stripMarkdownFence_when_invoke_given_codeFenceResponse() {
        // given
        when(chatModelManager.getChatModel(1L)).thenReturn(chatModel);
        Flux<ChatResponse> responseFlux = Flux.just(textResponse("```sql\nSELECT 1\n```"));
        when(chatModel.stream(anyList(), any(), any())).thenReturn(responseFlux);

        // when
        String result = llmInvoker.invoke(1L, "system", "user");

        // then: 清理 markdown 代码块围栏
        assertEquals("SELECT 1", result);
    }

    @Test
    void should_throwException_when_invoke_given_emptyResponse() {
        // given
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        when(response.getContent()).thenReturn(List.of());
        when(chatModelManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any())).thenReturn(Flux.just(response));

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> llmInvoker.invoke(1L, "system", "user")
        );
        assertTrue(exception.getMessage().contains("LLM 返回空响应"));
        // 空响应属于业务失败，不重试
        verify(chatModelManager, times(1)).getChatModel(1L);
        verify(chatModelManager, never()).evictCache(1L);
    }

    @Test
    void should_rethrowException_when_invoke_given_deepDataAgentExceptionFromProvider() {
        // given
        DeepDataAgentException providerError = new DeepDataAgentException("provider down");
        when(chatModelManager.getChatModel(1L)).thenThrow(providerError);

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> llmInvoker.invoke(1L, "system", "user")
        );
        assertEquals("provider down", exception.getMessage());
        // DeepDataAgentException 直接抛出，不重试也不清缓存
        verify(chatModelManager, times(1)).getChatModel(1L);
        verify(chatModelManager, never()).evictCache(1L);
    }

    @Test
    void should_throwCancelledException_when_invoke_given_interrupted() {
        // given
        when(chatModelManager.getChatModel(1L)).thenAnswer(invocation -> {
            throw new InterruptedException("cancelled");
        });
        try {
            // when & then
            AnalysisCancelledException exception = assertThrows(
                    AnalysisCancelledException.class,
                    () -> llmInvoker.invoke(1L, "system", "user")
            );
            assertTrue(exception.getMessage().contains("LLM 调用被取消"));
            // 中断标志被恢复（assert 会同时清除标志，避免污染测试线程）
            assertTrue(Thread.interrupted());
            // 用户取消不重试
            verify(chatModelManager, times(1)).getChatModel(1L);
            verify(chatModelManager, never()).evictCache(1L);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void should_retryAndSucceed_when_invoke_given_transientErrorThenSuccess() {
        // given
        when(chatModelManager.getChatModel(1L)).thenReturn(chatModel);
        Flux<ChatResponse> errorFlux = Flux.error(new RuntimeException("connection reset"));
        Flux<ChatResponse> successFlux = Flux.just(textResponse("重试成功"));
        when(chatModel.stream(anyList(), any(), any())).thenReturn(errorFlux, successFlux);

        // when
        String result = llmInvoker.invoke(1L, "system", "user");

        // then: 瞬时错误后清缓存重建连接并重试成功
        assertEquals("重试成功", result);
        verify(chatModelManager, times(2)).getChatModel(1L);
        verify(chatModelManager).evictCache(1L);
    }

    @Test
    void should_throwException_when_invoke_given_nonTransientError() {
        // given
        when(chatModelManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("server internal error")));

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> llmInvoker.invoke(1L, "system", "user")
        );
        assertTrue(exception.getMessage().contains("LLM 调用失败"));
        // 非瞬时错误直接放弃，不重试
        verify(chatModelManager, times(1)).getChatModel(1L);
        verify(chatModelManager, never()).evictCache(1L);
    }
}