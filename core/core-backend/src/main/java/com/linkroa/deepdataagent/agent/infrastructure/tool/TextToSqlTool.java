package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.domain.service.TextToSqlService;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
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

    /** RuntimeContext 中存储 modelConfigId 的键名 */
    private static final String CTX_KEY_MODEL_CONFIG_ID = "model_config_id";

    private final TextToSqlService textToSqlService;
    private final DatasourceGateway datasourceGateway;
    private final SessionToolContext sessionToolContext;

    public TextToSqlTool(TextToSqlService textToSqlService, DatasourceGateway datasourceGateway, SessionToolContext sessionToolContext) {
        this.textToSqlService = textToSqlService;
        this.datasourceGateway = datasourceGateway;
        this.sessionToolContext = sessionToolContext;
    }

    @Tool(name = "generate_sql",
          description = "Convert a natural language question into a SQL SELECT query. " +
                        "Call this AFTER retrieve_schema to understand the database structure. " +
                        "Returns the generated SQL statement.")
    public String generateSql(
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the datasource connection") Long datasourceId,
            @ToolParam(name = "userQuestion", required = true,
                       description = "The user's natural language question") String userQuestion,
            @ToolParam(name = "sessionId", required = true,
                       description = "The session ID from the user message (format: UUID)") String sessionId,
            ToolCallParam toolCallParam
    ) {
        log.info("TextToSqlTool: generating SQL for question='{}', sessionId={}", userQuestion, sessionId);
        try {
            if (sessionId == null || sessionId.isBlank()) {
                log.error("TextToSqlTool: sessionId parameter is empty");
                return "Failed to generate SQL: sessionId is required";
            }
            
            // 从 SessionToolContext 获取 modelConfigId
            Long modelConfigId = sessionToolContext.getModelConfigId(sessionId);
            if (modelConfigId == null) {
                // 兜底：尝试从 RuntimeContext 获取
                if (toolCallParam != null && toolCallParam.getRuntimeContext() != null) {
                    modelConfigId = (Long) toolCallParam.getRuntimeContext().get(CTX_KEY_MODEL_CONFIG_ID);
                }
            }
            if (modelConfigId == null) {
                log.error("TextToSqlTool: modelConfigId not available for sessionId={}", sessionId);
                return "Failed to generate SQL: modelConfigId not available in context";
            }
            
            String schemaInfo = datasourceGateway.extractSchema(datasourceId);
            String sqlDialect = resolveSqlDialect(datasourceId);
            String sql = textToSqlService.convert(modelConfigId, userQuestion, schemaInfo, sqlDialect, sessionId);
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
