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
 * NL2SqlClient 单元测试
 * <p>验证 NL2SQL 客户端将用户问题与 schema 组装为提示词，并委托 {@link LLMInvoker} 调用。</p>
 */
@ExtendWith(MockitoExtension.class)
class NL2SqlClientTest {

    @Mock
    private LLMInvoker llmInvoker;

    @InjectMocks
    private NL2SqlClient nl2SqlClient;

    @Test
    void should_returnSql_when_generateSql_given_validInput() {
        // given
        when(llmInvoker.invoke(eq(1L), anyString(), anyString(), isNull())).thenReturn("SELECT * FROM users");

        // when
        String result = nl2SqlClient.generateSql(1L, "查询所有用户", "表 users 包含 name 字段", "MySQL", null);

        // then
        assertEquals("SELECT * FROM users", result);
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).invoke(eq(1L), systemCaptor.capture(), userCaptor.capture(), isNull());
        // 系统提示词注入 SQL 方言，用户提示词包含 schema 与问题
        assertTrue(systemCaptor.getValue().contains("MySQL"));
        assertTrue(userCaptor.getValue().contains("表 users 包含 name 字段"));
        assertTrue(userCaptor.getValue().contains("查询所有用户"));
    }

    @Test
    void should_passSessionId_when_generateSql_given_sessionId() {
        // given
        when(llmInvoker.invoke(eq(1L), anyString(), anyString(), eq("sess-1")))
                .thenReturn("SELECT 1");

        // when
        String result = nl2SqlClient.generateSql(1L, "查询数量", "schema", "ClickHouse", "sess-1");

        // then
        assertEquals("SELECT 1", result);
        verify(llmInvoker).invoke(eq(1L), anyString(), anyString(), eq("sess-1"));
    }
}