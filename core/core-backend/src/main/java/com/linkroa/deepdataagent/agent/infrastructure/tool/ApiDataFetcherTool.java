package com.linkroa.deepdataagent.agent.infrastructure.tool;

import tools.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.infrastructure.util.AgentToolResponse;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
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

    /** 单次返回最大行数，超出即视为可能被截断 */
    private static final int MAX_ROWS = 500;

    private final DatasourceGateway datasourceGateway;
    private final ObjectMapper objectMapper;

    public ApiDataFetcherTool(DatasourceGateway datasourceGateway, ObjectMapper objectMapper) {
        this.datasourceGateway = datasourceGateway;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "execute_api_query",
          description = "Fetch data from an API datasource by specifying the API schema name. " +
                        "Call this AFTER retrieve_schema to get data from the API. " +
                        "Returns at most " + MAX_ROWS + " rows of data as JSON.")
    public String executeApiQuery(
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the API datasource connection") Long datasourceId,
            @ToolParam(name = "apiSchemaName", required = true,
                       description = "The name of the API schema (table) to query, from retrieve_schema results") String apiSchemaName,
            @ToolParam(name = "limit", required = false,
                       description = "Maximum number of rows to return, capped at " + MAX_ROWS + ", default " + MAX_ROWS) Integer limit
    ) {
        log.info("ApiDataFetcherTool: fetching data from datasource={}, schema={}", datasourceId, apiSchemaName);
        try {
            // 与 executeApiQuery 内部钳制保持一致，避免 LLM 传入超大 limit 绕过默认上限
            int effectiveLimit = Math.min(limit != null ? limit : MAX_ROWS, MAX_ROWS);
            List<Map<String, Object>> results = datasourceGateway.executeApiQuery(datasourceId, apiSchemaName, effectiveLimit);
            log.info("ApiDataFetcherTool: fetched {} rows", results.size());

            if (results.isEmpty()) {
                return AgentToolResponse.empty("API 查询结果为空。");
            }

            String json = objectMapper.writeValueAsString(results);
            String result = AgentToolResponse.data(String.format("API 返回 %d 行数据：\n%s", results.size(), json));

            // 达到单次上限时提示可能被截断，并引导聚焦统计性结论
            if (results.size() >= MAX_ROWS) {
                result += AgentToolResponse.pagingHint(MAX_ROWS);
            }
            return result;
        } catch (DeepDataAgentException e) {
            log.error("ApiDataFetcherTool: failed to fetch API data", e);
            // 仅返回业务原因，避免暴露 URL/请求细节
            return AgentToolResponse.error("API 数据获取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("ApiDataFetcherTool: failed to fetch API data", e);
            return AgentToolResponse.error("API 数据获取失败: 发生未知错误，请检查数据源配置后重试。");
        }
    }
}
