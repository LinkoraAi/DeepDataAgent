package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.datasource.application.contract.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.application.contract.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.application.port.DatasourceQueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DatasourceQueryTool} 数据源查询工具单测（只读工具方法文本输出，mock 端口）。
 */
class DatasourceQueryToolTest {

    @Test
    void should_listDatasources_when_listDatasources_given_refs() {
        // given
        DatasourceQueryPort port = mock(DatasourceQueryPort.class);
        when(port.listDatasources(List.of(1L)))
                .thenReturn(List.of(new DatasourceReferenceDTO(1L, "客户库", "POSTGRESQL")));
        DatasourceQueryTool tool = new DatasourceQueryTool(port, List.of(1L));

        // when
        String result = tool.listDatasources();

        // then
        assertTrue(result.contains("1 - 客户库"));
        assertTrue(result.contains("POSTGRESQL"));
    }

    @Test
    void should_returnEmptyHint_when_listDatasources_given_noRefs() {
        // given
        DatasourceQueryPort port = mock(DatasourceQueryPort.class);
        when(port.listDatasources(List.of(1L))).thenReturn(List.of());
        DatasourceQueryTool tool = new DatasourceQueryTool(port, List.of(1L));

        // when
        String result = tool.listDatasources();

        // then
        assertEquals("当前无可用数据源", result);
    }

    @Test
    void should_listTables_when_listTables_given_tables() {
        // given
        DatasourceQueryPort port = mock(DatasourceQueryPort.class);
        when(port.listTableNames(1L)).thenReturn(List.of("orders", "customers"));
        DatasourceQueryTool tool = new DatasourceQueryTool(port, List.of(1L));

        // when
        String result = tool.listTables(1L);

        // then
        assertEquals("orders\ncustomers", result);
    }

    @Test
    void should_formatPreview_when_previewTable_given_rows() {
        // given
        DatasourceQueryPort port = mock(DatasourceQueryPort.class);
        when(port.previewTable(1L, "orders", 10))
                .thenReturn(new DatasourcePreviewDTO(
                        "客户库", "orders",
                        List.of("id", "name"),
                        List.of(Map.of("id", 1, "name", "张三"))));
        DatasourceQueryTool tool = new DatasourceQueryTool(port, List.of(1L));

        // when
        String result = tool.previewTable(1L, "orders", 10);

        // then
        assertTrue(result.contains("columns:id,name"));
        assertTrue(result.contains("1,张三"));
    }
}