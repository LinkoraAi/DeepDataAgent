package com.linkroa.deepdataagent.agent.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChartConfigGenerationClient 单元测试
 * <p>验证图表配置客户端将数据结构与用户意图组装为提示词，并委托 {@link LLMInvoker} 调用。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChartConfigGenerationClientTest {

    @Mock
    private LLMInvoker llmInvoker;

    @InjectMocks
    private ChartConfigGenerationClient chartConfigGenerationClient;

    @Test
    void should_returnChartConfig_when_generateChartConfig_given_validInput() {
        // given
        when(llmInvoker.invoke(eq(1L), anyString(), anyString(), isNull())).thenReturn("{\"type\": \"bar\"}");

        // when
        String result = chartConfigGenerationClient.generateChartConfig(1L, "含月份与销量两列", "展示月度销量");

        // then
        assertEquals("{\"type\": \"bar\"}", result);
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).invoke(eq(1L), systemCaptor.capture(), userCaptor.capture(), isNull());
        assertTrue(systemCaptor.getValue().contains("ECharts"));
        assertTrue(userCaptor.getValue().contains("含月份与销量两列"));
        assertTrue(userCaptor.getValue().contains("展示月度销量"));
    }

    @Test
    void should_passSessionId_when_generateChartConfig_given_sessionId() {
        // given
        when(llmInvoker.invoke(eq(1L), anyString(), anyString(), eq("sess-1")))
                .thenReturn("{\"type\": \"line\"}");

        // when
        String result = chartConfigGenerationClient.generateChartConfig(1L, "按天趋势", "查询每日销量", "sess-1");

        // then
        assertEquals("{\"type\": \"line\"}", result);
        verify(llmInvoker).invoke(eq(1L), anyString(), anyString(), eq("sess-1"));
    }
}