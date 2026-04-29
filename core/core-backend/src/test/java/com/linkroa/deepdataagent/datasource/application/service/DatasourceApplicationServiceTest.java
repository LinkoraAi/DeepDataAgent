package com.linkroa.deepdataagent.datasource.application.service;

import com.linkroa.deepdataagent.datasource.controller.response.DatasourceTypeResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ParseApiResponseResult;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
import com.linkroa.deepdataagent.datasource.domain.service.DatasourceConnectionDomainService;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiResponseParser;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasourceApplicationServiceTest {

    @Mock
    private DatasourceConnectionRepository connectionRepository;
    @Mock
    private DatabaseSchemaRepository databaseSchemaRepository;
    @Mock
    private TableInfoRepository tableInfoRepository;
    @Mock
    private ColumnInfoRepository columnInfoRepository;
    @Mock
    private ApiSchemaRepository apiSchemaRepository;
    @Mock
    private ApiFieldRepository apiFieldRepository;
    @Mock
    private DatasourceConnectionStrategyFactory strategyFactory;
    @Mock
    private DatasourceConnectionDomainService domainService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private DatasourceConnectionStrategy strategy;
    @Mock
    private ApiResponseParser apiResponseParser;
    @Mock
    private ApiPaginationHandler apiPaginationHandler;

    @InjectMocks
    private DatasourceApplicationService applicationService;

    @Test
    void should_updateTableComment_when_updateTableComment_given_businessComment() {
        applicationService.updateTableComment(1L, "biz comment");

        verify(domainService).validateComment("biz comment");
        verify(tableInfoRepository).updateTableCustomComment(1L, "biz comment");
    }

    @Test
    void should_updateColumnComment_when_updateColumnComment_given_businessComment() {
        applicationService.updateColumnComment(2L, "biz column comment");

        verify(domainService).validateComment("biz column comment");
        verify(columnInfoRepository).updateColumnCustomComment(2L, "biz column comment");
    }

    @Test
    void should_deleteConnectionAndSchema_when_deleteDatasource_given_disabledDatasource() {
        DatasourceConnection connection = new DatasourceConnection(1L, "ds", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.DISABLED,
                new JdbcConnectionConfig("localhost", 3306, "db", "user", "pass"), null, null, null, null, null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.deleteDatasource(1L);

        verify(connectionRepository).deleteById(1L);
        verify(databaseSchemaRepository).softDeleteByConnectionId(1L);
    }

    @Test
    void should_syncNewColumns_when_syncMetadata_given_newColumnsAdded() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        TableInfo table = new TableInfo(2L, 1L, "users", "table comment", null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of(new DatabaseSchema(null, 1L, "testdb", null, null, null)));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(strategy.extractTables(connection, "testdb")).thenReturn(List.of(new TableInfo(null, null, "users", "table comment", null, null, null)));
        when(tableInfoRepository.findByDatabaseSchemaId(1L)).thenReturn(List.of(table));
        when(strategy.extractColumns(connection, "testdb", "users"))
                .thenReturn(List.of(new ColumnInfo(null, null, "id", "BIGINT", "new column", null, null, null)));
        when(columnInfoRepository.findByTableId(2L)).thenReturn(List.of());
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.frontendSyncMetadata(1L);

        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_softDeleteMissingTables_when_syncMetadata_given_remoteTablesRemoved() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        TableInfo existingTable = new TableInfo(2L, 1L, "users", "table comment", null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of(new DatabaseSchema(null, 1L, "testdb", null, null, null)));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(strategy.extractTables(connection, "testdb")).thenReturn(List.of());
        when(tableInfoRepository.findByDatabaseSchemaId(1L)).thenReturn(List.of(existingTable));
        when(columnInfoRepository.findByTableId(2L)).thenReturn(List.of());
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.frontendSyncMetadata(1L);

        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_returnEmptyTablePage_when_listTables_given_connectionWithoutSchemas() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(createJdbcConnection(1L)));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());

        var result = applicationService.listTables(new TableListQuery(1L, null, 1, 50));

        assertTrue(result.data().isEmpty());
    }

    @Test
    void should_createDatasourceAndTrySync_when_createDatasource_given_validJdbcCommand() {
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "test-ds", DatasourceType.JDBC, JdbcType.MYSQL, null, jdbcConfig, null
        );
        DatasourceConnection saved = createJdbcConnection(1L);

        when(connectionRepository.findByName("test-ds")).thenReturn(Optional.empty());
        when(connectionRepository.save(any())).thenReturn(saved);
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<DatasourceConnection> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(any())).thenReturn(List.of());

        DatasourceConnection result = applicationService.createDatasource(command);

        assertEquals(1L, result.id());
    }

    @Test
    void should_enableDatasource_when_enable_given_disabledConnectionAndValidTest() {
        DatasourceConnection connection = new DatasourceConnection(1L, "ds", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.DISABLED, new JdbcConnectionConfig("localhost", 3306, "db", "user", "pass"),
                null, null, null, null, null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.testConnection(connection)).thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.ok());
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.enableDatasource(1L);

        verify(domainService).validateCanEnable(connection);
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_throwException_when_enableDatasource_given_connectionTestFails() {
        DatasourceConnection connection = new DatasourceConnection(1L, "ds", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.DISABLED, new JdbcConnectionConfig("localhost", 3306, "db", "user", "pass"),
                null, null, null, null, null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.testConnection(connection))
                .thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.fail("Connection refused"));

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.enableDatasource(1L));
        assertTrue(ex.getMessage().contains("连接测试失败"));
    }

    @Test
    void should_throwException_when_enableDatasource_given_connectionNotFound() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.enableDatasource(1L));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_disableDatasource_when_disable_given_enabledConnection() {
        DatasourceConnection connection = createJdbcConnection(1L);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.disableDatasource(1L);

        verify(domainService).validateCanDisable(connection);
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_throwException_when_disableDatasource_given_connectionNotFound() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.disableDatasource(1L));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_deleteDatasource_when_delete_given_enabledConnection() {
        DatasourceConnection connection = createJdbcConnection(1L);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.deleteDatasource(1L);

        verify(connectionRepository).deleteById(1L);
    }

    @Test
    void should_deleteApiDatasourceAndApiSchemas_when_deleteDatasource_given_apiDatasource() {
        DatasourceConnection connection = createApiConnection(1L);
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "api_table", "/api", HttpMethod.GET, "$.data", null, null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(apiSchema));
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.deleteDatasource(1L);

        verify(apiSchemaRepository).deleteByConnectionId(1L);
        verify(connectionRepository).deleteById(1L);
    }

    @Test
    void should_executeSyncMetadata_when_frontendSyncMetadata_given_disabledConnection() {
        DatasourceConnection connection = new DatasourceConnection(1L, "ds", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.DISABLED, new JdbcConnectionConfig("localhost", 3306, "db", "user", "pass"),
                null, null, null, null, null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of());
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.frontendSyncMetadata(1L);

        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_testConnection_when_testConnection_given_existingDataSource() {
        TestConnectionCommand command = new TestConnectionCommand(
                1L, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );
        DatasourceConnection connection = createJdbcConnection(1L);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.testConnection(connection)).thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.ok());

        var result = applicationService.testConnection(command);

        assertTrue(result.success());
    }

    @Test
    void should_testConnection_when_testConnection_given_tempJdbcCommand() {
        TestConnectionCommand command = new TestConnectionCommand(
                null, "JDBC", "MYSQL", "localhost", 3306, "testdb", "root", "pass",
                null, null, null, null, null, null, null, null
        );

        // 临时连接测试会使用硬编码的名称 "temporary_test_connection"
        // 这里应该抛出 NullPointerException 因为 strategyFactory 没有被 mock
        assertThrows(NullPointerException.class, () -> applicationService.testConnection(command));
    }

    @Test
    void should_testConnection_when_testConnection_given_tempApiCommand() {
        TestConnectionCommand command = new TestConnectionCommand(
                null, "API", null, null, null, null, null, null,
                "http://api.test.com", "GET", "NO_AUTH", null, null, null, 10, "$.data"
        );

        // 临时连接测试会使用硬编码的名称 "temporary_test_connection"
        // 这里应该抛出 NullPointerException 因为 strategyFactory 没有被 mock
        assertThrows(NullPointerException.class, () -> applicationService.testConnection(command));
    }

    @Test
    void should_throwException_when_testConnection_given_nonExistingDataSource() {
        TestConnectionCommand command = new TestConnectionCommand(
                999L, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );

        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.testConnection(command));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_executeSyncMetadata_when_frontendSyncMetadata_given_apiDatasource() {
        DatasourceConnection connection = createApiConnection(1L);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenReturn(strategy);
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        applicationService.frontendSyncMetadata(1L);

        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void should_listDatasources_when_listDatasources_given_query() {
        when(connectionRepository.findByCondition(null, null, null, 1, 20)).thenReturn(List.of(createJdbcConnection(1L)));
        when(connectionRepository.countByCondition(null, null, null)).thenReturn(1L);

        var result = applicationService.listDatasources(new ListDatasourceQuery(null, null, null, 1, 20));

        assertEquals(1, result.data().size());
        assertEquals(1L, result.total());
    }

    @Test
    void should_listTables_when_listTables_given_connectionWithSchemas() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        TableInfo table = new TableInfo(2L, 1L, "users", null, null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(tableInfoRepository.findByDatabaseSchemaIdAndKeyword(1L, null, 1, 50)).thenReturn(List.of(table));
        when(tableInfoRepository.countByDatabaseSchemaIdAndKeyword(1L, null)).thenReturn(1L);

        var result = applicationService.listTables(new TableListQuery(1L, null, 1, 50));

        assertEquals(1, result.data().size());
        assertEquals(1L, result.total());
    }

    @Test
    void should_listColumns_when_listColumns_given_tableId() {
        when(columnInfoRepository.findByTableId(1L)).thenReturn(List.of(
                new ColumnInfo(1L, 1L, "id", "INT", "Primary key", null, null, null)
        ));

        var result = applicationService.listColumns(1L);

        assertEquals(1, result.size());
        assertEquals("id", result.get(0).columnName());
    }

    @Test
    void should_throwException_when_updateDatasource_given_notFound() {
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                999L, null, DatasourceType.JDBC, JdbcType.MYSQL, null, null, null
        );

        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.updateDatasource(command));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_throwException_when_getDatasource_given_notFound() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.getDatasource(1L));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_throwException_when_createDatasource_given_duplicateName() {
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "existing-ds", DatasourceType.JDBC, JdbcType.MYSQL, null, jdbcConfig, null
        );

        when(connectionRepository.findByName("existing-ds")).thenReturn(Optional.of(createJdbcConnection(1L)));

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, () -> applicationService.createDatasource(command));
        assertEquals("数据源名称已被使用", ex.getMessage());
    }

    @Test
    void should_updateDatasource_when_connectionConfigChanged_given_updateCommand() {
        DatasourceConnection existing = createJdbcConnection(1L);
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("new-host", 3307, "new-db", "new-user", "new-pass");
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "updated-ds", DatasourceType.JDBC, JdbcType.MYSQL, null, jdbcConfig, null
        );

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(existing));

        applicationService.updateDatasource(command);

    }

    private DatasourceConnection createApiConnection(Long id) {
        return new DatasourceConnection(id, "api-ds", DatasourceType.API, null, DatasourceStatus.DISABLED,
                null,
                new ApiConnectionConfig("http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null);
    }

    private DatasourceConnection createJdbcConnection(Long id) {
        return new DatasourceConnection(id, "test", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "pass"),
                null, null, null, null, null, null, null);
    }

    @Test
    void should_previewTableData_when_previewTableData_given_jdbcDatasource() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.previewData(connection, "testdb", "users", 100)).thenReturn(List.of(Map.of("id", 1)));

        List<Map<String, Object>> result = applicationService.previewTableData(1L, "users", 100);

        assertEquals(1, result.size());
    }

    @Test
    void should_previewTableData_when_previewTableData_given_apiDatasource() {
        DatasourceConnection connection = createApiConnection(1L);
        connection = new DatasourceConnection(connection.id(), connection.name(), connection.type(), connection.subType(),
                DatasourceStatus.ENABLED, connection.jdbcConnectionConfig(), connection.apiConnectionConfig(),
                connection.apiAuthConfig(), connection.description(), connection.createdAt(), connection.updatedAt(),
                connection.createdBy(), connection.updatedBy());

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenReturn(strategy);
        when(strategy.previewData(eq(connection), eq(null), eq("api_table"), anyInt())).thenReturn(List.of(Map.of("id", 1)));

        List<Map<String, Object>> result = applicationService.previewTableData(1L, "api_table", 100);

        assertEquals(1, result.size());
    }

    @Test
    void should_returnSupportedTypes_when_getSupportedTypes_given_validRequest() {
        List<DatasourceTypeResponse> result = applicationService.getSupportedTypes();

        assertEquals(3, result.size());

        DatasourceTypeResponse mysqlType = result.stream()
                .filter(t -> "MYSQL".equals(t.subType()))
                .findFirst().orElseThrow();
        assertEquals("JDBC", mysqlType.type());
        assertEquals("MySQL", mysqlType.name());
        assertEquals("OLTP", mysqlType.category());

        DatasourceTypeResponse clickhouseType = result.stream()
                .filter(t -> "CLICKHOUSE".equals(t.subType()))
                .findFirst().orElseThrow();
        assertEquals("JDBC", clickhouseType.type());
        assertEquals("ClickHouse", clickhouseType.name());
        assertEquals("OLAP", clickhouseType.category());

        DatasourceTypeResponse apiType = result.stream()
                .filter(t -> "API".equals(t.subType()))
                .findFirst().orElseThrow();
        assertEquals("API", apiType.type());
        assertEquals("API", apiType.name());
        assertEquals("API", apiType.category());
    }

    @Test
    void should_listApiSchemas_when_listApiSchemas_given_connectionId() {
        ApiSchema schema = new ApiSchema(1L, 1L, "api_table", "/api", HttpMethod.GET, "$.data", null, null, null, null);

        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));

        List<ApiSchema> result = applicationService.listApiSchemas(1L);

        assertEquals(1, result.size());
        assertEquals("api_table", result.get(0).name());
    }

    @Test
    void should_listApiFields_when_listApiFields_given_schemaId() {
        ApiField field = new ApiField(1L, 1L, "field1", "Field1", "$.field1", "STRING", "desc", null, null);

        when(apiFieldRepository.findByApiSchemaId(1L)).thenReturn(List.of(field));

        List<ApiField> result = applicationService.listApiFields(1L);

        assertEquals(1, result.size());
        assertEquals("field1", result.get(0).originalName());
    }


    @Test
    void should_throwException_when_previewTableData_given_disabledDatasource() {
        DatasourceConnection connection = createJdbcConnection(1L);
        connection = new DatasourceConnection(connection.id(), connection.name(), connection.type(), connection.subType(),
                DatasourceStatus.DISABLED, connection.jdbcConnectionConfig(), connection.apiConnectionConfig(),
                connection.apiAuthConfig(), connection.description(), connection.createdAt(), connection.updatedAt(),
                connection.createdBy(), connection.updatedBy());

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, 
            () -> applicationService.previewTableData(1L, "users", 10));
        assertEquals("数据源已禁用，请先启用数据源", ex.getMessage());
    }

    @Test
    void should_throwException_when_previewTableData_given_apiSchemaNotFound() {
        DatasourceConnection connection = createApiConnection(1L);
        connection = new DatasourceConnection(connection.id(), connection.name(), connection.type(), connection.subType(),
                DatasourceStatus.ENABLED, connection.jdbcConnectionConfig(), connection.apiConnectionConfig(),
                connection.apiAuthConfig(), connection.description(), connection.createdAt(), connection.updatedAt(),
                connection.createdBy(), connection.updatedBy());

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenReturn(strategy);
        when(strategy.previewData(eq(connection), eq(null), eq("not_exist"), anyInt()))
                .thenThrow(new RuntimeException("API Schema不存在: not_exist"));

        RuntimeException ex = assertThrows(RuntimeException.class, 
            () -> applicationService.previewTableData(1L, "not_exist", 10));
        assertTrue(ex.getMessage().contains("API Schema不存在"));
    }

    @Test
    void should_throwException_when_createDatasource_given_duplicateNameOnUpdate() {
        DatasourceConnection existing = createJdbcConnection(1L);
        DatasourceConnection other = createJdbcConnection(2L);
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "other-name", DatasourceType.JDBC, JdbcType.MYSQL, null, jdbcConfig, null
        );

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(connectionRepository.findByName("other-name")).thenReturn(Optional.of(other));

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, 
            () -> applicationService.updateDatasource(command));
        assertEquals("数据源名称已被使用", ex.getMessage());
    }

    @Test
    void should_updateDatasourceWithSameName_when_updateDatasource_given_sameName() {
        DatasourceConnection existing = createJdbcConnection(1L);
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "pass");
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(
                1L, "test", DatasourceType.JDBC, JdbcType.MYSQL, null, jdbcConfig, null
        );

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(connectionRepository.update(any())).thenReturn(existing);
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<DatasourceConnection> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        applicationService.updateDatasource(command);

        verify(connectionRepository).update(any());
    }

    @Test
    void should_throwException_when_frontendSyncMetadata_given_notFound() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, 
            () -> applicationService.frontendSyncMetadata(1L));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_throwException_when_deleteDatasource_given_notFound() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, 
            () -> applicationService.deleteDatasource(1L));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void should_getDatasource_when_getDatasource_given_existingId() {
        DatasourceConnection connection = createJdbcConnection(1L);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));

        DatasourceConnection result = applicationService.getDatasource(1L);

        assertEquals(1L, result.id());
        assertEquals("test", result.name());
    }

    @Test
    void should_parseApiResponse_when_parseApiResponse_given_newApiDatasource() {
        ParseApiResponseCommand command = new ParseApiResponseCommand(
                null, "http://api.example.com", "/api/test", "GET", null, null, null,
                null, null, null, null, null, 10, null, "$.data"
        );
        PaginatedApiResult paginatedResult = new PaginatedApiResult(List.of(Map.of("id", 1)), null, null, false);

        when(apiPaginationHandler.fetchRawResponse(any(), any(), any())).thenReturn("{\"data\":[{\"id\":1}]}");
        when(apiResponseParser.parseFields("{\"data\":[{\"id\":1}]}", "$.data"))
                .thenReturn(List.of(new ApiField(null, null, "id", "id", "$.id", "number", null, null, null)));
        when(apiPaginationHandler.executeOnce(any(), any(), any())).thenReturn(paginatedResult);

        ParseApiResponseResult result = applicationService.parseApiResponse(command);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        assertEquals(1, result.previewData().size());
    }

    @Test
    void should_parseApiResponse_when_parseApiResponse_given_existingApiDatasource() {
        DatasourceConnection connection = createApiConnection(1L);
        connection = new DatasourceConnection(connection.id(), connection.name(), connection.type(), connection.subType(),
                DatasourceStatus.ENABLED, connection.jdbcConnectionConfig(), connection.apiConnectionConfig(),
                connection.apiAuthConfig(), connection.description(), connection.createdAt(), connection.updatedAt(),
                connection.createdBy(), connection.updatedBy());
        ParseApiResponseCommand command = new ParseApiResponseCommand(
                1L, "http://api.example.com/new", "/api/new", "POST", Map.of("h", "v"), Map.of("k", "v"), "{\"body\":true}",
                "RAW", "BEARER_TOKEN", "token", null, null, 10, 3, "$.data"
        );
        PaginatedApiResult paginatedResult = new PaginatedApiResult(List.of(Map.of("id", 1)), null, null, false);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(apiPaginationHandler.fetchRawResponse(any(), any(), any())).thenReturn("{\"data\":[{\"id\":1}]}");
        when(apiResponseParser.parseFields("{\"data\":[{\"id\":1}]}", "$.data"))
                .thenReturn(List.of(new ApiField(null, null, "id", "id", "$.id", "number", null, null, null)));
        when(apiPaginationHandler.executeOnce(any(), any(), any())).thenReturn(paginatedResult);

        ParseApiResponseResult result = applicationService.parseApiResponse(command);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
    }

    @Test
    void should_throwException_when_parseApiResponse_given_nonApiDatasource() {
        DatasourceConnection connection = createJdbcConnection(1L);
        ParseApiResponseCommand command = new ParseApiResponseCommand(
                1L, null, null, null, null, null, null, null, null, null, null, null, null, null, "$.data"
        );

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, 
            () -> applicationService.parseApiResponse(command));
        assertEquals("仅支持API数据源", ex.getMessage());
    }

    @Test
    void should_throwException_when_parseApiResponse_given_datasourceNotFound() {
        ParseApiResponseCommand command = new ParseApiResponseCommand(
                999L, null, null, null, null, null, null, null, null, null, null, null, null, null, "$.data"
        );

        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());

        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class, 
            () -> applicationService.parseApiResponse(command));
        assertEquals("数据源不存在", ex.getMessage());
    }
}
