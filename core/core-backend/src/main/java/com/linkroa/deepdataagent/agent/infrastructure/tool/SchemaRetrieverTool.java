package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Schema 检索工具
 * <p>根据数据源 ID 提取数据库 Schema 信息，供 Agent 调用。</p>
 */
@Component
public class SchemaRetrieverTool {

    private static final Logger log = LoggerFactory.getLogger(SchemaRetrieverTool.class);

    private final DatasourceGateway datasourceGateway;

    public SchemaRetrieverTool(DatasourceGateway datasourceGateway) {
        this.datasourceGateway = datasourceGateway;
    }

    @Tool(name = "retrieve_schema",
          description = "Retrieve database schema information for a given datasource. " +
                        "This includes table names, column names, data types, and comments. " +
                        "Call this FIRST before generating any SQL to understand the database structure.")
    public String retrieveSchema(
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the datasource connection") Long datasourceId,
            @ToolParam(name = "keyword", required = false,
                       description = "Optional keyword to filter relevant tables") String keyword
    ) {
        log.info("SchemaRetrieverTool: retrieving schema for datasource={}, keyword={}", datasourceId, keyword);
        try {
            String schema = datasourceGateway.extractSchema(datasourceId);
            if (keyword != null && !keyword.isBlank()) {
                schema = filterByKeyword(schema, keyword);
            }
            log.info("SchemaRetrieverTool: schema retrieved, length={}", schema.length());
            return schema;
        } catch (Exception e) {
            log.error("SchemaRetrieverTool: failed to retrieve schema", e);
            return "Failed to retrieve schema: " + e.getMessage();
        }
    }

    /**
     * 根据关键词过滤 Schema 文本
     */
    private String filterByKeyword(String schema, String keyword) {
        String[] lines = schema.split("\n");
        StringBuilder filtered = new StringBuilder();
        String lowerKeyword = keyword.toLowerCase();

        for (String line : lines) {
            if (line.toLowerCase().contains(lowerKeyword)) {
                // 找到匹配行，包含其上下文（表名行）
                filtered.append(line).append("\n");
            }
        }
        if (filtered.isEmpty()) {
            // 如果没匹配到，返回完整 schema
            return schema;
        }
        return filtered.toString().strip();
    }
}