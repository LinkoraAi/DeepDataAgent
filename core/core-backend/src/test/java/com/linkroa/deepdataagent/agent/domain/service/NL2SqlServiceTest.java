package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.service.port.SqlGenerationPort;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * NL2SqlService 单元测试
 * <p>测试 NL2SQL 转换服务在成功、失败重试、达到上限抛异常等场景下的行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class NL2SqlServiceTest {

    @Mock
    private SqlGenerationPort sqlGenerationPort;

    @Mock
    private SqlValidationPort sqlValidationPort;

    private NL2SqlService service;

    @BeforeEach
    void setUp() {
        service = new NL2SqlService(sqlGenerationPort, sqlValidationPort, 3);
    }

    @Test
    void should_returnSql_when_convert_given_generationAndValidationSucceed() {
        // given
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn("SELECT * FROM t");
        doNothing().when(sqlValidationPort).validate("SELECT * FROM t");

        // when
        String result = service.convert(1L, "查询用户", "schema", "mysql");

        // then
        assertEquals("SELECT * FROM t", result);
        verify(sqlGenerationPort, times(1))
                .generate(1L, "查询用户", "schema", "mysql", null);
        verify(sqlValidationPort).validate("SELECT * FROM t");
    }

    @Test
    void should_returnSql_when_convert_given_firstAttemptFailsThenSucceeds() {
        // given
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("解析失败"))
                .thenReturn("SELECT * FROM t");
        doNothing().when(sqlValidationPort).validate("SELECT * FROM t");

        // when
        String result = service.convert(1L, "查询用户", "schema", "mysql");

        // then
        assertEquals("SELECT * FROM t", result);
        verify(sqlGenerationPort, times(2))
                .generate(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void should_throwException_when_convert_given_validationAlwaysFails() {
        // given
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn("SELECT * FROM t");
        doThrow(new RuntimeException("非法 SQL")).when(sqlValidationPort).validate(anyString());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.convert(1L, "查询用户", "schema", "mysql")
        );
        assertTrue(exception.getMessage().contains("已重试 3 次"));
        verify(sqlGenerationPort, times(3))
                .generate(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void should_throwException_when_convert_given_generationAlwaysFails() {
        // given
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("模型不可用"));

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.convert(1L, "查询用户", "schema", "mysql")
        );
        assertTrue(exception.getMessage().contains("已重试 3 次"));
    }

    @Test
    void should_passSessionIdAndRetryContext_when_convert_given_sessionIdAndFailure() {
        // given
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("解析失败"))
                .thenReturn("SELECT * FROM t");
        doNothing().when(sqlValidationPort).validate("SELECT * FROM t");

        // when
        String result = service.convert(1L, "查询用户", "schema", "mysql", "sess-1");

        // then
        assertEquals("SELECT * FROM t", result);
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(sqlGenerationPort, times(2))
                .generate(eq(1L), eq("查询用户"), contextCaptor.capture(), eq("mysql"), sessionCaptor.capture());
        // 第一次使用原始 schema，第二次使用带错误信息的上下文
        assertEquals("schema", contextCaptor.getAllValues().get(0));
        assertTrue(contextCaptor.getAllValues().get(1).contains("解析失败"));
        // 会话 ID 始终透传
        assertEquals("sess-1", sessionCaptor.getAllValues().get(0));
        assertEquals("sess-1", sessionCaptor.getAllValues().get(1));
    }

    @Test
    void should_includeInvalidSqlInRetryContext_when_convert_given_validationFails() {
        // given - 生成成功但校验失败，重试上下文应包含违规 SQL 与原因
        String invalidSql = "SELECT * FROM users; SELECT * FROM orders";
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(invalidSql);
        doThrow(new RuntimeException("禁止多语句执行")).when(sqlValidationPort).validate(anyString());

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> service.convert(1L, "查询用户", "schema", "mysql"));

        // then - 重试上下文应包含违规 SQL 原文与修正指令（首次为原始 schema，后续重试才携带）
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(sqlGenerationPort, times(3))
                .generate(eq(1L), eq("查询用户"), contextCaptor.capture(), eq("mysql"), any());
        // 首次使用原始 schema
        assertEquals("schema", contextCaptor.getAllValues().get(0));
        // 后续每次重试都携带违规 SQL 与原因
        for (int i = 1; i < contextCaptor.getAllValues().size(); i++) {
            String ctx = contextCaptor.getAllValues().get(i);
            assertTrue(ctx.contains(invalidSql));
            assertTrue(ctx.contains("禁止多语句执行"));
            assertTrue(ctx.contains("只输出单条 SELECT 语句"));
        }
    }

    @Test
    void should_notAccumulateRetryContext_when_convert_given_repeatedGenerationFailures() {
        // given - 生成阶段持续失败，重试上下文应只保留最新一次错误，不累积历史
        when(sqlGenerationPort.generate(any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("模型不可用"));

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> service.convert(1L, "查询用户", "schema", "mysql"));

        // then - 每次重试上下文均从 schemaInfo 重建，仅含最新一次错误（首次为原始 schema）
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(sqlGenerationPort, times(3))
                .generate(eq(1L), eq("查询用户"), contextCaptor.capture(), eq("mysql"), any());
        assertEquals("schema", contextCaptor.getAllValues().get(0));
        for (int i = 1; i < contextCaptor.getAllValues().size(); i++) {
            String ctx = contextCaptor.getAllValues().get(i);
            assertTrue(ctx.startsWith("schema"));
            assertTrue(ctx.contains("模型不可用"));
            // 不累积多次错误信息
            assertEquals(1, countOccurrences(ctx, "模型不可用"));
        }
    }

    /**
     * 统计子串在字符串中出现的次数。
     */
    private int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}