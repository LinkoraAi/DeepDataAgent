package com.linkroa.deepdataagent.agent.infrastructure.adapter;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.JdbcConnectionConfig;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.SchemaCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DatasourceGatewayAdapter 单元测试（API 数据源场景）
 * <p>测试 API 数据源的 extractSchema、executeApiQuery 和 findDatasource 行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class DatasourceGatewayAdapterTest {

    @Mock
    private DatasourceConnectionRepository repository;

    @Mock
    private DatasourceConnectionStrategyFactory strategyFactory;

    @Mock
    private DatasourceConnectionStrategy strategy;

    @Mock
    private ApiSchemaRepository apiSchemaRepository;

    @Mock
    private ApiFieldRepository apiFieldRepository;

    @Mock
    private ApiPaginationHandler paginationHandler;

    @Mock
    private DatabaseSchemaRepository databaseSchemaRepository;

    @Mock
    private TableInfoRepository tableInfoRepository;

    @Mock
    private ColumnInfoRepository columnInfoRepository;

    @Mock
    private SchemaCachePort schemaCachePort;

    private DatasourceGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DatasourceGatewayAdapter(
                schemaCachePort, repository, strategyFactory, apiSchemaRepository, apiFieldRepository, paginationHandler,
                databaseSchemaRepository, tableInfoRepository, columnInfoRepository
        );
    }

    // ==================== extractSchema (API 类型) ====================

    @Test
    void should_returnApiSchemaText_when_extractSchema_given_apiDatasourceWithSchemas() {
        // given
        Long connectionId = 1L;
        DatasourceConnection apiConnection = createApiConnection(connectionId, "api-datasource");
        when(repository.findById(connectionId)).thenReturn(Optional.of(apiConnection));

        ApiSchema userSchema = createApiSchema(10L, connectionId, "user_api", "https://api.example.com/users", HttpMethod.GET);
        when(apiSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of(userSchema));

        ApiField idField = createApiField(100L, 10L, "user_id", "用户ID", "$.id", "string", "用户唯一标识");
        ApiField nameField = createApiField(101L, 10L, "user_name", "用户名", "$.name", "string", "用户名称");
        when(apiFieldRepository.findByApiSchemaId(10L)).thenReturn(List.of(idField, nameField));

        // when
        String schema = adapter.extractSchema(connectionId);

        // then
        assertTrue(schema.contains("表: user_api"));
        assertTrue(schema.contains("GET https://api.example.com/users"));
        assertTrue(schema.contains("用户ID"));
        assertTrue(schema.contains("用户名"));
        assertTrue(schema.contains("string"));
        verify(apiSchemaRepository).findByConnectionId(connectionId);
        verify(apiFieldRepository).findByApiSchemaId(10L);
    }

    @Test
    void should_throwException_when_extractSchema_given_apiDatasourceWithNoSchemas() {
        // given
        Long connectionId = 2L;
        DatasourceConnection apiConnection = createApiConnection(connectionId, "empty-api");
        when(repository.findById(connectionId)).thenReturn(Optional.of(apiConnection));
        when(apiSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of());

        // when & then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> adapter.extractSchema(connectionId));
        assertTrue(exception.getMessage().contains("API 数据源未配置任何 Schema"));
    }

    @Test
    void should_includeOriginalName_when_extractSchema_given_fieldWithDifferentOriginalName() {
        // given
        Long connectionId = 3L;
        DatasourceConnection apiConnection = createApiConnection(connectionId, "api-test");
        when(repository.findById(connectionId)).thenReturn(Optional.of(apiConnection));

        ApiSchema schema = createApiSchema(20L, connectionId, "order_api", "https://api.example.com/orders", HttpMethod.POST);
        when(apiSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of(schema));

        ApiField field = createApiField(200L, 20L, "order_amount", "订单金额", "$.amount", "number", "订单总金额");
        when(apiFieldRepository.findByApiSchemaId(20L)).thenReturn(List.of(field));

        // when
        String result = adapter.extractSchema(connectionId);

        // then
        assertTrue(result.contains("订单金额 (order_amount)"));
    }

    @Test
    void should_notShowOriginalName_when_extractSchema_given_sameOriginalAndDisplayName() {
        // given
        Long connectionId = 4L;
        DatasourceConnection apiConnection = createApiConnection(connectionId, "api-test2");
        when(repository.findById(connectionId)).thenReturn(Optional.of(apiConnection));

        ApiSchema schema = createApiSchema(30L, connectionId, "product_api", "https://api.example.com/products", HttpMethod.GET);
        when(apiSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of(schema));

        ApiField field = createApiField(300L, 30L, "name", "name", "$.name", "string", null);
        when(apiFieldRepository.findByApiSchemaId(30L)).thenReturn(List.of(field));

        // when
        String result = adapter.extractSchema(connectionId);

        // then
        assertTrue(result.contains("name (string)"));
        assertFalse(result.contains("name (name)"));
    }

    @Test
    void should_throwException_when_extractSchema_given_nonexistentDatasource() {
        // given
        Long connectionId = 999L;
        when(repository.findById(connectionId)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> adapter.extractSchema(connectionId));
        assertTrue(exception.getMessage().contains("数据源不存在"));
    }

    // ==================== extractSchema (JDBC 类型 + 缓存/裁剪) ====================

    @Test
    void should_returnCachedSchema_when_extractSchema_given_cacheHit() {
        // given - Schema 缓存命中，直接返回缓存文本，不访问仓储与远程策略
        Long connectionId = 10L;
        when(schemaCachePort.get(connectionId)).thenReturn(Optional.of("cached-schema"));

        // when
        String result = adapter.extractSchema(connectionId);

        // then
        assertEquals("cached-schema", result);
        verify(schemaCachePort).get(connectionId);
        verify(repository, never()).findById(any());
        verify(schemaCachePort, never()).put(any(), any());
    }

    @Test
    void should_putSchemaCache_when_extractSchema_given_cacheMiss() {
        // given - JDBC 数据源，首次提取（缓存未命中）
        Long connectionId = 10L;
        DatasourceConnection jdbcConnection = createJdbcConnection(connectionId, "jdbc-source");
        when(schemaCachePort.get(connectionId)).thenReturn(Optional.empty());
        when(repository.findById(connectionId)).thenReturn(Optional.of(jdbcConnection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(jdbcConnection)).thenReturn(List.of(createSchema("db")));
        when(strategy.extractTables(jdbcConnection, "db")).thenReturn(List.of(createTable("orders")));
        when(strategy.extractColumns(jdbcConnection, "db", "orders")).thenReturn(List.of(createColumn("order_id")));

        // when
        String schema = adapter.extractSchema(connectionId);

        // then - 提取结果写入缓存
        assertTrue(schema.contains("表: orders"));
        verify(schemaCachePort).get(connectionId);
        verify(schemaCachePort).put(eq(connectionId), contains("表: orders"));
        verify(repository, times(1)).findById(connectionId);
        verify(strategy, times(1)).extractSchemas(jdbcConnection);
    }

    @Test
    void should_trimColumns_when_extractSchema_given_exceedingMaxTables() {
        // given - JDBC 数据源，表数超过 50，只输出表清单不展开字段
        Long connectionId = 11L;
        DatasourceConnection jdbcConnection = createJdbcConnection(connectionId, "jdbc-big");
        when(repository.findById(connectionId)).thenReturn(Optional.of(jdbcConnection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(jdbcConnection)).thenReturn(List.of(createSchema("db")));
        List<TableInfo> manyTables = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> createTable("tbl_" + i))
                .collect(Collectors.toList());
        when(strategy.extractTables(jdbcConnection, "db")).thenReturn(manyTables);

        // when
        String result = adapter.extractSchema(connectionId);

        // then - 表清单保留，但未展开字段（extractColumns 未被调用）
        assertTrue(result.contains("表: tbl_1"));
        assertTrue(result.contains("表: tbl_51"));
        assertTrue(result.contains("表数超过"));
        verify(strategy, never()).extractColumns(any(), any(), any());
    }

    // ==================== extractSchema (JDBC 本地优先) ====================

    @Test
    void should_readLocalMetadata_when_extractSchema_given_localSchemaPresent() {
        // given - JDBC 数据源，本地已同步元数据，应直接读本地而非连远程
        Long connectionId = 20L;
        DatasourceConnection jdbcConnection = createJdbcConnection(connectionId, "jdbc-local");
        when(repository.findById(connectionId)).thenReturn(Optional.of(jdbcConnection));

        DatabaseSchema localSchema = new DatabaseSchema(1L, connectionId, "db", null, null, null);
        TableInfo localTable = new TableInfo(10L, 1L, "orders", "订单表", null, null, null);
        ColumnInfo localColumn = new ColumnInfo(100L, 10L, "order_id", "INT", "订单ID", null, null, null);
        when(databaseSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of(localSchema));
        when(tableInfoRepository.findByDatabaseSchemaId(1L)).thenReturn(List.of(localTable));
        when(columnInfoRepository.findByTableId(10L)).thenReturn(List.of(localColumn));

        // when
        String result = adapter.extractSchema(connectionId);

        // then - 输出本地元数据，且不触发远程 strategy
        assertTrue(result.contains("表: orders"));
        assertTrue(result.contains("订单表"));
        assertTrue(result.contains("order_id"));
        assertTrue(result.contains("INT"));
        assertTrue(result.contains("订单ID"));
        verify(strategyFactory, never()).getStrategy(any(), any());
        verify(strategy, never()).extractSchemas(any());
    }

    @Test
    void should_fallbackToRemote_when_extractSchema_given_noLocalSchema() {
        // given - JDBC 数据源，本地无元数据，应兜底连远程
        Long connectionId = 21L;
        DatasourceConnection jdbcConnection = createJdbcConnection(connectionId, "jdbc-no-local");
        when(repository.findById(connectionId)).thenReturn(Optional.of(jdbcConnection));
        when(databaseSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of());
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(jdbcConnection)).thenReturn(List.of(createSchema("db")));
        when(strategy.extractTables(jdbcConnection, "db")).thenReturn(List.of(createTable("orders")));
        when(strategy.extractColumns(jdbcConnection, "db", "orders")).thenReturn(List.of(createColumn("order_id")));

        // when
        String result = adapter.extractSchema(connectionId);

        // then - 兜底连远程提取
        assertTrue(result.contains("表: orders"));
        assertTrue(result.contains("order_id"));
        verify(strategy).extractSchemas(jdbcConnection);
        verify(strategy).extractTables(jdbcConnection, "db");
    }

    @Test
    void should_trimColumnsOnLocal_when_extractSchema_given_localExceedingMaxTables() {
        // given - JDBC 数据源，本地表数超过 50，仅输出表清单不展开字段
        Long connectionId = 22L;
        DatasourceConnection jdbcConnection = createJdbcConnection(connectionId, "jdbc-local-big");
        when(repository.findById(connectionId)).thenReturn(Optional.of(jdbcConnection));

        DatabaseSchema localSchema = new DatabaseSchema(2L, connectionId, "db", null, null, null);
        List<TableInfo> manyTables = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> new TableInfo((long) i, 2L, "tbl_" + i, null, null, null, null))
                .collect(Collectors.toList());
        when(databaseSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of(localSchema));
        when(tableInfoRepository.findByDatabaseSchemaId(2L)).thenReturn(manyTables);

        // when
        String result = adapter.extractSchema(connectionId);

        // then - 表清单保留，但未展开字段（本地 columnInfoRepository 未被调用）
        assertTrue(result.contains("表: tbl_1"));
        assertTrue(result.contains("表: tbl_51"));
        assertTrue(result.contains("表数超过"));
        verify(columnInfoRepository, never()).findByTableId(any());
    }

    // ==================== executeApiQuery ====================

    @Test
    void should_returnData_when_executeApiQuery_given_validSchemaName() {
        // given
        Long datasourceId = 1L;
        String apiSchemaName = "user_api";
        int limit = 100;
        ApiSchema apiSchema = createApiSchema(10L, datasourceId, apiSchemaName, "https://api.example.com/users", HttpMethod.GET);
        when(apiSchemaRepository.findByConnectionIdAndName(datasourceId, apiSchemaName)).thenReturn(Optional.of(apiSchema));

        List<Map<String, Object>> expectedData = List.of(
                Map.of("id", 1, "name", "张三"),
                Map.of("id", 2, "name", "李四")
        );
        when(paginationHandler.fetchAllPages(apiSchema, null, 100)).thenReturn(expectedData);

        // when
        List<Map<String, Object>> results = adapter.executeApiQuery(datasourceId, apiSchemaName, limit);

        // then
        assertEquals(2, results.size());
        assertEquals("张三", results.get(0).get("name"));
        verify(apiSchemaRepository).findByConnectionIdAndName(datasourceId, apiSchemaName);
        verify(paginationHandler).fetchAllPages(apiSchema, null, 100);
    }

    @Test
    void should_throwException_when_executeApiQuery_given_nonexistentSchemaName() {
        // given
        Long datasourceId = 2L;
        String apiSchemaName = "nonexistent_api";
        when(apiSchemaRepository.findByConnectionIdAndName(datasourceId, apiSchemaName)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> adapter.executeApiQuery(datasourceId, apiSchemaName, 100));
        assertTrue(exception.getMessage().contains("API Schema 不存在"));
        verify(paginationHandler, never()).fetchAllPages(any(), any(), anyInt());
    }

    @Test
    void should_clampLimitToRange_when_executeApiQuery_given_limitOutOfRange() {
        // given
        Long datasourceId = 3L;
        String apiSchemaName = "order_api";
        ApiSchema apiSchema = createApiSchema(30L, datasourceId, apiSchemaName, "https://api.example.com/orders", HttpMethod.GET);
        when(apiSchemaRepository.findByConnectionIdAndName(datasourceId, apiSchemaName)).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.fetchAllPages(apiSchema, null, 1)).thenReturn(List.of());

        // when - limit 为 0 时会被 Math.min(Math.max(0, 1), 1000) = 1
        adapter.executeApiQuery(datasourceId, apiSchemaName, 0);

        // then
        verify(paginationHandler).fetchAllPages(apiSchema, null, 1);
    }

    @Test
    void should_clampLimitToMax500_when_executeApiQuery_given_limitExceedsMax() {
        // given
        Long datasourceId = 4L;
        String apiSchemaName = "big_api";
        ApiSchema apiSchema = createApiSchema(40L, datasourceId, apiSchemaName, "https://api.example.com/big", HttpMethod.GET);
        when(apiSchemaRepository.findByConnectionIdAndName(datasourceId, apiSchemaName)).thenReturn(Optional.of(apiSchema));
        when(paginationHandler.fetchAllPages(apiSchema, null, 500)).thenReturn(List.of());

        // when
        adapter.executeApiQuery(datasourceId, apiSchemaName, 5000);

        // then
        verify(paginationHandler).fetchAllPages(apiSchema, null, 500);
    }

    // ==================== findDatasource (API 类型) ====================

    @Test
    void should_returnDatasourceInfoWithApiConfig_when_findDatasource_given_apiDatasource() {
        // given
        Long connectionId = 1L;
        DatasourceConnection apiConnection = createApiConnection(connectionId, "api-datasource");
        when(repository.findById(connectionId)).thenReturn(Optional.of(apiConnection));

        ApiSchema schema1 = createApiSchema(10L, connectionId, "user_api", "https://api.example.com/users", HttpMethod.GET);
        ApiSchema schema2 = createApiSchema(11L, connectionId, "order_api", "https://api.example.com/orders", HttpMethod.POST);
        when(apiSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of(schema1, schema2));

        // when
        Optional<DatasourceInfo> result = adapter.findDatasource(connectionId);

        // then
        assertTrue(result.isPresent());
        DatasourceInfo info = result.get();
        assertEquals(connectionId, info.id());
        assertEquals("api-datasource", info.name());
        assertEquals(DatasourceCategory.API, info.category());
        assertTrue(info.enabled());
        assertNotNull(info.apiConfig());
        assertEquals(connectionId, info.apiConfig().connectionId());
        assertEquals(2, info.apiConfig().apiSchemaNames().size());
        assertTrue(info.apiConfig().apiSchemaNames().contains("user_api"));
        assertTrue(info.apiConfig().apiSchemaNames().contains("order_api"));
        assertNull(info.jdbcConfig());
    }

    @Test
    void should_returnEmpty_when_findDatasource_given_nonexistentId() {
        // given
        Long connectionId = 999L;
        when(repository.findById(connectionId)).thenReturn(Optional.empty());

        // when
        Optional<DatasourceInfo> result = adapter.findDatasource(connectionId);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnDatasourceInfoWithEnabledFalse_when_findDatasource_given_disabledApiDatasource() {
        // given
        Long connectionId = 5L;
        DatasourceConnection disabledConnection = DatasourceConnection.restore(
                connectionId, "disabled-api", DatasourceType.API, null,
                DatasourceStatus.DISABLED, null, "已禁用的API数据源",
                LocalDateTime.now(), LocalDateTime.now(), "admin", "admin"
        );
        when(repository.findById(connectionId)).thenReturn(Optional.of(disabledConnection));
        when(apiSchemaRepository.findByConnectionId(connectionId)).thenReturn(List.of());

        // when
        Optional<DatasourceInfo> result = adapter.findDatasource(connectionId);

        // then
        assertTrue(result.isPresent());
        assertFalse(result.get().enabled());
        assertEquals(DatasourceCategory.API, result.get().category());
    }

    // ==================== 辅助方法 ====================

    private DatasourceConnection createApiConnection(Long id, String name) {
        return DatasourceConnection.restore(
                id, name, DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, "测试API数据源",
                LocalDateTime.now(), LocalDateTime.now(), "admin", "admin"
        );
    }

    private DatasourceConnection createJdbcConnection(Long id, String name) {
        JdbcConnectionConfig config = new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "pass", "testdb");
        return new DatasourceConnection(
                id, name, DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                config, null, null, null, null, null
        );
    }

    private DatabaseSchema createSchema(String schemaName) {
        return new DatabaseSchema(null, null, schemaName, null, null, null);
    }

    private TableInfo createTable(String tableName) {
        return new TableInfo(null, null, tableName, null, null, null, null);
    }

    private ColumnInfo createColumn(String columnName) {
        return new ColumnInfo(null, null, columnName, "VARCHAR", null, null, null, null);
    }

    private ApiSchema createApiSchema(Long id, Long connectionId, String name, String url, HttpMethod method) {
        return new ApiSchema(id, connectionId, name, url, method, null,
                LocalDateTime.now(), LocalDateTime.now(), "admin", "admin");
    }

    private ApiField createApiField(Long id, Long apiSchemaId, String originalName, String displayName,
                                    String jsonPath, String fieldType, String description) {
        return new ApiField(id, apiSchemaId, originalName, displayName, jsonPath, fieldType, description,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
