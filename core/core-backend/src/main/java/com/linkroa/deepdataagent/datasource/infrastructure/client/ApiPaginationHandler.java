package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiExpressionEvaluator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ApiPaginationHandler {

    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final ApiExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiPaginationHandler(ApiExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    public PaginatedApiResult executeOnce(DatasourceConnection connection, ApiTableConfig tableConfig, Map<String, Object> context) {
        return executeRequest(connection, tableConfig, context == null ? Map.of() : context);
    }

    /**
     * 执行单次API请求，返回原始响应体字符串
     *
     * @param connection 数据源连接配置
     * @param tableConfig API数据表配置
     * @param context 动态参数上下文
     * @return 原始响应体字符串
     */
    public String fetchRawResponse(DatasourceConnection connection, ApiTableConfig tableConfig, Map<String, Object> context) {
        return executeRawResponse(connection, tableConfig, context == null ? Map.of() : context);
    }

    public List<Map<String, Object>> fetchAllPages(DatasourceConnection connection, ApiTableConfig tableConfig, int limit) {
        return fetchAllPages(connection, tableConfig, limit, Map.of());
    }

    public List<Map<String, Object>> fetchAllPages(DatasourceConnection connection, ApiTableConfig tableConfig, int limit, Map<String, Object> context) {
        ApiPaginationConfig paginationConfig = effectivePaginationConfig(connection, tableConfig);
        if (paginationConfig == null || paginationConfig.paginationType() == null || paginationConfig.paginationType() == ApiPaginationType.NONE) {
            return truncate(executeOnce(connection, tableConfig, context).data(), limit);
        }

        return switch (paginationConfig.paginationType()) {
            case PAGE_BASED -> fetchByPageNumber(connection, tableConfig, limit, context);
            case CURSOR_BASED -> fetchByCursor(connection, tableConfig, limit, context);
            case NONE -> truncate(executeOnce(connection, tableConfig, context).data(), limit);
        };
    }

    private List<Map<String, Object>> fetchByPageNumber(DatasourceConnection connection, ApiTableConfig tableConfig, int limit, Map<String, Object> context) {
        ApiPaginationConfig paginationConfig = effectivePaginationConfig(connection, tableConfig);
        int pageSize = paginationConfig != null && paginationConfig.pageSize() != null ? paginationConfig.pageSize() : DEFAULT_PAGE_SIZE;
        int maxPages = paginationConfig != null && paginationConfig.maxPages() != null ? paginationConfig.maxPages() : DEFAULT_MAX_PAGES;

        List<Map<String, Object>> allData = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            Map<String, Object> pageContext = new LinkedHashMap<>(context);
            pageContext.put("pageNumber", pageNumber);
            pageContext.put("pageSize", pageSize);
            pageContext.put("page", pageNumber);
            pageContext.put("size", pageSize);

            PaginatedApiResult result = executeRequest(connection, tableConfig, pageContext);
            if (result.data().isEmpty()) {
                break;
            }
            allData.addAll(result.data());
            if (limit > 0 && allData.size() >= limit) {
                break;
            }
            if (!result.hasMore() || (result.totalCount() != null && allData.size() >= result.totalCount())) {
                break;
            }
        }
        return truncate(allData, limit);
    }

    private List<Map<String, Object>> fetchByCursor(DatasourceConnection connection, ApiTableConfig tableConfig, int limit, Map<String, Object> context) {
        ApiPaginationConfig paginationConfig = effectivePaginationConfig(connection, tableConfig);
        int pageSize = paginationConfig != null && paginationConfig.pageSize() != null ? paginationConfig.pageSize() : DEFAULT_PAGE_SIZE;
        int maxPages = paginationConfig != null && paginationConfig.maxPages() != null ? paginationConfig.maxPages() : DEFAULT_MAX_PAGES;

        List<Map<String, Object>> allData = new ArrayList<>();
        String cursor = null;
        for (int pageCount = 0; pageCount < maxPages; pageCount++) {
            Map<String, Object> pageContext = new LinkedHashMap<>(context);
            pageContext.put("pageCursor", cursor == null ? "" : cursor);
            pageContext.put("cursor", cursor == null ? "" : cursor);
            pageContext.put("pageSize", pageSize);
            pageContext.put("size", pageSize);

            PaginatedApiResult result = executeRequest(connection, tableConfig, pageContext);
            if (result.data().isEmpty()) {
                break;
            }
            allData.addAll(result.data());
            if (limit > 0 && allData.size() >= limit) {
                break;
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return truncate(allData, limit);
    }

    private PaginatedApiResult executeRequest(DatasourceConnection connection, ApiTableConfig tableConfig, Map<String, Object> context) {
        try {
            ApiConnectionConfig connectionConfig = connection.apiConnectionConfig();
            if (connectionConfig == null) {
                throw new IllegalArgumentException("API连接配置不能为空");
            }

            ApiConnectionConfig effectiveConfig = effectiveConnectionConfig(connectionConfig, tableConfig);
            String url = expressionEvaluator.evaluateString(effectiveConfig.url(), context);
            url = appendQueryParams(url, effectiveConfig.params(), context);

            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(effectiveConfig.timeout(), 1)))
                    .build()) {

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(effectiveConfig.timeout(), 1)));

                if (effectiveConfig.headers() != null) {
                    effectiveConfig.headers().forEach((key, value) -> {
                        if (key != null && !key.isBlank()) {
                            requestBuilder.header(key, expressionEvaluator.evaluateString(value, context));
                        }
                    });
                }

                applyAuth(requestBuilder, connection.apiAuthConfig(), context);

                if (effectiveConfig.method() == HttpMethod.POST) {
                    String body = effectiveConfig.body() == null ? "" : expressionEvaluator.evaluateString(effectiveConfig.body(), context);
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                    if (effectiveConfig.headers() == null || effectiveConfig.headers().keySet().stream().map(String::toLowerCase).noneMatch("content-type"::equals)) {
                        requestBuilder.header("Content-Type", "application/json");
                    }
                } else {
                    requestBuilder.GET();
                }

                HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("API请求失败，HTTP状态码: " + response.statusCode());
                }

                return parseResponse(response.body(), tableConfig, effectiveConfig);
            }
        } catch (Exception e) {
            throw new IllegalStateException("API请求执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行API请求并返回原始响应体
     */
    private String executeRawResponse(DatasourceConnection connection, ApiTableConfig tableConfig, Map<String, Object> context) {
        try {
            ApiConnectionConfig connectionConfig = connection.apiConnectionConfig();
            if (connectionConfig == null) {
                throw new IllegalArgumentException("API连接配置不能为空");
            }

            ApiConnectionConfig effectiveConfig = effectiveConnectionConfig(connectionConfig, tableConfig);
            String url = expressionEvaluator.evaluateString(effectiveConfig.url(), context);
            url = appendQueryParams(url, effectiveConfig.params(), context);

            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(effectiveConfig.timeout(), 1)))
                    .build()) {

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(effectiveConfig.timeout(), 1)));

                if (effectiveConfig.headers() != null) {
                    effectiveConfig.headers().forEach((key, value) -> {
                        if (key != null && !key.isBlank()) {
                            requestBuilder.header(key, expressionEvaluator.evaluateString(value, context));
                        }
                    });
                }

                applyAuth(requestBuilder, connection.apiAuthConfig(), context);

                if (effectiveConfig.method() == HttpMethod.POST) {
                    String body = effectiveConfig.body() == null ? "" : expressionEvaluator.evaluateString(effectiveConfig.body(), context);
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                    if (effectiveConfig.headers() == null || effectiveConfig.headers().keySet().stream().map(String::toLowerCase).noneMatch("content-type"::equals)) {
                        requestBuilder.header("Content-Type", "application/json");
                    }
                } else {
                    requestBuilder.GET();
                }

                HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("API请求失败，HTTP状态码: " + response.statusCode());
                }

                return response.body();
            }
        } catch (Exception e) {
            throw new IllegalStateException("API请求执行失败: " + e.getMessage(), e);
        }
    }

    private ApiConnectionConfig effectiveConnectionConfig(ApiConnectionConfig baseConfig, ApiTableConfig tableConfig) {
        if (tableConfig == null) {
            return baseConfig;
        }
        return new ApiConnectionConfig(
                tableConfig.path() != null && !tableConfig.path().isBlank() ? tableConfig.path() : baseConfig.url(),
                tableConfig.method() != null ? tableConfig.method() : baseConfig.method(),
                baseConfig.headers(),
                baseConfig.params(),
                baseConfig.body(),
                tableConfig.paginationConfig() != null ? tableConfig.paginationConfig() : baseConfig.paginationConfig(),
                baseConfig.timeout(),
                tableConfig.jsonPath() != null ? tableConfig.jsonPath() : baseConfig.jsonPath()
        );
    }

    private ApiPaginationConfig effectivePaginationConfig(DatasourceConnection connection, ApiTableConfig tableConfig) {
        ApiPaginationConfig tableCfg = tableConfig != null ? tableConfig.paginationConfig() : null;
        if (tableCfg != null) {
            return tableCfg;
        }
        ApiConnectionConfig connectionConfig = connection.apiConnectionConfig();
        return connectionConfig == null ? null : connectionConfig.paginationConfig();
    }

    private void applyAuth(HttpRequest.Builder requestBuilder, ApiAuthConfig authConfig, Map<String, Object> context) {
        if (authConfig == null || authConfig.authType() == null || authConfig.authType() == ApiAuthType.NO_AUTH) {
            return;
        }
        switch (authConfig.authType()) {
            case NO_AUTH -> {
            }
            case BASIC_AUTH -> {
                String username = authConfig.username() == null ? "" : expressionEvaluator.evaluateString(authConfig.username(), context);
                String password = authConfig.password() == null ? "" : expressionEvaluator.evaluateString(authConfig.password(), context);
                String credentials = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                requestBuilder.header("Authorization", "Basic " + credentials);
            }
            case BEARER_TOKEN -> {
                String token = authConfig.token() == null ? "" : expressionEvaluator.evaluateString(authConfig.token(), context);
                requestBuilder.header("Authorization", "Bearer " + token);
            }
        }
    }

    private PaginatedApiResult parseResponse(String responseBody, ApiTableConfig tableConfig, ApiConnectionConfig config) {
        try {
            String jsonPath = tableConfig != null && tableConfig.jsonPath() != null ? tableConfig.jsonPath() : config.jsonPath();
            Object data = jsonPath != null && !jsonPath.isBlank() ? JsonPath.read(responseBody, jsonPath) : objectMapper.readValue(responseBody, Object.class);
            List<Map<String, Object>> dataList = toMapList(data);

            Integer totalCount = null;
            ApiPaginationConfig paginationConfig = effectivePaginationConfigFromConfig(config, tableConfig);
            if (paginationConfig != null && paginationConfig.totalCountJsonPath() != null && !paginationConfig.totalCountJsonPath().isBlank()) {
                try {
                    Object total = JsonPath.read(responseBody, paginationConfig.totalCountJsonPath());
                    if (total instanceof Number number) {
                        totalCount = number.intValue();
                    } else if (total != null) {
                        totalCount = Integer.parseInt(total.toString());
                    }
                } catch (Exception ignored) {
                }
            }

            String nextCursor = null;
            if (paginationConfig != null && paginationConfig.cursorJsonPath() != null && !paginationConfig.cursorJsonPath().isBlank()) {
                try {
                    Object cursor = JsonPath.read(responseBody, paginationConfig.cursorJsonPath());
                    nextCursor = cursor == null ? null : cursor.toString();
                } catch (Exception ignored) {
                }
            }

            boolean hasMore = nextCursor != null && !nextCursor.isBlank();
            if (totalCount != null && dataList.size() >= totalCount) {
                hasMore = false;
            }
            return new PaginatedApiResult(dataList, totalCount, nextCursor, hasMore);
        } catch (PathNotFoundException e) {
            return new PaginatedApiResult(List.of(), null, null, false);
        } catch (Exception e) {
            throw new IllegalStateException("解析API响应失败: " + e.getMessage(), e);
        }
    }

    private ApiPaginationConfig effectivePaginationConfigFromConfig(ApiConnectionConfig config, ApiTableConfig tableConfig) {
        return tableConfig != null && tableConfig.paginationConfig() != null ? tableConfig.paginationConfig() : config.paginationConfig();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object data) {
        if (data == null) {
            return List.of();
        }
        if (data instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                result.add(toMap(item));
            }
            return result;
        }
        return List.of(toMap(data));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object item) {
        if (item == null) {
            return Map.of();
        }
        if (item instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return objectMapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private String appendQueryParams(String url, Map<String, String> params, Map<String, Object> context) {
        if (params == null || params.isEmpty()) {
            return url;
        }

        StringBuilder queryBuilder = new StringBuilder();
        try {
            URI uri = URI.create(url);
            if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                queryBuilder.append(uri.getQuery());
            }
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                String value = expressionEvaluator.evaluateString(entry.getValue(), context);
                if (value == null) {
                    continue;
                }
                if (!queryBuilder.isEmpty()) {
                    queryBuilder.append("&");
                }
                queryBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                queryBuilder.append("=");
                queryBuilder.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }

            String base = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
            if (queryBuilder.isEmpty()) {
                return url;
            }
            return base + "?" + queryBuilder;
        } catch (Exception e) {
            return url;
        }
    }

    private List<Map<String, Object>> truncate(List<Map<String, Object>> data, int limit) {
        if (limit <= 0 || data.size() <= limit) {
            return data;
        }
        return new ArrayList<>(data.subList(0, limit));
    }
}