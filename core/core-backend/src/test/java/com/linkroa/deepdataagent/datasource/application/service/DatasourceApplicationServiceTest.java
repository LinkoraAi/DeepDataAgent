package com.linkroa.deepdataagent.datasource.application.service;

import com.linkroa.deepdataagent.datasource.application.assembler.DatasourceAssembler;
import com.linkroa.deepdataagent.datasource.application.command.ApiFieldCommand;
import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.controller.request.UpdateApiSchemaRequest;
import com.linkroa.deepdataagent.datasource.controller.response.*;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import com.linkroa.deepdataagent.datasource.domain.repository.*;
import com.linkroa.deepdataagent.datasource.domain.service.DatasourceConnectionDomainService;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiResponseParser;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.SchemaCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasourceApplicationServiceTest {

    @Mock private DatasourceConnectionRepository connectionRepository;
    @Mock private DatasourceConnectionStrategyFactory strategyFactory;
    @Mock private DatasourceConnectionDomainService domainService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private DatabaseSchemaRepository databaseSchemaRepository;
    @Mock private TableInfoRepository tableInfoRepository;
    @Mock private ColumnInfoRepository columnInfoRepository;
    @Mock private ApiSchemaRepository apiSchemaRepository;
    @Mock private ApiFieldRepository apiFieldRepository;
    @Mock private ApiResponseParser apiResponseParser;
    @Mock private ApiPaginationHandler apiPaginationHandler;
    @Mock private DatasourceConnectionStrategy strategy;
    @Mock private SchemaCachePort schemaCachePort;

    private DatasourceApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DatasourceApplicationService(
                connectionRepository, strategyFactory, domainService,
                transactionTemplate, databaseSchemaRepository, tableInfoRepository,
                columnInfoRepository, apiSchemaRepository, apiFieldRepository,
                apiResponseParser, apiPaginationHandler, schemaCachePort
        );
    }

    @Test
    void should_throwException_when_createDatasource_given_duplicateName() {
        when(connectionRepository.findByName("existing")).thenReturn(Optional.of(createApiConnection(1L)));
        CreateDatasourceCommand command = new CreateDatasourceCommand("existing", DatasourceType.API, null, null, null, null);
        assertThrows(DeepDataAgentException.class, () -> service.createDatasource(command));
    }

    @Test
    void should_throwException_when_updateDatasource_given_notFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(999L, "name", null, null);
        assertThrows(DeepDataAgentException.class, () -> service.updateDatasource(command));
    }

    @Test
    void should_throwException_when_enableDatasource_given_notFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.enableDatasource(999L));
    }

    @Test
    void should_throwException_when_disableDatasource_given_notFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.disableDatasource(999L));
    }

    @Test
    void should_throwException_when_deleteDatasource_given_notFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.deleteDatasource(999L));
    }

    @Test
    void should_throwException_when_getDatasource_given_notFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.getDatasource(999L));
    }

    @Test
    void should_returnConnection_when_getDatasource_given_found() {
        DatasourceConnection connection = createApiConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        DatasourceConnection result = service.getDatasource(1L);
        assertEquals(1L, result.id());
        assertEquals("api-test", result.name());
    }

    @Test
    void should_throwException_when_testConnection_given_notFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        TestConnectionCommand command = new TestConnectionCommand(999L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DeepDataAgentException.class, () -> service.testConnection(command));
    }

    @Test
    void should_returnPaginatedResult_when_listDatasources_given_validQuery() {
        DatasourceConnection connection = createApiConnection(1L);
        when(connectionRepository.findByCondition(null, null, null, 1, 20)).thenReturn(List.of(connection));
        when(connectionRepository.countByCondition(null, null, null)).thenReturn(1L);
        var query = new com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery(null, null, null, 1, 20);
        var result = service.listDatasources(query);
        assertEquals(1, result.data().size());
        assertEquals(1L, result.total());
    }

    @Test
    void should_throwException_when_enableDatasource_given_testFail() {
        DatasourceConnection connection = createApiConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanEnable(connection);
        DatasourceConnectionStrategy strategy = mock(DatasourceConnectionStrategy.class);
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenReturn(strategy);
        when(strategy.testConnection(connection)).thenReturn(
                DatasourceConnectionStrategy.ConnectionTestResult.fail("Connection refused"));
        assertThrows(DeepDataAgentException.class, () -> service.enableDatasource(1L));
    }

    @Test
    void should_disableConnection_when_disableDatasource_given_validId() {
        DatasourceConnection connection = createApiConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanDisable(connection);
        doAnswer(invocation -> {
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        service.disableDatasource(1L);
        verify(connectionRepository).updateStatus(1L, DatasourceStatus.DISABLED);
    }

    @Test
    void should_cascadeDeleteApi_when_deleteDatasource_given_apiConnection() {
        DatasourceConnection connection = createApiConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanDelete(connection);
        doAnswer(invocation -> {
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());
        service.deleteDatasource(1L);
        verify(apiSchemaRepository).deleteByConnectionId(1L);
        verify(connectionRepository).deleteById(1L);
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_cascadeDeleteJdbc_when_deleteDatasource_given_jdbcConnection() {
        DatasourceConnection connection = new DatasourceConnection(1L, "jdbc-test", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, new JdbcConnectionConfig("h", 3306, "db", "u", "p", null), null, null, null, null, null);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanDelete(connection);
        doAnswer(invocation -> {
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());
        service.deleteDatasource(1L);
        verify(connectionRepository).deleteById(1L);
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_testApiConnection_when_testConnection_given_apiSchema() {
        TestConnectionCommand command = new TestConnectionCommand(
                null, "API", null, null, null, null, null, null, null,
                "http://example.com/api", "GET", null, null, null, null, null, null, null, 30, null);
        DatasourceConnectionStrategy.ConnectionTestResult expectedResult =
                DatasourceConnectionStrategy.ConnectionTestResult.ok();
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenAnswer(inv -> {
            com.linkroa.deepdataagent.datasource.infrastructure.client.ApiConnectionStrategy mock =
                    mock(com.linkroa.deepdataagent.datasource.infrastructure.client.ApiConnectionStrategy.class);
            when(mock.testConnection(any(ApiSchema.class))).thenReturn(expectedResult);
            return mock;
        });
        DatasourceConnectionStrategy.ConnectionTestResult result = service.testConnection(command);
        assertTrue(result.success());
    }

    @Test
    void should_createApiDatasource_when_createDatasource_given_apiCommand() {
        CreateDatasourceCommand command = new CreateDatasourceCommand("new-api", DatasourceType.API, null, null, null, null);
        DatasourceConnection savedConnection = new DatasourceConnection(1L, "new-api", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null);
        when(connectionRepository.findByName("new-api")).thenReturn(Optional.empty());
        when(connectionRepository.save(any())).thenReturn(savedConnection);
        doAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<DatasourceConnection>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        DatasourceConnection result = service.createDatasource(command);

        assertEquals("new-api", result.name());
        verify(connectionRepository).save(any());
    }

    @Test
    void should_createJdbcDatasource_when_createDatasource_given_jdbcCommand() {
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "new-jdbc", DatasourceType.JDBC, JdbcType.MYSQL, null,
                new JdbcConfigCommand("localhost", 3306, "db", "u", "p", null), null);
        DatasourceConnection savedConnection = new DatasourceConnection(1L, "new-jdbc", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, new JdbcConnectionConfig("localhost", 3306, "db", "u", "p", null),
                null, null, null, null, null);
        when(connectionRepository.findByName("new-jdbc")).thenReturn(Optional.empty());
        when(connectionRepository.save(any())).thenReturn(savedConnection);
        doAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<DatasourceConnection>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(any())).thenReturn(List.of());
        when(databaseSchemaRepository.findByConnectionId(any())).thenReturn(List.of());

        DatasourceConnection result = service.createDatasource(command);

        assertEquals(DatasourceType.JDBC, result.type());
    }

    @Test
    void should_throwException_when_createDatasource_given_invalidPgSchema() {
        // given
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "pg-ds", DatasourceType.JDBC, JdbcType.POSTGRESQL, null,
                new JdbcConfigCommand("localhost", 5432, "db", "u", "p", "public,analytics"), null);
        when(connectionRepository.findByName("pg-ds")).thenReturn(Optional.empty());

        // when / then
        assertThrows(DeepDataAgentException.class, () -> service.createDatasource(command));
    }

    @Test
    void should_createApiDatasourceWithSchemas_when_createDatasource_given_apiCommandWithSchemas() {
        var schemaCommand = new ApiSchemaCommand(
                "test-api", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        CreateDatasourceCommand command = new CreateDatasourceCommand(
                "new-api", DatasourceType.API, null, null, null, List.of(schemaCommand));
        DatasourceConnection savedConnection = new DatasourceConnection(1L, "new-api", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null);
        when(connectionRepository.findByName("new-api")).thenReturn(Optional.empty());
        when(connectionRepository.save(any())).thenReturn(savedConnection);
        doAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<DatasourceConnection>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        when(apiSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatasourceConnection result = service.createDatasource(command);

        assertEquals("new-api", result.name());
        verify(apiSchemaRepository).save(any());
    }

    @Test
    void should_updateConnection_when_updateDatasource_given_validCommand() {
        DatasourceConnection existing = createApiConnection(1L);
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(1L, "updated-name", "updated-desc", null);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(connectionRepository.findByName("updated-name")).thenReturn(Optional.empty());
        when(connectionRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<DatasourceConnection>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        DatasourceConnection result = service.updateDatasource(command);

        verify(connectionRepository).update(any());
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_resyncMetadata_when_updateDatasource_given_jdbcConnection() {
        // given JDBC 数据源更新后应重新同步元数据
        DatasourceConnection existing = createJdbcConnection(1L);
        JdbcConfigCommand jdbcConfig = new JdbcConfigCommand("localhost", 3306, "testdb", "root", "password", null);
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(1L, "updated-name", "updated-desc", jdbcConfig);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(connectionRepository.findByName("updated-name")).thenReturn(Optional.empty());
        when(connectionRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<DatasourceConnection>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        // when
        service.updateDatasource(command);

        // then 更新后触发了元数据同步
        verify(strategy).extractSchemas(any());
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_throwException_when_updateDatasource_given_duplicateName() {
        DatasourceConnection existing = createApiConnection(1L);
        DatasourceConnection other = createApiConnection(2L);
        UpdateDatasourceCommand command = new UpdateDatasourceCommand(1L, "duplicate-name", null, null);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(connectionRepository.findByName("duplicate-name")).thenReturn(Optional.of(other));
        assertThrows(DeepDataAgentException.class, () -> service.updateDatasource(command));
    }

    @Test
    void should_enableConnection_when_enableDatasource_given_testSuccess() {
        DatasourceConnection connection = createApiConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanEnable(connection);
        DatasourceConnectionStrategy strategy = mock(DatasourceConnectionStrategy.class);
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenReturn(strategy);
        when(strategy.testConnection(connection)).thenReturn(
                DatasourceConnectionStrategy.ConnectionTestResult.ok());
        doAnswer(invocation -> {
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.enableDatasource(1L);

        verify(connectionRepository).updateStatus(1L, DatasourceStatus.ENABLED);
    }

    @Test
    void should_testJdbcConnection_when_testConnection_given_existingConnection() {
        DatasourceConnection connection = new DatasourceConnection(1L, "jdbc-test", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, new JdbcConnectionConfig("h", 3306, "db", "u", "p", null), null, null, null, null, null);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        DatasourceConnectionStrategy strategy = mock(DatasourceConnectionStrategy.class);
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.testConnection(connection)).thenReturn(
                DatasourceConnectionStrategy.ConnectionTestResult.ok());

        TestConnectionCommand command = new TestConnectionCommand(1L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        DatasourceConnectionStrategy.ConnectionTestResult result = service.testConnection(command);

        assertTrue(result.success());
    }

    @Test
    void should_testJdbcConnection_when_testConnection_given_newConnection() {
        TestConnectionCommand command = new TestConnectionCommand(
                null, "JDBC", "MYSQL", "localhost", 3306, "testdb", "root", "pass", null,
                null, null, null, null, null, null, null, null, null, null, null);
        DatasourceConnectionStrategy strategy = mock(DatasourceConnectionStrategy.class);
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.testConnection(any())).thenReturn(
                DatasourceConnectionStrategy.ConnectionTestResult.ok());

        DatasourceConnectionStrategy.ConnectionTestResult result = service.testConnection(command);

        assertTrue(result.success());
        verify(strategy).testConnection(any());
    }

    @Test
    void should_syncMetadata_when_connectionExists() {
        DatasourceConnection connection = createJdbcConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanSync(connection);
        doAnswer(invocation -> {
            var callback = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of());
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());

        service.syncMetadata(1L);

        verify(transactionTemplate).executeWithoutResult(any());
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_throwException_when_syncMetadata_given_connectionNotFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.syncMetadata(999L));
    }

    @Test
    void should_returnPaginatedTables_when_listTables_given_validQuery() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        TableInfo table = new TableInfo(1L, 1L, "users", "User table", null, null, null);
        TableListQuery query = new TableListQuery(1L, null, 1, 10);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(tableInfoRepository.findByDatabaseSchemaIdAndKeyword(1L, null, 1, 10)).thenReturn(List.of(table));
        when(tableInfoRepository.countByDatabaseSchemaIdAndKeyword(1L, null)).thenReturn(1L);

        var result = service.listTables(query);

        assertEquals(1, result.data().size());
        assertEquals(1L, result.total());
        assertEquals("users", result.data().getFirst().tableName());
    }

    @Test
    void should_returnEmptyResult_when_listTables_given_noSchemas() {
        DatasourceConnection connection = createJdbcConnection(1L);
        TableListQuery query = new TableListQuery(1L, null, 0, 10);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());

        var result = service.listTables(query);

        assertTrue(result.data().isEmpty());
        assertEquals(0, result.total());
    }

    @Test
    void should_returnColumns_when_listColumns_given_validTableId() {
        ColumnInfo column = new ColumnInfo(1L, 1L, "id", "BIGINT", "Primary key", null, null, null);
        when(columnInfoRepository.findByTableId(1L)).thenReturn(List.of(column));

        var result = service.listColumns(1L);

        assertEquals(1, result.size());
        assertEquals("id", result.getFirst().columnName());
    }

    @Test
    void should_updateTableComment_when_validComment() {
        doNothing().when(domainService).validateComment("test comment");
        doNothing().when(tableInfoRepository).updateTableCustomComment(1L, "test comment");

        service.updateTableComment(1L, "test comment");

        verify(domainService).validateComment("test comment");
        verify(tableInfoRepository).updateTableCustomComment(1L, "test comment");
    }

    @Test
    void should_updateColumnComment_when_validComment() {
        doNothing().when(domainService).validateComment("column comment");
        doNothing().when(columnInfoRepository).updateColumnCustomComment(1L, "column comment");

        service.updateColumnComment(1L, "column comment");

        verify(domainService).validateComment("column comment");
        verify(columnInfoRepository).updateColumnCustomComment(1L, "column comment");
    }

    @Test
    void should_previewTableData_when_jdbcConnection() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        List<Map<String, Object>> expectedData = List.of(Map.of("id", 1, "name", "test"));

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(strategy.previewData(connection, "testdb", "users", 50)).thenReturn(expectedData);

        var result = service.previewTableData(1L, "users", 50);

        assertEquals(1, result.size());
        verify(strategy).previewData(connection, "testdb", "users", 50);
    }

    @Test
    void should_previewTableData_when_apiConnection() {
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, null, null, null, null, null);
        List<Map<String, Object>> expectedData = List.of(Map.of("id", 1));

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.API, null)).thenReturn(strategy);
        when(strategy.previewData(connection, null, "api_table", 100)).thenReturn(expectedData);

        var result = service.previewTableData(1L, "api_table", 100);

        assertEquals(1, result.size());
        verify(strategy).previewData(connection, null, "api_table", 100);
    }

    @Test
    void should_throwException_when_previewTableData_given_connectionNotFound() {
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.previewTableData(999L, "users", 10));
    }

    @Test
    void should_throwException_when_previewTableData_given_disabledConnection() {
        DatasourceConnection connection = new DatasourceConnection(1L, "test", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.DISABLED, new JdbcConnectionConfig("localhost", 3306, "db", "u", "p", null),
                null, null, null, null, null);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        assertThrows(DeepDataAgentException.class, () -> service.previewTableData(1L, "users", 10));
    }

    @Test
    void should_deleteRelatedMetadata_when_jdbcConnection() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema schema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        TableInfo table = new TableInfo(1L, 1L, "users", null, null, null, null);

        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));
        when(tableInfoRepository.findByDatabaseSchemaId(1L)).thenReturn(List.of(table));

        service.deleteRelatedMetadata(connection);

        verify(columnInfoRepository).softDeleteByTableId(1L);
        verify(tableInfoRepository).softDeleteByDatabaseSchemaId(1L);
        verify(databaseSchemaRepository).softDeleteByConnectionId(1L);
    }

    @Test
    void should_doNothing_when_deleteRelatedMetadata_given_apiConnection() {
        DatasourceConnection connection = new DatasourceConnection(1L, "api-test", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, null, null, null, null, null);

        service.deleteRelatedMetadata(connection);

        verifyNoInteractions(databaseSchemaRepository);
        verifyNoInteractions(tableInfoRepository);
        verifyNoInteractions(columnInfoRepository);
    }

    @Test
    void should_doSyncMetadata_when_jdbcConnection() {
        DatasourceConnection connection = createJdbcConnection(1L);
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of());
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());

        service.doSyncMetadata(connection);

        verify(strategy).extractSchemas(connection);
    }

    @Test
    void should_listApiSchemas_when_given_validConnectionId() {
        ApiSchema schema = new ApiSchema(1L, 1L, "test-api", "http://example.com", HttpMethod.GET, null, null, null, null, null);
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));

        var result = service.listApiSchemas(1L);

        assertEquals(1, result.size());
        assertEquals("test-api", result.getFirst().name());
    }

    @Test
    void should_listApiFields_when_given_validSchemaId() {
        ApiField field = new ApiField(1L, 1L, "field1", "Field1", "$.field1", "STRING", "desc", null, null);
        when(apiFieldRepository.findByApiSchemaId(1L)).thenReturn(List.of(field));

        var result = service.listApiFields(1L);

        assertEquals(1, result.size());
        assertEquals("field1", result.getFirst().originalName());
    }

    private DatasourceConnection createApiConnection(Long id) {
        return new DatasourceConnection(id, "api-test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null);
    }

    private DatasourceConnection createJdbcConnection(Long id) {
        JdbcConnectionConfig config = new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "password", null);
        return new DatasourceConnection(id, "jdbc-test", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, config, null, null, null, null, null);
    }

    @Test
    void should_returnSupportedTypes_when_getSupportedTypes() {
        var result = service.getSupportedTypes();
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(r -> r.type().equals("JDBC")));
        assertTrue(result.stream().anyMatch(r -> r.type().equals("API")));
    }

    @Test
    void should_saveApiSchema_when_given_validCommand() {
        var schemaCommand = new ApiSchemaCommand(
                "test-api", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        when(apiSchemaRepository.save(any())).thenAnswer(inv -> {
            ApiSchema s = inv.getArgument(0);
            return new ApiSchema(1L, 1L, s.name(), s.url(), s.method(), s.config(), LocalDateTime.now(), LocalDateTime.now(), null, null);
        });

        var result = service.saveApiSchema(1L, schemaCommand);

        assertNotNull(result);
        assertEquals("test-api", result.name());
        verify(apiSchemaRepository).save(any());
    }

    @Test
    void should_saveApiSchemaWithFields_when_given_commandWithFields() {
        var fieldDto = new ApiFieldCommand("id", "ID", "$.id", "number", "Primary key");
        var schemaCommand = new ApiSchemaCommand(
                "test-api", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(fieldDto));
        when(apiSchemaRepository.save(any())).thenAnswer(inv -> {
            ApiSchema s = inv.getArgument(0);
            return new ApiSchema(1L, 1L, s.name(), s.url(), s.method(), s.config(), LocalDateTime.now(), LocalDateTime.now(), null, null);
        });

        service.saveApiSchema(1L, schemaCommand);

        verify(apiFieldRepository).save(any());
    }

    @Test
    void should_saveApiSchemaWithAuthConfig_when_given_authType() {
        var schemaCommand = new ApiSchemaCommand(
                "test-api", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, ApiAuthType.BASIC_AUTH, "user", "pass", null, null, null, null, null, null, null, null);
        when(apiSchemaRepository.save(any())).thenAnswer(inv -> {
            ApiSchema s = inv.getArgument(0);
            return new ApiSchema(1L, 1L, s.name(), s.url(), s.method(), s.config(), LocalDateTime.now(), LocalDateTime.now(), null, null);
        });

        service.saveApiSchema(1L, schemaCommand);

        verify(apiSchemaRepository).save(argThat(schema -> schema.config().authConfig().authType() == ApiAuthType.BASIC_AUTH));
    }

    @Test
    void should_saveApiSchemaWithPaginationConfig_when_given_paginationType() {
        var schemaCommand = new ApiSchemaCommand(
                "test-api", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, "PAGE_BASED", "page", "size", "$.total", 20, 30, null, null);
        when(apiSchemaRepository.save(any())).thenAnswer(inv -> {
            ApiSchema s = inv.getArgument(0);
            return new ApiSchema(1L, 1L, s.name(), s.url(), s.method(), s.config(), LocalDateTime.now(), LocalDateTime.now(), null, null);
        });

        service.saveApiSchema(1L, schemaCommand);

        verify(apiSchemaRepository).save(argThat(schema -> schema.config().paginationConfig() != null));
    }

    @Test
    void should_throwException_when_saveApiSchema_given_blankName() {
        var schemaCommand = new ApiSchemaCommand(
                "", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DeepDataAgentException.class, () -> service.saveApiSchema(1L, schemaCommand));
    }

    @Test
    void should_throwException_when_saveApiSchema_given_blankUrl() {
        var schemaCommand = new ApiSchemaCommand(
                "test", HttpMethod.GET, "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DeepDataAgentException.class, () -> service.saveApiSchema(1L, schemaCommand));
    }

    @Test
    void should_throwException_when_createApiSchema_given_nonApiConnection() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(createJdbcConnection(1L)));
        var schemaCommand = new ApiSchemaCommand(
                "test", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DeepDataAgentException.class, () -> service.createApiSchema(1L, schemaCommand));
    }

    @Test
    void should_throwException_when_createApiSchema_given_connectionNotFound() {
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());
        var schemaCommand = new ApiSchemaCommand(
                "test", HttpMethod.GET, "http://example.com", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DeepDataAgentException.class, () -> service.createApiSchema(1L, schemaCommand));
    }

    @Test
    void should_deleteApiSchema_when_given_validSchemaId() {
        when(apiSchemaRepository.findById(1L)).thenReturn(Optional.of(mock(ApiSchema.class)));
        doAnswer(invocation -> {
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.deleteApiSchema(1L);

        verify(apiFieldRepository).deleteByApiSchemaId(1L);
        verify(apiSchemaRepository).deleteById(1L);
    }

    @Test
    void should_throwException_when_deleteApiSchema_given_notFound() {
        when(apiSchemaRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.deleteApiSchema(1L));
    }

    @Test
    void should_getApiSchemaDetail_when_given_validSchemaId() {
        ApiRequestConfig config = new ApiRequestConfig(null, null, null, null, null, 180, null,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, null);
        ApiSchema schema = new ApiSchema(1L, 1L, "test-api", "http://example.com", HttpMethod.GET,
                config, LocalDateTime.now(), LocalDateTime.now(), null, null);
        ApiField field = new ApiField(1L, 1L, "id", "ID", "$.id", "number", "desc", LocalDateTime.now(), LocalDateTime.now());
        when(apiSchemaRepository.findById(1L)).thenReturn(Optional.of(schema));
        when(apiFieldRepository.findByApiSchemaId(1L)).thenReturn(List.of(field));

        var result = service.getApiSchemaDetail(1L);

        assertNotNull(result);
        assertEquals("test-api", result.name());
        assertEquals(1, result.fields().size());
    }

    @Test
    void should_throwException_when_getApiSchemaDetail_given_notFound() {
        when(apiSchemaRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(DeepDataAgentException.class, () -> service.getApiSchemaDetail(1L));
    }

    @Test
    void should_syncMetadataWithNewSchema_when_strategyReturnsSchemas() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema remoteSchema = new DatabaseSchema(null, 1L, "newdb", null, null, null);
        TableInfo remoteTable = new TableInfo(null, null, "users", "User table", null, null, null);
        ColumnInfo remoteColumn = new ColumnInfo(null, null, "id", "BIGINT", "Primary key", null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanSync(connection);
        doAnswer(invocation -> {
            var callback = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of(remoteSchema));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());
        when(databaseSchemaRepository.save(any())).thenAnswer(inv -> {
            DatabaseSchema s = inv.getArgument(0);
            return new DatabaseSchema(1L, s.connectionId(), s.schemaName(), s.description(), s.createdAt(), s.updatedAt());
        });
        when(strategy.extractTables(connection, "newdb")).thenReturn(List.of(remoteTable));
        when(tableInfoRepository.findByDatabaseSchemaId(1L)).thenReturn(List.of());
        when(tableInfoRepository.save(any())).thenAnswer(inv -> {
            TableInfo t = inv.getArgument(0);
            return new TableInfo(1L, t.databaseSchemaId(), t.tableName(), t.tableComment(), t.tableCustomComment(), t.createdAt(), t.updatedAt());
        });
        when(strategy.extractColumns(connection, "newdb", "users")).thenReturn(List.of(remoteColumn));

        service.syncMetadata(1L);

        verify(databaseSchemaRepository).save(any());
        verify(tableInfoRepository).save(any());
        verify(columnInfoRepository).save(any());
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_syncMetadataWithExistingSchema_when_schemaAlreadyExists() {
        DatasourceConnection connection = createJdbcConnection(1L);
        DatabaseSchema existingSchema = new DatabaseSchema(1L, 1L, "testdb", null, null, null);
        DatabaseSchema remoteSchema = new DatabaseSchema(null, 1L, "testdb", null, null, null);
        TableInfo remoteTable = new TableInfo(null, null, "users", "User table", null, null, null);

        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        doNothing().when(domainService).validateCanSync(connection);
        doAnswer(invocation -> {
            var callback = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(strategy.extractSchemas(connection)).thenReturn(List.of(remoteSchema));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(existingSchema));
        when(strategy.extractTables(connection, "testdb")).thenReturn(List.of(remoteTable));
        when(tableInfoRepository.findByDatabaseSchemaId(1L)).thenReturn(List.of());
        when(tableInfoRepository.save(any())).thenAnswer(inv -> {
            TableInfo t = inv.getArgument(0);
            return new TableInfo(1L, t.databaseSchemaId(), t.tableName(), t.tableComment(), t.tableCustomComment(), t.createdAt(), t.updatedAt());
        });
        when(strategy.extractColumns(connection, "testdb", "users")).thenReturn(List.of());

        service.syncMetadata(1L);

        verify(tableInfoRepository).save(any());
        verify(databaseSchemaRepository, never()).save(any());
        verify(schemaCachePort).evict(1L);
    }

    @Test
    void should_previewTableDataWithNoSchema_when_jdbcConnectionAndNoSchemas() {
        DatasourceConnection connection = createJdbcConnection(1L);
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(connection));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL)).thenReturn(strategy);
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());
        when(strategy.previewData(connection, null, "users", 50)).thenReturn(List.of());

        service.previewTableData(1L, "users", 50);

        verify(strategy).previewData(connection, null, "users", 50);
    }

    @Test
    void should_deleteApiSchemasByConnectionId_when_given_validConnectionId() {
        ApiSchema schema = new ApiSchema(1L, 1L, "test", "http://example.com", HttpMethod.GET, null, null, null, null, null);
        when(apiSchemaRepository.findByConnectionId(1L)).thenReturn(List.of(schema));

        service.deleteApiSchemasByConnectionId(1L);

        verify(apiFieldRepository).deleteByApiSchemaId(1L);
        verify(apiSchemaRepository).deleteByConnectionId(1L);
    }
}
