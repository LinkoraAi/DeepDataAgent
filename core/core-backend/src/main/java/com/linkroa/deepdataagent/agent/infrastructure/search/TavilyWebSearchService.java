package com.linkroa.deepdataagent.agent.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.model.SearchResult;
import com.linkroa.deepdataagent.agent.domain.service.WebSearchService;
import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 网络搜索服务实现
 * <p>基于 Tavily API 的网络搜索适配器，提供 AI 优化的搜索结果。</p>
 */
@Service
public class TavilyWebSearchService implements WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchService.class);

    /**
     * 摘要最大长度
     */
    private static final int MAX_SNIPPET_LENGTH = 500;

    private final WebSearchProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造器
     *
     * @param properties   网络搜索配置属性
     * @param restClient   HTTP 客户端（Spring Bean，使用 @Qualifier 指定 webSearchRestClient）
     * @param objectMapper JSON 序列化工具（Spring 自动注入）
     */
    public TavilyWebSearchService(WebSearchProperties properties,
                                   @Qualifier("webSearchRestClient") RestClient restClient,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        if (properties.getApiKey() == null || properties.getApiKey().isEmpty()) {
            log.warn("Tavily API 密钥未配置");
            return List.of();
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("api_key", properties.getApiKey());
            requestBody.put("query", query);
            requestBody.put("max_results", maxResults);

            // 注意：requestBody 中包含 api_key，禁止打印完整请求体以防止敏感信息泄露
            log.info("执行 Tavily 搜索: query='{}', maxResults={}", query, maxResults);

            // 执行请求
            String responseBody = restClient.post()
                    .uri(properties.getEndpoint())
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // 解析响应
            return parseResponse(responseBody);

        } catch (Exception e) {
            log.error("Tavily 搜索失败: query='{}'", query, e);
            return List.of();
        }
    }

    /**
     * 解析 Tavily API 响应
     *
     * @param responseBody API 响应体
     * @return 搜索结果列表
     */
    private List<SearchResult> parseResponse(String responseBody) {
        List<SearchResult> results = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            
            // 防御性检查：root 节点可能为 null（当 responseBody 为 null 或空字符串时）
            if (root == null) {
                log.warn("Tavily 响应为空: {}", responseBody);
                return results;
            }
            
            JsonNode resultsNode = root.get("results");

            if (resultsNode == null || !resultsNode.isArray()) {
                log.warn("Tavily 响应格式异常: {}", responseBody);
                return results;
            }

            for (JsonNode resultNode : resultsNode) {
                // 防御性检查：跳过无效的搜索结果节点
                if (resultNode == null || resultNode.isNull()) {
                    log.warn("跳过无效的搜索结果节点");
                    continue;
                }

                String title = getTextOrEmpty(resultNode, "title");
                String url = getTextOrEmpty(resultNode, "url");
                String content = getTextOrEmpty(resultNode, "content");

                // 截取摘要
                String snippet = content.length() > MAX_SNIPPET_LENGTH
                        ? content.substring(0, MAX_SNIPPET_LENGTH) + "..."
                        : content;

                results.add(SearchResult.of(title, url, snippet, content));
            }

            log.debug("Tavily 搜索返回 {} 条结果", results.size());

        } catch (Exception e) {
            log.error("解析 Tavily 响应失败", e);
        }

        return results;
    }

    /**
     * 安全获取 JSON 节点的文本值
     * <p>处理字段缺失或为 null 的情况，返回空字符串而非 "null"。</p>
     *
     * @param node JSON 节点
     * @param fieldName 字段名
     * @return 字段文本值，缺失或为 null 时返回空字符串
     */
    private String getTextOrEmpty(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return "";
        }
        return fieldNode.asText();
    }
}
