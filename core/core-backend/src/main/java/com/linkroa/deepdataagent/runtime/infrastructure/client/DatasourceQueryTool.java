package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.datasource.application.contract.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.application.contract.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.application.port.DatasourceQueryPort;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.Map;

/**
 * 数据源查询工具（AgentScope 官方 {@code @Tool} 注解对象）。
 * <p>随 Agent 版本数据源引用自动装配（无需用户勾选）：LLM 可先用 {@code datasource_list}
 * 了解可用数据源，再 {@code datasource_tables} 查看表清单，最后 {@code datasource_preview}
 * 预览表数据。所有能力均为只读，凭证/连接细节由 {@link DatasourceQueryPort} 收口，不跨 BC。</p>
 */
public class DatasourceQueryTool {

    private static final int DEFAULT_LIMIT = 10;

    private final DatasourceQueryPort queryPort;
    private final List<Long> dataSourceIds;

    public DatasourceQueryTool(DatasourceQueryPort queryPort, List<Long> dataSourceIds) {
        this.queryPort = queryPort;
        this.dataSourceIds = List.copyOf(dataSourceIds);
    }

    @Tool(name = "datasource_list", description = "列出当前 Agent 可用的数据源（数据源 ID 与名称）", readOnly = true)
    public String listDatasources() {
        List<DatasourceReferenceDTO> refs = queryPort.listDatasources(dataSourceIds);
        if (refs.isEmpty()) {
            return "当前无可用数据源";
        }
        StringBuilder sb = new StringBuilder();
        for (DatasourceReferenceDTO ref : refs) {
            sb.append(ref.id()).append(" - ").append(ref.name()).append("（").append(ref.type()).append("）\n");
        }
        return sb.toString();
    }

    @Tool(name = "datasource_tables", description = "列出指定数据源下的表名", readOnly = true)
    public String listTables(@ToolParam(name = "dataSourceId", required = true, description = "数据源 ID") Long dataSourceId) {
        requireAllowed(dataSourceId);
        List<String> tables = queryPort.listTableNames(dataSourceId);
        if (tables.isEmpty()) {
            return "该数据源下无表";
        }
        return String.join("\n", tables);
    }

    @Tool(name = "datasource_preview", description = "预览指定数据源指定表的数据", readOnly = true)
    public String previewTable(
            @ToolParam(name = "dataSourceId", required = true, description = "数据源 ID") Long dataSourceId,
            @ToolParam(name = "tableName", required = true, description = "表名") String tableName,
            @ToolParam(name = "limit", description = "返回条数，默认 10") Integer limit) {
        requireAllowed(dataSourceId);
        DatasourcePreviewDTO preview = queryPort.previewTable(dataSourceId, tableName, limit == null ? DEFAULT_LIMIT : limit);
        return formatPreview(preview);
    }

    /**
     * 校验数据源 ID 在 Agent 版本配置的引用白名单内（越权访问直接拒绝）。
     */
    private void requireAllowed(Long dataSourceId) {
        if (dataSourceId == null || !dataSourceIds.contains(dataSourceId)) {
            throw new IllegalArgumentException("数据源不在本 Agent 的授权范围内: " + dataSourceId);
        }
    }

    private String formatPreview(DatasourcePreviewDTO preview) {
        if (preview.columns().isEmpty()) {
            return "表 " + preview.tableName() + " 无数据";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("columns:").append(String.join(",", preview.columns())).append("\n");
        for (Map<String, Object> row : preview.rows()) {
            List<String> values = preview.columns().stream()
                    .map(column -> String.valueOf(row.get(column)))
                    .toList();
            sb.append(String.join(",", values)).append("\n");
        }
        return sb.toString();
    }
}