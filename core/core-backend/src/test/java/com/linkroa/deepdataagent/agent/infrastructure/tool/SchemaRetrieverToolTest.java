package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaRetrieverToolTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    @InjectMocks
    private SchemaRetrieverTool schemaRetrieverTool;

    /** 模拟真实 Schema 输出：表头行 + 缩进字段行，表块间空行分隔 */
    private static final String SAMPLE_SCHEMA = """
            表: orders  -- 订单表
              - order_id (INT)  -- 订单ID
              - customer_name (VARCHAR)  -- 客户姓名
              - payment_status (VARCHAR)  -- 支付状态

            表: payments  -- 支付表
              - payment_id (INT)  -- 支付ID
              - amount (DECIMAL)  -- 支付金额
              - status (VARCHAR)  -- 状态
            """.strip();

    @Test
    void retrieveSchema_shouldReturnSchema_whenSuccess() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        String result = schemaRetrieverTool.retrieveSchema(1L, null);

        assertEquals(SAMPLE_SCHEMA.strip(), result);
    }

    @Test
    void retrieveSchema_shouldReturnOriginalSchema_whenBlankKeyword() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        String result = schemaRetrieverTool.retrieveSchema(1L, "   ");

        assertEquals(SAMPLE_SCHEMA.strip(), result);
    }

    @Test
    void retrieveSchema_shouldPreserveWholeTable_whenFieldLineMatches() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        // 关键场景：命中字段行 amount，但表头 payments 不包含 amount，必须整表保留
        String result = schemaRetrieverTool.retrieveSchema(1L, "amount");

        assertTrue(result.contains("表: payments  -- 支付表"));
        assertTrue(result.contains("  - amount (DECIMAL)  -- 支付金额"));
        assertFalse(result.contains("表: orders"));
        assertFalse(result.contains("order_id"));
    }

    @Test
    void retrieveSchema_shouldPreserveWholeTable_whenTableNameMatches() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        String result = schemaRetrieverTool.retrieveSchema(1L, "orders");

        assertTrue(result.contains("表: orders  -- 订单表"));
        assertTrue(result.contains("  - order_id (INT)  -- 订单ID"));
        assertFalse(result.contains("表: payments"));
    }

    @Test
    void retrieveSchema_shouldReturnOnlyMatchedTables_whenPartialMatch() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        // payment_id 仅存在于 payments 表，orders 表不被命中
        String result = schemaRetrieverTool.retrieveSchema(1L, "payment_id");

        assertTrue(result.contains("表: payments  -- 支付表"));
        assertFalse(result.contains("表: orders"));
    }

    @Test
    void retrieveSchema_shouldReturnNoMatchHint_whenKeywordNotFound() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        String result = schemaRetrieverTool.retrieveSchema(1L, "nonexistent");

        assertTrue(result.contains("未找到与关键词相关的表"));
    }

    @Test
    void retrieveSchema_shouldReturnErrorMarker_whenGatewayThrows() {
        when(datasourceGateway.extractSchema(1L)).thenThrow(new RuntimeException("DB error"));

        String result = schemaRetrieverTool.retrieveSchema(1L, null);

        assertTrue(result.startsWith("[ERROR] "));
        assertTrue(result.contains("Failed to retrieve schema"));
    }

    @Test
    void retrieveSchema_shouldBeCaseInsensitive_whenFiltering() {
        when(datasourceGateway.extractSchema(1L)).thenReturn(SAMPLE_SCHEMA);

        String result = schemaRetrieverTool.retrieveSchema(1L, "AMOUNT");

        assertTrue(result.contains("表: payments  -- 支付表"));
        assertFalse(result.contains("表: orders"));
    }
}