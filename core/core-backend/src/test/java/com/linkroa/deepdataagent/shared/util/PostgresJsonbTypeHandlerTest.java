package com.linkroa.deepdataagent.shared.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PostgresJsonbTypeHandler 单元测试。
 * <p>覆盖写入方向（String → PGobject(jsonb)）、读取方向（PGobject → String）以及
 * null 值、非 PGobject 驱动对象、异常传播等分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class PostgresJsonbTypeHandlerTest {

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private CallableStatement callableStatement;

    private PostgresJsonbTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PostgresJsonbTypeHandler();
    }

    // ==================== setNonNullParameter（写方向） ====================

    @Test
    void should_setJsonbPgObject_when_setNonNullParameter_given_validJsonText() throws Exception {
        // given
        String json = "{\"name\":\"test\"}";

        // when
        handler.setNonNullParameter(preparedStatement, 1, json, null);

        // then - 包装为 jsonb 类型的 PGobject 后写入
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(preparedStatement).setObject(eq(1), captor.capture());
        assertInstanceOf(PGobject.class, captor.getValue());
        PGobject pgObject = (PGobject) captor.getValue();
        assertEquals("jsonb", pgObject.getType());
        assertEquals(json, pgObject.getValue());
    }

    @Test
    void should_propagateSqlException_when_setNonNullParameter_given_driverFailure() throws Exception {
        // given
        doThrow(new SQLException("driver error")).when(preparedStatement).setObject(anyInt(), any());

        // when & then
        assertThrows(SQLException.class, () -> handler.setNonNullParameter(preparedStatement, 1, "{}", null));
    }

    // ==================== getNullableResult（读方向，按列名） ====================

    @Test
    void should_returnJsonText_when_getNullableResultByColumnName_given_pgObject() throws Exception {
        // given
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue("{\"id\":1}");
        when(resultSet.getObject("config")).thenReturn(pgObject);

        // when
        String result = handler.getNullableResult(resultSet, "config");

        // then
        assertEquals("{\"id\":1}", result);
    }

    @Test
    void should_returnNull_when_getNullableResultByColumnName_given_nullValue() throws Exception {
        // given
        when(resultSet.getObject("config")).thenReturn(null);

        // when
        String result = handler.getNullableResult(resultSet, "config");

        // then
        assertNull(result);
    }

    @Test
    void should_returnToString_when_getNullableResultByColumnName_given_nonPgObjectValue() throws Exception {
        // given - 个别驱动实现可能返回普通 String/其他对象
        when(resultSet.getObject("config")).thenReturn("{\"raw\":true}");

        // when
        String result = handler.getNullableResult(resultSet, "config");

        // then
        assertEquals("{\"raw\":true}", result);
    }

    // ==================== getNullableResult（读方向，按列索引） ====================

    @Test
    void should_returnJsonText_when_getNullableResultByColumnIndex_given_pgObject() throws Exception {
        // given
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue("[]");
        when(resultSet.getObject(2)).thenReturn(pgObject);

        // when
        String result = handler.getNullableResult(resultSet, 2);

        // then
        assertEquals("[]", result);
    }

    @Test
    void should_returnNull_when_getNullableResultByColumnIndex_given_nullValue() throws Exception {
        // given
        when(resultSet.getObject(2)).thenReturn(null);

        // when
        String result = handler.getNullableResult(resultSet, 2);

        // then
        assertNull(result);
    }

    // ==================== getNullableResult（CallableStatement） ====================

    @Test
    void should_returnJsonText_when_getNullableResultFromCallable_given_pgObject() throws Exception {
        // given
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue("{\"a\":1}");
        when(callableStatement.getObject(3)).thenReturn(pgObject);

        // when
        String result = handler.getNullableResult(callableStatement, 3);

        // then
        assertEquals("{\"a\":1}", result);
    }

    @Test
    void should_returnNull_when_getNullableResultFromCallable_given_nullValue() throws Exception {
        // given
        when(callableStatement.getObject(3)).thenReturn(null);

        // when
        String result = handler.getNullableResult(callableStatement, 3);

        // then
        assertNull(result);
    }
}
