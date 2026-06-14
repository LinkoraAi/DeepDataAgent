package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * API 数据获取工具
 * <p>替代 API 流程中的 generate_sql + execute_sql，
 * 根据用户指定的 API Schema 名称直接获取数据，供 Agent 调用。</p>
 */
@Component
public class ApiDataFetcherTool {

    private static final Logger log = LoggerFactory.getLogger(ApiDataFetcherTool.class);

    private final DatasourceGateway datasourceGateway;
    private final ObjectMapper objectMapper;

    public ApiDataFetcherTool(DatasourceGateway datasourceGateway, ObjectMapper objectMapper) {
        this.datasourceGateway = datasourceGateway;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "execute_api_query",
          description = "Fetch data from an API datasource by specifying the API schema name. " +
                        "Call this AFTER retrieve_schema to get data from the API. " +
                        "Returns the fetched data as JSON.")
    public String executeApiQuery(
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the API datasource connection") Long datasourceId,
            @ToolParam(name = "apiSchemaName", required = true,
                       description = "The name of the API schema (table) to query, from retrieve_schema results") String apiSchemaName,
            @ToolParam(name = "limit", required = false,
                       description = "Maximum number of rows to return, default 1000") Integer limit
    ) {
        log.info("ApiDataFetcherTool: fetching data from datasource={}, schema={}", datasourceId, apiSchemaName);
        try {
            int effectiveLimit = limit != null ? limit : 1000;
            List<Map<String, Object>> results = datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, effectiveLimit);
            log.info("ApiDataFetcherTool: fetched {} rows", results.size());

            if (results.isEmpty()) {
                return "API 查询结果为空。可能原因：1) API 未返回数据 2) 分页配置不正确";
            }

            String json = objectMapper.writeValueAsString(results);
            return String.format("API 返回 %d 行数据：\n%s", results.size(), json);
        } catch (Exception e) {
            log.error("ApiDataFetcherTool: failed to fetch API data", e);
            return "API 数据获取失败: " + e.getMessage();
        }
    }
}
