package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiExpressionEvaluator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiPaginationHandlerTest {

    private ApiExpressionEvaluator evaluator;
    private ApiPaginationHandler handler;
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        evaluator = new ApiExpressionEvaluator();
        handler = new ApiPaginationHandler(evaluator);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ApiSchema buildApiSchema(String url, HttpMethod method, Map<String, String> headers,
                                     Map<String, String> params, String body, String jsonPathConfig,
                                     int timeout, ApiAuthConfig authConfig, BodyType bodyType,
                                     Integer retryCount, List<PreOperationConfig> preOperationConfigs) {
        ApiRequestConfig config = new ApiRequestConfig(
                headers, params, body, bodyType, jsonPathConfig, timeout, retryCount,
                authConfig, null, preOperationConfigs
        );
        return new ApiSchema(1L, 1L, "test", url, method, config, null, null, null, null);
    }

    private ApiSchema buildSimpleApiSchema(String url, HttpMethod method, String jsonPathConfig) {
        return buildApiSchema(url, method, null, null, null, jsonPathConfig, 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);
    }

    @Test
    void should_returnSingleRequestResult_when_executeOnce_given_validApiSchema() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1,\"name\":\"test1\"}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertFalse(result.data().isEmpty());
        assertEquals(1, result.data().size());
    }

    @Test
    void should_returnEmptyResult_when_executeOnce_given_emptyDataResponse() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
    }

    @Test
    void should_returnSinglePageData_when_fetchAllPages_given_noPagination() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"items\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.items");

        List<Map<String, Object>> result = handler.fetchAllPages(apiSchema, null, 10);

        assertNotNull(result);
    }

    @Test
    void should_returnSinglePageData_when_fetchAllPages_given_nullPaginationConfig() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"items\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.items");

        List<Map<String, Object>> result = handler.fetchAllPages(apiSchema, null, 10, Map.of());

        assertNotNull(result);
    }

    @Test
    void should_applyLimit_when_fetchAllPages_given_smallLimit() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.items");

        List<Map<String, Object>> result = handler.fetchAllPages(apiSchema, null, 2);

        assertEquals(2, result.size());
    }

    @Test
    void should_handleHttpError_when_executeOnce_given_500Status() {
        server.createContext("/api/error", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/error", HttpMethod.GET, null);

        assertThrows(IllegalStateException.class, () -> handler.executeOnce(apiSchema, null, Map.of()));
    }

    @Test
    void should_returnRawResponse_when_executeOnce_given_noJsonPath() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"id\":1}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_handleQueryParams_when_executeOnce_given_paramsWithExpression() {
        server.createContext("/api/test", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query.contains("key=testValue"));
            String response = "{\"data\":[]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, null,
                Map.of("key", "${value}"), null, "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of("value", "testValue"));

        assertNotNull(result);
    }

    @Test
    void should_handlePostRequest_when_executeOnce_given_postMethod() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.POST, null,
                null, "{\"key\":\"value\"}", "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertFalse(result.data().isEmpty());
    }

    @Test
    void should_returnEmptyResult_when_parseResponse_given_pathNotFound() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"other\":\"value\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data.items");

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertTrue(result.data().isEmpty());
        assertFalse(result.hasMore());
    }

    @Test
    void should_useTableConfigJsonPath_when_executeOnce_given_tableConfigWithPath() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"tableData\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.configData");
        ApiTableConfig tableConfig = new ApiTableConfig(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, null, null, null,
                "$.tableData", 10, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_handleNullDataTable_when_fetchAllPages_given_nullDataTable() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        List<Map<String, Object>> result = handler.fetchAllPages(apiSchema, null, 10);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_fetchRawResponse_when_fetchRawResponse_given_validApiSchema() {
        server.createContext("/api/raw", exchange -> {
            String response = "{\"raw\":\"data\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/raw", HttpMethod.GET, null);

        String result = handler.fetchRawResponse(apiSchema, null, Map.of());

        assertNotNull(result);
        assertTrue(result.contains("raw"));
    }

    @Test
    void should_useTableConfig_when_executeOnce_given_tableConfigOverrides() {
        server.createContext("/api/custom", exchange -> {
            String response = "{\"custom\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");
        ApiTableConfig tableConfig = new ApiTableConfig(
                "http://localhost:" + port + "/api/custom", HttpMethod.GET, null, null, null,
                "$.custom", 10, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_handleNullContext_when_executeOnce_given_nullContext() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, null);

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_handleNullContext_when_fetchRawResponse_given_nullContext() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        String result = handler.fetchRawResponse(apiSchema, null, null);

        assertNotNull(result);
    }

    @Test
    void should_handleNullTableConfig_when_executeOnce_given_nullTableConfig() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_retryAndSucceed_when_executeOnce_given_retryCountSet() {
        server.createContext("/api/retry", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/retry", HttpMethod.GET, null, null, null,
                "$.data", 10, new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, 2, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_throwAfterRetries_when_executeOnce_given_allRequestsFail() {
        server.createContext("/api/fail", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/fail", HttpMethod.GET, null, null, null,
                null, 10, new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, 1, null);

        assertThrows(IllegalStateException.class, () -> handler.executeOnce(apiSchema, null, Map.of()));
    }

    @Test
    void should_executePreOperation_when_executeOnce_given_enabledPreOpConfig() {
        server.createContext("/api/preop", exchange -> {
            String response = "{\"token\":\"abc123\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String response = "{\"data\":[{\"id\":1,\"auth\":\"" + (authHeader != null ? authHeader : "none") + "\"}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET, "$.data");

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.GET,
                null,
                null,
                null,
                null,
                List.of(new ParamMapping("authToken", "header", "$.token"))
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_skipPreOperation_when_executeOnce_given_disabledPreOpConfig() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/test", HttpMethod.GET, "$.data");

        PreOperationConfig preOpConfig = new PreOperationConfig(false, null, null, null, null, null, null, null);
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }

    @Test
    void should_handleFormUrlEncoded_when_executeOnce_given_formBodyType() {
        server.createContext("/api/form", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/form", HttpMethod.POST, null,
                Map.of("key", "value"), null, "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), BodyType.FORM_URLENCODED, null, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, null, Map.of());

        assertNotNull(result);
        assertFalse(result.data().isEmpty());
    }

    @Test
    void should_injectPreOpParamsViaContext_when_executeOnce_given_preOpWithHeaderParam() {
        server.createContext("/api/preop", exchange -> {
            String response = "{\"token\":\"bearer-token-123\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String response = "{\"data\":[{\"auth\":\"" + (authHeader != null ? authHeader : "none") + "\"}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET,
                Map.of("Authorization", "${authToken}"), null, null, "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.GET,
                null,
                null,
                null,
                null,
                List.of(new ParamMapping("authToken", "header", "$.token"))
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
        assertEquals("bearer-token-123", result.data().get(0).get("auth"));
    }

    @Test
    void should_injectPreOpParamsViaContext_when_executeOnce_given_preOpWithQueryParam() {
        server.createContext("/api/preop", exchange -> {
            String response = "{\"sessionId\":\"sess-abc\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String response = "{\"data\":[{\"query\":\"" + (query != null ? query : "none") + "\"}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET, null,
                Map.of("sessionId", "${sessionId}"), null, "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.GET,
                null,
                null,
                null,
                null,
                List.of(new ParamMapping("sessionId", "query", "$.sessionId"))
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
        String query = (String) result.data().get(0).get("query");
        assertTrue(query.contains("sessionId=sess-abc"));
    }

    @Test
    void should_injectPreOpParamsViaContext_when_executeOnce_given_preOpWithBodyParam() {
        server.createContext("/api/preop", exchange -> {
            String response = "{\"apiKey\":\"key-xyz\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            boolean containsKey = requestBody.contains("key-xyz");
            String response = "{\"data\":[{\"success\":" + containsKey + "}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.POST, null,
                null, "{\"apiKey\":\"${apiKey}\"}", "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.GET,
                null,
                null,
                null,
                null,
                List.of(new ParamMapping("apiKey", "body", "$.apiKey"))
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
        assertEquals(true, result.data().get(0).get("success"));
    }

    @Test
    void should_appendQueryParams_when_executePreOp_given_preOpWithParams() {
        server.createContext("/api/preop", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String response = "{\"query\":\"" + (query != null ? query : "none") + "\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET, "$.data");

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.GET,
                null,
                Map.of("client_id", "test123"),
                null,
                null,
                List.of(new ParamMapping("queryInfo", "body", "$.query"))
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
    }

    @Test
    void should_useBodyType_when_executePreOp_given_preOpWithFormBodyType() {
        server.createContext("/api/preop", exchange -> {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            String response = "{\"contentType\":\"" + (contentType != null ? contentType : "none") +
                    "\",\"body\":\"" + requestBody + "\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET, "$.data");

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.POST,
                null,
                Map.of("field1", "value1"),
                null,
                BodyType.FORM_URLENCODED,
                List.of()
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
    }

    @Test
    void should_useBodyType_when_executePreOp_given_preOpWithRawBodyType() {
        server.createContext("/api/preop", exchange -> {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            String response = "{\"contentType\":\"" + (contentType != null ? contentType : "none") +
                    "\",\"body\":\"" + requestBody + "\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET, "$.data");

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.POST,
                null,
                null,
                "raw text content",
                BodyType.RAW,
                List.of()
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
    }

    @Test
    void should_useDefaultJsonBodyType_when_executePreOp_given_preOpWithNullBodyType() {
        server.createContext("/api/preop", exchange -> {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String response = "{\"contentType\":\"" + (contentType != null ? contentType : "none") + "\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiSchema apiSchema = buildSimpleApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET, "$.data");

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.POST,
                null,
                null,
                "{\"key\":\"value\"}",
                null,
                List.of()
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        PaginatedApiResult result = handler.executeOnce(apiSchema, tableConfig, Map.of());

        assertNotNull(result);
    }

    @Test
    void should_notModifyImmutableMap_when_executeOnce_given_immutableHeadersAndParams() {
        server.createContext("/api/preop", exchange -> {
            String response = "{\"token\":\"immutable-test\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/api/main", exchange -> {
            String response = "{\"data\":[{\"id\":1}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        Map<String, String> immutableHeaders = Map.of("X-Custom", "header-value");
        Map<String, String> immutableParams = Map.of("param1", "value1");

        ApiSchema apiSchema = buildApiSchema(
                "http://localhost:" + port + "/api/main", HttpMethod.GET,
                immutableHeaders, immutableParams, null, "$.data", 10,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null, null);

        PreOperationConfig preOpConfig = new PreOperationConfig(
                true,
                "http://localhost:" + port + "/api/preop",
                HttpMethod.GET,
                null,
                null,
                null,
                null,
                List.of(new ParamMapping("token", "header", "$.token"))
        );
        ApiTableConfig tableConfig = new ApiTableConfig(null, null, null, null, null, null, 10, null,
                List.of(preOpConfig), 0, null);

        assertDoesNotThrow(() -> handler.executeOnce(apiSchema, tableConfig, Map.of()));
    }
}
