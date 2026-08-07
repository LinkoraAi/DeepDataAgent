package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.domain.service.NL2SqlService;
import com.linkroa.deepdataagent.agent.infrastructure.util.AgentToolResponse;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NL2SQL 工具
 * <p>将自然语言问题转换为 SQL 查询，供 Agent 调用。</p>
 */
@Component
public class NL2SqlTool {

    private static final Logger log = LoggerFactory.getLogger(NL2SqlTool.class);

    /** RuntimeContext 中存储 modelConfigId 的键名 */
    private static final String CTX_KEY_MODEL_CONFIG_ID = "model_config_id";

    private final NL2SqlService nl2SqlService;
    private final DatasourceGateway datasourceGateway;
    private final SessionToolContext sessionToolContext;

    public NL2SqlTool(NL2SqlService nl2SqlService, DatasourceGateway datasourceGateway, SessionToolContext sessionToolContext) {
        this.nl2SqlService = nl2SqlService;
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
            @ToolParam(name = "schemaInfo", required = true,
                       description = "The schema info obtained from the retrieve_schema tool") String schemaInfo,
            @ToolParam(name = "sessionId", required = true,
                       description = "The session ID from the user message (format: UUID)") String sessionId,
            ToolCallParam toolCallParam
    ) {
        log.info("NL2SqlTool: generating SQL for question='{}', sessionId={}", userQuestion, sessionId);
        try {
            if (sessionId == null || sessionId.isBlank()) {
                log.error("NL2SqlTool: sessionId parameter is empty");
                return AgentToolResponse.error("Failed to generate SQL: sessionId is required");
            }

            if (schemaInfo == null || schemaInfo.isBlank()) {
                log.error("NL2SqlTool: schemaInfo parameter is empty");
                return AgentToolResponse.error("Failed to generate SQL: schemaInfo is required, call retrieve_schema first");
            }
            
            // 从 SessionToolContext 获取 modelConfigId
            Long modelConfigId = sessionToolContext.getModelConfigId(sessionId);
            if (modelConfigId == null) {
                // 兜底：尝试从 RuntimeContext 获取
                if (toolCallParam != null && toolCallParam.getRuntimeContext() != null) {
                    Object configId = toolCallParam.getRuntimeContext().get(CTX_KEY_MODEL_CONFIG_ID);
                    if (configId instanceof Number number) {
                        modelConfigId = number.longValue();
                    }
                }
            }
            if (modelConfigId == null) {
                log.error("NL2SqlTool: modelConfigId not available for sessionId={}", sessionId);
                return AgentToolResponse.error("Failed to generate SQL: modelConfigId not available in context");
            }
            
            String sqlDialect = resolveSqlDialect(datasourceId);
            String sql = nl2SqlService.convert(modelConfigId, userQuestion, schemaInfo, sqlDialect, sessionId);
            log.info("NL2SqlTool: SQL generated: {}", sql);
            return sql;
        } catch (Exception e) {
            log.error("NL2SqlTool: failed to generate SQL", e);
            return AgentToolResponse.error("Failed to generate SQL: " + e.getMessage());
        }
    }

    private String resolveSqlDialect(Long datasourceId) {
        return datasourceGateway.findDatasource(datasourceId)
                .map(ds -> {
                    if (ds.jdbcCategory() == null) {
                        return "MySQL";
                    }
                    return switch (ds.jdbcCategory()) {
                        case CLICKHOUSE -> "ClickHouse";
                        case POSTGRESQL -> "PostgreSQL";
                        default -> "MySQL";
                    };
                })
                .orElse("MySQL");
    }
}