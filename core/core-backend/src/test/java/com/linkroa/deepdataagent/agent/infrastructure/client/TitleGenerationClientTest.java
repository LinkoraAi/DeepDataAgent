package com.linkroa.deepdataagent.agent.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TitleGenerationClient 单元测试
 * <p>验证会话标题客户端组装提示词并委托 {@link LLMInvoker} 调用，调用失败时返回 null 降级。</p>
 */
@ExtendWith(MockitoExtension.class)
class TitleGenerationClientTest {

    @Mock
    private LLMInvoker llmInvoker;

    @InjectMocks
    private TitleGenerationClient titleGenerationClient;

    @Test
    void should_returnTitle_when_generateTitle_given_validInput() {
        // given
        when(llmInvoker.invoke(eq(1L), anyString(), anyString())).thenReturn("月度销售分析");

        // when
        String result = titleGenerationClient.generateTitle(1L, "分析上月各区域销量");

        // then
        assertEquals("月度销售分析", result);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).invoke(eq(1L), anyString(), userCaptor.capture());
        assertTrue(userCaptor.getValue().contains("分析上月各区域销量"));
    }

    @Test
    void should_returnNull_when_generateTitle_given_llmFailure() {
        // given
        when(llmInvoker.invoke(eq(1L), anyString(), anyString()))
                .thenThrow(new RuntimeException("模型不可用"));

        // when
        String result = titleGenerationClient.generateTitle(1L, "分析销量");

        // then: 失败时返回 null，由调用方使用降级标题
        assertNull(result);
    }
}