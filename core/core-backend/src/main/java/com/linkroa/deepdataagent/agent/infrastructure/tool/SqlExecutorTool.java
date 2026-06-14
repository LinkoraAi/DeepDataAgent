package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.infrastructure.executor.QueryExecutor;
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

    public SqlExecutorTool(DatasourceGateway datasourceGateway, List<QueryExecutor> queryExecutors, ObjectMapper objectMapper) {
        this.datasourceGateway = datasourceGateway;
        this.queryExecutors = queryExecutors;
        this.objectMapper = objectMapper;
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
            DatasourceInfo datasource = datasourceGateway.findDatasource(datasourceId)
                    .orElseThrow(() -> new RuntimeException("数据源不存在: " + datasourceId));

            QueryExecutor executor = queryExecutors.stream()
                    .filter(e -> e.supports(datasource))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("不支持的数据源类型"));

            List<Map<String, Object>> results = executor.execute(datasource, sql);
            log.info("SqlExecutorTool: query returned {} rows", results.size());

            if (results.isEmpty()) {
                return "查询结果为空。可能原因：1) 查询条件过于严格 2) 数据表中确实没有匹配数据";
            }

            // 返回完整 JSON 数据，供下游工具（generate_chart、generate_analysis）使用
            String json = objectMapper.writeValueAsString(results);
            return String.format("查询返回 %d 行数据：\n%s", results.size(), json);
        } catch (Exception e) {
            log.error("SqlExecutorTool: failed to execute SQL", e);
            return "SQL 执行失败: " + e.getMessage();
        }
    }
}