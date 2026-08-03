package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaRetrieverToolTest {

    @Mock
    private DatasourceGateway datasourceGateway;

    @InjectMocks
    private SchemaRetrieverTool schemaRetrieverTool;

    @Test
    void retrieveSchema_shouldReturnSchema_whenSuccess() {
        when(datasourceGateway.extractSchema(1L)).thenReturn("table1\ncolumn1\ntable2\ncolumn2");

        String result = schemaRetrieverTool.retrieveSchema(1L, null);

        assertEquals("table1\ncolumn1\ntable2\ncolumn2", result);
    }

    @Test
    void retrieveSchema_shouldReturnFilteredSchema_whenKeywordProvided() {
        when(datasourceGateway.extractSchema(1L)).thenReturn("table1\ncolumn1\ntable2\ncolumn2");

        String result = schemaRetrieverTool.retrieveSchema(1L, "table1");

        assertTrue(result.contains("table1"));
        assertTrue(!result.contains("column1"));
        assertTrue(!result.contains("table2"));
    }

    @Test
    void retrieveSchema_shouldReturnError_whenExceptionThrown() {
        when(datasourceGateway.extractSchema(1L)).thenThrow(new RuntimeException("DB error"));

        String result = schemaRetrieverTool.retrieveSchema(1L, null);

        assertTrue(result.contains("Failed to retrieve schema"));
    }

    @Test
    void retrieveSchema_shouldReturnFullSchema_whenKeywordIsBlank() {
        when(datasourceGateway.extractSchema(1L)).thenReturn("table1\ncolumn1\ntable2\ncolumn2");

        String result = schemaRetrieverTool.retrieveSchema(1L, "   ");

        assertEquals("table1\ncolumn1\ntable2\ncolumn2", result);
    }

    @Test
    void retrieveSchema_shouldReturnFullSchema_whenKeywordDoesNotMatch() {
        when(datasourceGateway.extractSchema(1L)).thenReturn("table1\ncolumn1\ntable2\ncolumn2");

        String result = schemaRetrieverTool.retrieveSchema(1L, "nonexistent");

        assertEquals("table1\ncolumn1\ntable2\ncolumn2", result);
    }
}
