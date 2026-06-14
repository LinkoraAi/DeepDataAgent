package com.linkroa.deepdataagent.datasource.infrastructure.client;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiExpressionEvaluator;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Supplier;

@Component
public class ApiPaginationHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiPaginationHandler.class);
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_RETRY_COUNT = 5;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;

    private final ApiExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ApiPaginationHandler(ApiExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    public PaginatedApiResult executeOnce(ApiSchema apiSchema, ApiTableConfig tableConfig, Map<String, Object> context) {
        return executeRequest(apiSchema, tableConfig, context == null ? Map.of() : context);
    }

    public String fetchRawResponse(ApiSchema apiSchema, ApiTableConfig tableConfig, Map<String, Object> context) {
        return executeRawResponse(apiSchema, tableConfig, context == null ? Map.of() : context);
    }

    public List<Map<String, Object>> fetchAllPages(ApiSchema apiSchema, ApiTableConfig tableConfig, int limit) {
        return fetchAllPages(apiSchema, tableConfig, limit, Map.of());
    }

    public List<Map<String, Object>> fetchAllPages(ApiSchema apiSchema, ApiTableConfig tableConfig, int limit, Map<String, Object> context) {
        ApiPaginationConfig paginationConfig = effectivePaginationConfig(apiSchema, tableConfig);
        if (paginationConfig == null || paginationConfig.paginationType() == null || paginationConfig.paginationType() == ApiPaginationType.NONE) {
            return truncate(executeOnce(apiSchema, tableConfig, context).data(), limit);
        }

        return switch (paginationConfig.paginationType()) {
            case PAGE_BASED -> fetchByPageNumber(apiSchema, tableConfig, limit, context);
            case NONE -> truncate(executeOnce(apiSchema, tableConfig, context).data(), limit);
        };
    }

    private List<Map<String, Object>> fetchByPageNumber(ApiSchema apiSchema, ApiTableConfig tableConfig, int limit, Map<String, Object> context) {
        ApiPaginationConfig paginationConfig = effectivePaginationConfig(apiSchema, tableConfig);
        int pageSize = paginationConfig != null && paginationConfig.pageSize() != null ? paginationConfig.pageSize() : DEFAULT_PAGE_SIZE;
        int maxPages = paginationConfig != null && paginationConfig.maxPages() != null ? paginationConfig.maxPages() : DEFAULT_MAX_PAGES;

        List<Map<String, Object>> allData = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            Map<String, Object> pageContext = new LinkedHashMap<>(context);
            pageContext.put("pageNumber", pageNumber);
            pageContext.put("pageSize", pageSize);
            pageContext.put("page", pageNumber);
            pageContext.put("size", pageSize);

            PaginatedApiResult result = executeRequest(apiSchema, tableConfig, pageContext);
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

    private PaginatedApiResult executeRequest(ApiSchema apiSchema, ApiTableConfig tableConfig, Map<String, Object> context) {
        ResolvedConfig resolved = resolveConfig(apiSchema, tableConfig);
        return executeHttpRequest(resolved, context,
                responseBody -> parseResponse(responseBody, resolved));
    }

    private String executeRawResponse(ApiSchema apiSchema, ApiTableConfig tableConfig, Map<String, Object> context) {
        ResolvedConfig resolved = resolveConfig(apiSchema, tableConfig);
        return executeHttpRequest(resolved, context, responseBody -> responseBody);
    }

    private <T> T executeHttpRequest(ResolvedConfig resolved, Map<String, Object> context, java.util.function.Function<String, T> responseHandler) {
        try {
            Map<String, Object> mergedContext = new LinkedHashMap<>(context);
            int retryCount = resolved.retryCount() != null ? resolved.retryCount() : 0;

            if (resolved.preOperationConfigs() != null) {
                for (PreOperationConfig preOpConfig : resolved.preOperationConfigs()) {
                    if (preOpConfig != null && preOpConfig.enabled()) {
                        Map<String, Object> preOpParams = executePreOperation(preOpConfig, mergedContext, resolved.timeout(), retryCount);
                        injectPreOpParamsToContext(mergedContext, preOpParams, preOpConfig);
                    }
                }
            }

            BodyType effectiveBodyType = resolved.bodyType() != null ? resolved.bodyType() : BodyType.JSON;
            boolean skipQueryParams = resolved.method() == HttpMethod.POST && effectiveBodyType == BodyType.FORM_URLENCODED;
            String url = skipQueryParams
                    ? expressionEvaluator.evaluateString(resolved.url(), mergedContext)
                    : appendQueryParams(expressionEvaluator.evaluateString(resolved.url(), mergedContext), resolved.params(), mergedContext);

            StringBuilder preOpQueryParams = new StringBuilder();
            if (resolved.preOperationConfigs() != null) {
                for (PreOperationConfig preOpConfig : resolved.preOperationConfigs()) {
                    if (preOpConfig != null && preOpConfig.paramMappings() != null) {
                        for (ParamMapping mapping : preOpConfig.paramMappings()) {
                            if ("query".equalsIgnoreCase(mapping.paramLocation())) {
                                Object value = mergedContext.get(mapping.paramName());
                                if (value != null) {
                                    if (!preOpQueryParams.isEmpty()) {
                                        preOpQueryParams.append("&");
                                    }
                                    preOpQueryParams.append(URLEncoder.encode(mapping.paramName(), StandardCharsets.UTF_8));
                                    preOpQueryParams.append("=");
                                    preOpQueryParams.append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                }
            }
            if (!preOpQueryParams.isEmpty()) {
                String separator = url.contains("?") ? "&" : "?";
                url = url + separator + preOpQueryParams;
            }

            final String finalUrl = url;
            final ResolvedConfig finalConfig = resolved;
            final Map<String, Object> finalContext = mergedContext;

            return executeWithRetry(() -> {
                try (HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(finalConfig.timeout(), 1)))
                        .build()) {

                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(finalUrl))
                            .timeout(Duration.ofSeconds(Math.max(finalConfig.timeout(), 1)));

                    if (finalConfig.headers() != null) {
                        finalConfig.headers().forEach((key, value) -> {
                            if (key != null && !key.isBlank()) {
                                requestBuilder.header(key, expressionEvaluator.evaluateString(value, finalContext));
                            }
                        });
                    }

                    applyAuth(requestBuilder, finalConfig.authConfig(), finalContext);

                    if (finalConfig.preOperationConfigs() != null) {
                        for (PreOperationConfig preOpConfig : finalConfig.preOperationConfigs()) {
                            if (preOpConfig != null && preOpConfig.paramMappings() != null) {
                                for (ParamMapping mapping : preOpConfig.paramMappings()) {
                                    if ("header".equalsIgnoreCase(mapping.paramLocation())) {
                                        Object value = finalContext.get(mapping.paramName());
                                        if (value != null) {
                                            requestBuilder.header(mapping.paramName(), value.toString());
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (finalConfig.method() == HttpMethod.POST) {
                        String body = buildRequestBody(finalConfig, effectiveBodyType, finalContext);
                        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                        if (finalConfig.headers() == null || finalConfig.headers().keySet().stream().map(String::toLowerCase).noneMatch("content-type"::equals)) {
                            requestBuilder.header("Content-Type", resolveContentType(effectiveBodyType));
                        }
                    } else {
                        requestBuilder.GET();
                    }

                    HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("API请求失败，HTTP状态码: " + response.statusCode());
                    }

                    return responseHandler.apply(response.body());
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("API请求执行失败: " + e.getMessage(), e);
                }
            }, retryCount);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("API请求执行失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> executePreOperation(PreOperationConfig preOpConfig, Map<String, Object> context, int timeout, int retryCount) {
        if (preOpConfig == null || !preOpConfig.enabled()) {
            return Map.of();
        }
        try {
            BodyType preOpBodyType = preOpConfig.bodyType() != null ? preOpConfig.bodyType() : BodyType.JSON;
            boolean skipQueryParams = preOpConfig.method() == HttpMethod.POST && preOpBodyType == BodyType.FORM_URLENCODED;
            String url = expressionEvaluator.evaluateString(preOpConfig.url(), context);
            if (!skipQueryParams) {
                url = appendQueryParams(url, preOpConfig.params(), context);
            }
            String finalUrl = url;
            return executeWithRetry(() -> {
                try (HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(timeout, 1)))
                        .build()) {
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(finalUrl))
                            .timeout(Duration.ofSeconds(Math.max(timeout, 1)));
                    if (preOpConfig.headers() != null) {
                        preOpConfig.headers().forEach((key, value) -> {
                            if (key != null && !key.isBlank()) {
                                requestBuilder.header(key, expressionEvaluator.evaluateString(value, context));
                            }
                        });
                    }
                    if (preOpConfig.method() == HttpMethod.POST) {
                        String body;
                        if (preOpBodyType == BodyType.FORM_URLENCODED && preOpConfig.params() != null && !preOpConfig.params().isEmpty()) {
                            body = encodeFormUrlEncoded(preOpConfig.params(), context);
                        } else {
                            body = preOpConfig.body() == null ? "" : expressionEvaluator.evaluateString(preOpConfig.body(), context);
                        }
                        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                        if (preOpConfig.headers() == null || preOpConfig.headers().keySet().stream().map(String::toLowerCase).noneMatch("content-type"::equals)) {
                            String contentType = resolveContentType(preOpBodyType);
                            requestBuilder.header("Content-Type", contentType);
                        }
                    } else {
                        requestBuilder.GET();
                    }
                    HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("前置操作请求失败，HTTP状态码: " + response.statusCode());
                    }
                    return extractParamsFromResponse(response.body(), preOpConfig.paramMappings());
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("前置操作执行失败: " + e.getMessage(), e);
                }
            }, retryCount);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("前置操作执行失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> extractParamsFromResponse(String responseBody, List<ParamMapping> paramMappings) {
        if (paramMappings == null || paramMappings.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (ParamMapping mapping : paramMappings) {
            try {
                Object value = JsonPath.read(responseBody, mapping.jsonPath());
                params.put(mapping.paramName(), value == null ? "" : value.toString());
            } catch (PathNotFoundException e) {
                params.put(mapping.paramName(), "");
            }
        }
        return params;
    }

    private void injectPreOpParamsToContext(Map<String, Object> mergedContext, Map<String, Object> preOpParams, PreOperationConfig preOpConfig) {
        if (preOpConfig == null || preOpConfig.paramMappings() == null || preOpParams.isEmpty()) {
            return;
        }
        for (ParamMapping mapping : preOpConfig.paramMappings()) {
            Object value = preOpParams.get(mapping.paramName());
            if (value == null) {
                continue;
            }
            mergedContext.put(mapping.paramName(), value.toString());
        }
    }

    private String resolveContentType(BodyType bodyType) {
        if (bodyType == BodyType.FORM_URLENCODED) {
            return "application/x-www-form-urlencoded";
        }
        if (bodyType == BodyType.RAW) {
            return "text/plain";
        }
        return "application/json";
    }

    private String buildRequestBody(ResolvedConfig config, BodyType bodyType, Map<String, Object> context) {
        if (bodyType == BodyType.FORM_URLENCODED && config.params() != null && !config.params().isEmpty()) {
            if (StringUtils.isNotBlank(config.body())) {
                log.warn("FORM_URLENCODED模式下忽略body配置，使用params编码请求体");
            }
            return encodeFormUrlEncoded(config.params(), context);
        }
        return config.body() == null ? "" : expressionEvaluator.evaluateString(config.body(), context);
    }

    private String encodeFormUrlEncoded(Map<String, String> params, Map<String, Object> context) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String value = expressionEvaluator.evaluateString(entry.getValue(), context);
            if (value == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private <T> T executeWithRetry(Supplier<T> request, int maxRetries) {
        int effectiveRetries = Math.min(Math.max(maxRetries, 0), MAX_RETRY_COUNT);
        IllegalStateException lastException = null;
        for (int attempt = 0; attempt <= effectiveRetries; attempt++) {
            try {
                return request.get();
            } catch (IllegalStateException e) {
                lastException = e;
                if (attempt < effectiveRetries) {
                    try {
                        long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << attempt), MAX_BACKOFF_MS);
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw lastException;
                    }
                }
            }
        }
        throw lastException;
    }

    private ResolvedConfig resolveConfig(ApiSchema apiSchema, ApiTableConfig tableConfig) {
        if (apiSchema == null) {
            throw new IllegalArgumentException("ApiSchema不能为空");
        }
        ApiRequestConfig requestConfig = apiSchema.config();
        if (requestConfig == null) {
            requestConfig = ApiRequestConfig.defaultConfig();
        }

        String url = apiSchema.url();
        HttpMethod method = apiSchema.method();
        Map<String, String> headers = requestConfig.headers();
        Map<String, String> params = requestConfig.params();
        String body = requestConfig.body();
        BodyType bodyType = requestConfig.bodyType();
        String jsonPathConfig = requestConfig.jsonPathConfig();
        int timeout = requestConfig.timeout() > 0 ? requestConfig.timeout() : 180;
        Integer retryCount = requestConfig.retryCount();
        ApiAuthConfig authConfig = requestConfig.authConfig();
        ApiPaginationConfig paginationConfig = requestConfig.paginationConfig();
        List<PreOperationConfig> preOperationConfigs = requestConfig.preOperationConfigs();

        if (tableConfig != null) {
            if (StringUtils.isNotBlank(tableConfig.url())) {
                url = tableConfig.url();
            }
            if (tableConfig.method() != null) {
                method = tableConfig.method();
            }
            if (tableConfig.headers() != null) {
                headers = tableConfig.headers();
            }
            if (tableConfig.params() != null) {
                params = tableConfig.params();
            }
            if (StringUtils.isNotBlank(tableConfig.body())) {
                body = tableConfig.body();
            }
            if (tableConfig.bodyType() != null) {
                bodyType = tableConfig.bodyType();
            }
            if (StringUtils.isNotBlank(tableConfig.jsonPathConfig())) {
                jsonPathConfig = tableConfig.jsonPathConfig();
            }
            if (tableConfig.timeout() > 0) {
                timeout = tableConfig.timeout();
            }
            if (tableConfig.retryCount() != null) {
                retryCount = tableConfig.retryCount();
            }
            if (tableConfig.paginationConfig() != null) {
                paginationConfig = tableConfig.paginationConfig();
            }
            if (tableConfig.preOperationConfigs() != null && !tableConfig.preOperationConfigs().isEmpty()) {
                preOperationConfigs = tableConfig.preOperationConfigs();
            }
        }

        return new ResolvedConfig(url, method, headers, params, body, bodyType, jsonPathConfig, timeout, retryCount, authConfig, paginationConfig, preOperationConfigs);
    }

    private ApiPaginationConfig effectivePaginationConfig(ApiSchema apiSchema, ApiTableConfig tableConfig) {
        if (tableConfig != null && tableConfig.paginationConfig() != null) {
            return tableConfig.paginationConfig();
        }
        ApiRequestConfig requestConfig = apiSchema != null ? apiSchema.config() : null;
        return requestConfig == null ? null : requestConfig.paginationConfig();
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
        }
    }

    private PaginatedApiResult parseResponse(String responseBody, ResolvedConfig resolved) {
        try {
            String jsonPath = resolved.jsonPathConfig();
            Object data = jsonPath != null && !jsonPath.isBlank() ? JsonPath.read(responseBody, jsonPath) : objectMapper.readValue(responseBody, Object.class);
            List<Map<String, Object>> dataList = toMapList(data);

            Integer totalCount = null;
            ApiPaginationConfig paginationConfig = resolved.paginationConfig();
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

            boolean hasMore = true;
            if (totalCount != null && dataList.size() >= totalCount) {
                hasMore = false;
            }
            if (dataList.isEmpty()) {
                hasMore = false;
            }
            return new PaginatedApiResult(dataList, totalCount, hasMore);
        } catch (PathNotFoundException e) {
            return new PaginatedApiResult(List.of(), null, false);
        } catch (Exception e) {
            throw new IllegalStateException("解析API响应失败: " + e.getMessage(), e);
        }
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

    private record ResolvedConfig(
            String url,
            HttpMethod method,
            Map<String, String> headers,
            Map<String, String> params,
            String body,
            BodyType bodyType,
            String jsonPathConfig,
            int timeout,
            Integer retryCount,
            ApiAuthConfig authConfig,
            ApiPaginationConfig paginationConfig,
            List<PreOperationConfig> preOperationConfigs
    ) {
    }
}
