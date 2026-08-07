package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.model.SearchResult;
import com.linkroa.deepdataagent.agent.domain.service.WebSearchService;
import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import com.linkroa.deepdataagent.agent.infrastructure.util.AgentToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具
 * <p>Agent 工具，用于搜索互联网获取最新信息和外部知识。</p>
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    
    /**
     * 搜索查询最大允许长度（字符数）
     */
    private static final int MAX_QUERY_LENGTH = 200;

    private final WebSearchService webSearchService;
    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;

    public WebSearchTool(WebSearchService webSearchService, WebSearchProperties properties, ObjectMapper objectMapper) {
        this.webSearchService = webSearchService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "web_search",
          description = "Search the internet for up-to-date information. " +
                        "Use this when the question involves market trends, industry news, " +
                        "latest data, or external knowledge not available in the database. " +
                        "Returns a list of search results with titles, URLs, and snippets.")
    public String search(
            @ToolParam(name = "query", required = true,
                       description = "The search query in natural language") String query,
            @ToolParam(name = "maxResults", required = false,
                       description = "Maximum number of results to return, default 5") Integer maxResults
    ) {
        // Sanitize query（先清理再记录日志，避免将原始超长/含敏感信息的 query 写入日志）
        String sanitizedQuery = sanitizeQuery(query);
        log.info("WebSearchTool: searching for query='{}'", sanitizedQuery);
        if (sanitizedQuery.isEmpty()) {
            return AgentToolResponse.error("搜索查询为空，无法执行搜索。");
        }

        try {
            int effectiveMax = resolveMaxResults(maxResults);
            List<SearchResult> results = webSearchService.search(sanitizedQuery, effectiveMax);

            if (results.isEmpty()) {
                return AgentToolResponse.empty("未找到相关搜索结果。");
            }

            return AgentToolResponse.data(formatResults(results));
        } catch (Exception e) {
            log.error("WebSearchTool: search failed", e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "未知错误";
            return AgentToolResponse.error("搜索失败: " + errorMessage);
        }
    }

    /**
     * 解析并钳制最大返回结果数
     * <p>LLM 可能传入超大值，统一钳制到配置上限，避免浪费调用配额；非法值（null/非正数）回退到配置默认。</p>
     *
     * @param maxResults 工具传入的最大结果数
     * @return 钳制后的有效结果数
     */
    private int resolveMaxResults(Integer maxResults) {
        int configured = properties.getMaxResults() > 0 ? properties.getMaxResults() : 5;
        if (maxResults != null && maxResults > 0) {
            return Math.min(maxResults, configured);
        }
        return configured;
    }

    /**
     * 清理搜索查询
     */
    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String sanitized = query.trim();
        if (sanitized.length() > MAX_QUERY_LENGTH) {
            sanitized = sanitized.substring(0, MAX_QUERY_LENGTH);
        }
        return sanitized;
    }

    /**
     * 格式化搜索结果为 JSON
     * <p>将搜索结果列表格式化为 JSON 格式，便于前端解析和展示。</p>
     *
     * @param results 搜索结果列表
     * @return JSON 格式的字符串
     */
    private String formatResults(List<SearchResult> results) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("results", results);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("序列化搜索结果失败", e);
            return "{\"results\": []}";
        }
    }
}
