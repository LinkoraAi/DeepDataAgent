package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.domain.service.TextToSqlService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Text-to-SQL 工具
 * <p>将自然语言问题转换为 SQL 查询，供 Agent 调用。</p>
 */
@Component
public class TextToSqlTool {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlTool.class);

    private final TextToSqlService textToSqlService;
    private final DatasourceGateway datasourceGateway;

    public TextToSqlTool(TextToSqlService textToSqlService, DatasourceGateway datasourceGateway) {
        this.textToSqlService = textToSqlService;
        this.datasourceGateway = datasourceGateway;
    }

    @Tool(name = "generate_sql",
          description = "Convert a natural language question into a SQL SELECT query. " +
                        "Call this AFTER retrieve_schema to understand the database structure. " +
                        "Returns the generated SQL statement.")
    public String generateSql(
            @ToolParam(name = "modelConfigId", required = true,
                       description = "The LLM model configuration ID") Long modelConfigId,
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the datasource connection") Long datasourceId,
            @ToolParam(name = "userQuestion", required = true,
                       description = "The user's natural language question") String userQuestion
    ) {
        log.info("TextToSqlTool: generating SQL for question='{}'", userQuestion);
        try {
            String schemaInfo = datasourceGateway.extractSchema(datasourceId);
            String sqlDialect = resolveSqlDialect(datasourceId);
            String sql = textToSqlService.convert(modelConfigId, userQuestion, schemaInfo, sqlDialect);
            log.info("TextToSqlTool: SQL generated: {}", sql);
            return sql;
        } catch (Exception e) {
            log.error("TextToSqlTool: failed to generate SQL", e);
            return "Failed to generate SQL: " + e.getMessage();
        }
    }

    private String resolveSqlDialect(Long datasourceId) {
        return datasourceGateway.findDatasource(datasourceId)
                .map(ds -> {
                    if (ds.jdbcCategory() == JdbcCategory.CLICKHOUSE) {
                        return "ClickHouse";
                    }
                    return "MySQL";
                })
                .orElse("MySQL");
    }
}