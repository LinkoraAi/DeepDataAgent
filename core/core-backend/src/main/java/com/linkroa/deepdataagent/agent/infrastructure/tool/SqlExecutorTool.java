package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import com.linkroa.deepdataagent.agent.infrastructure.executor.QueryExecutor;
import com.linkroa.deepdataagent.agent.infrastructure.util.AgentToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行工具
 * <p>在数据源上执行 SQL 查询，返回结果，供 Agent 调用。</p>
 */
@Component
public class SqlExecutorTool {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutorTool.class);

    private final DatasourceGateway datasourceGateway;
    private final List<QueryExecutor> queryExecutors;
    private final ObjectMapper objectMapper;
    private final SqlValidationPort sqlValidator;
    private final DataAnalysisProperties properties;

    public SqlExecutorTool(DatasourceGateway datasourceGateway, List<QueryExecutor> queryExecutors,
                           ObjectMapper objectMapper, SqlValidationPort sqlValidator,
                           DataAnalysisProperties properties) {
        this.datasourceGateway = datasourceGateway;
        this.queryExecutors = queryExecutors;
        this.objectMapper = objectMapper;
        this.sqlValidator = sqlValidator;
        this.properties = properties;
    }

    @Tool(name = "execute_sql",
          description = "Execute a SQL SELECT query on the specified datasource. " +
                        "Call this AFTER generate_sql to run the generated SQL. " +
                        "Returns the query results as JSON.")
    public String executeSql(
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the datasource connection") Long datasourceId,
            @ToolParam(name = "sql", required = true,
                       description = "The SQL SELECT query to execute") String sql
    ) {
        log.info("SqlExecutorTool: executing SQL on datasource={}", datasourceId);
        try {
            // 二次安全校验：即使绕过 generate_sql 直接调用，也拦截危险/写操作 SQL
            sqlValidator.validate(sql);

            DatasourceInfo datasource = datasourceGateway.findDatasource(datasourceId)
                    .orElseThrow(() -> new RuntimeException("数据源不存在: " + datasourceId));

            QueryExecutor executor = queryExecutors.stream()
                    .filter(e -> e.supports(datasource))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("不支持的数据源类型"));

            List<Map<String, Object>> results = executor.execute(datasource, sql);
            log.info("SqlExecutorTool: query returned {} rows", results.size());

            if (results.isEmpty()) {
                return AgentToolResponse.empty("查询成功，但无数据。");
            }

            // 返回完整 JSON 数据，供下游工具（generate_chart、generate_analysis）使用
            String json = objectMapper.writeValueAsString(results);
            int maxRows = properties.getQuery().getMaxRows();
            String result = AgentToolResponse.data(String.format("查询成功，共 %d 行：\n%s", results.size(), json));

            // 结果集大小兜底：达到单次上限时提示已截断，并引导 LLM 用聚合/缩小范围，而非拉全量
            if (results.size() >= maxRows) {
                result += AgentToolResponse.pagingHint(maxRows);
            }
            return result;
        } catch (Exception e) {
            log.error("SqlExecutorTool: failed to execute SQL", e);
            return AgentToolResponse.error("SQL 执行失败: " + e.getMessage());
        }
    }
}