package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
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

    @Test
    void should_returnSingleRequestResult_when_executeOnce_given_validConnection() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[{\"id\":1,\"name\":\"test1\"}]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of());

        assertNotNull(result);
        assertFalse(result.data().isEmpty());
        assertEquals(1, result.data().size());
    }

    @Test
    void should_returnEmptyResult_when_executeOnce_given_nullConnectionConfig() {
        server.createContext("/api/test", exchange -> {
            String response = "{\"data\":[]}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.items"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);
        ApiTableConfig tableConfig = new ApiTableConfig("http://localhost:" + port + "/api/test", HttpMethod.GET, "$.items", null);

        List<Map<String, Object>> result = handler.fetchAllPages(connection, tableConfig, 10);

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.items"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);
        ApiTableConfig tableConfig = new ApiTableConfig("http://localhost:" + port + "/api/test", HttpMethod.GET, "$.items", null);

        List<Map<String, Object>> result = handler.fetchAllPages(connection, tableConfig, 10, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.items"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);
        ApiTableConfig tableConfig = new ApiTableConfig("http://localhost:" + port + "/api/test", HttpMethod.GET, "$.items", null);

        List<Map<String, Object>> result = handler.fetchAllPages(connection, tableConfig, 2);

        assertEquals(2, result.size());
    }

    @Test
    void should_throwException_when_executeOnce_given_invalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ApiConnectionConfig(
                    "not-a-valid-url",
                    HttpMethod.GET, null, null, null, null, 10, null
            );
        });
    }

    @Test
    void should_handleHttpError_when_executeOnce_given_500Status() {
        server.createContext("/api/error", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/error",
                HttpMethod.GET, null, null, null, null, 10, null
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        assertThrows(IllegalStateException.class, () -> handler.executeOnce(connection, null, Map.of()));
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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, null
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, Map.of("key", "${value}"), null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of("value", "testValue"));

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.POST, null, null, "{\"key\":\"value\"}", null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data.items"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.configData"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);
        ApiTableConfig tableConfig = new ApiTableConfig("http://localhost:" + port + "/api/test", HttpMethod.GET, "$.tableData", null);

        PaginatedApiResult result = handler.executeOnce(connection, tableConfig, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        List<Map<String, Object>> result = handler.fetchAllPages(connection, null, 10);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_fetchRawResponse_when_fetchRawResponse_given_validConnection() {
        server.createContext("/api/raw", exchange -> {
            String response = "{\"raw\":\"data\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/raw",
                HttpMethod.GET, null, null, null, null, 10, null
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        String result = handler.fetchRawResponse(connection, null, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);
        ApiTableConfig tableConfig = new ApiTableConfig(
                "http://localhost:" + port + "/api/custom",
                HttpMethod.GET,
                "$.custom",
                null
        );

        PaginatedApiResult result = handler.executeOnce(connection, tableConfig, Map.of());

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, null);

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        String result = handler.fetchRawResponse(connection, null, null);

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

        ApiConnectionConfig apiConfig = new ApiConnectionConfig(
                "http://localhost:" + port + "/api/test",
                HttpMethod.GET, null, null, null, null, 10, "$.data"
        );
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, apiConfig, authConfig, null, null, null, null, null);

        PaginatedApiResult result = handler.executeOnce(connection, null, Map.of());

        assertNotNull(result);
        assertEquals(1, result.data().size());
    }
}