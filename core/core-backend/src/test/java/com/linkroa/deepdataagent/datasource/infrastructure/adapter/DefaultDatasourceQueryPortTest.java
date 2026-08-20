package com.linkroa.deepdataagent.datasource.infrastructure.adapter;

import com.linkroa.deepdataagent.datasource.application.contract.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.application.contract.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.JdbcConnectionConfig;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultDatasourceQueryPort} 数据源查询出站端口单测（只读查询，mock 仓储/策略）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultDatasourceQueryPortTest {

    @Mock
    private DatasourceConnectionRepository connectionRepository;
    @Mock
    private DatasourceConnectionStrategyFactory strategyFactory;
    @Mock
    private DatabaseSchemaRepository databaseSchemaRepository;
    @Mock
    private TableInfoRepository tableInfoRepository;
    @Mock
    private ApiSchemaRepository apiSchemaRepository;
    @Mock
    private DatasourceConnectionStrategy strategy;

    @InjectMocks
    private DefaultDatasourceQueryPort port;

    @Test
    void should_returnRefs_when_listDatasources_given_existingIds() {
        // given
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(jdbcConnection(1L, "客户库")));

        // when
        List<DatasourceReferenceDTO> refs = port.listDatasources(List.of(1L));

        // then
        assertEquals(1, refs.size());
        assertEquals(1L, refs.get(0).id());
        assertEquals("客户库", refs.get(0).name());
        assertEquals("JDBC:POSTGRESQL", refs.get(0).type());
    }

    @Test
    void should_skipMissing_when_listDatasources_given_missingId() {
        // given
        when(connectionRepository.findById(1L)).thenReturn(Optional.empty());

        // when
        List<DatasourceReferenceDTO> refs = port.listDatasources(List.of(1L));

        // then
        assertTrue(refs.isEmpty());
    }

    @Test
    void should_listJdbcTableNames_when_listTableNames_given_jdbcConnection() {
        // given
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(jdbcConnection(1L, "客户库")));
        when(databaseSchemaRepository.findByConnectionId(1L))
                .thenReturn(List.of(new DatabaseSchema(1L, 1L, "public", null, null, null)));
        when(tableInfoRepository.findByDatabaseSchemaId(1L))
                .thenReturn(List.of(new TableInfo(1L, 1L, "orders", null, null, null, null)));

        // when
        List<String> tables = port.listTableNames(1L);

        // then
        assertEquals(List.of("orders"), tables);
    }

    @Test
    void should_previewJdbcTable_when_previewTable_given_enabledConnection() {
        // given
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(jdbcConnection(1L, "客户库")));
        when(strategyFactory.getStrategy(DatasourceType.JDBC, JdbcType.POSTGRESQL)).thenReturn(strategy);
        when(databaseSchemaRepository.findByConnectionId(1L))
                .thenReturn(List.of(new DatabaseSchema(1L, 1L, "public", null, null, null)));
        when(strategy.previewData(any(DatasourceConnection.class), eq("public"), eq("orders"), eq(10)))
                .thenReturn(List.of(Map.of("id", 1, "name", "张三")));

        // when
        DatasourcePreviewDTO preview = port.previewTable(1L, "orders", 10);

        // then
        assertEquals("客户库", preview.dataSourceName());
        assertEquals("orders", preview.tableName());
        assertEquals(2, preview.columns().size());
        assertEquals(1, preview.rows().size());
    }

    private DatasourceConnection jdbcConnection(Long id, String name) {
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("localhost", 5432, "db", "u", "p", "public");
        return DatasourceConnection.restore(
                id, name, DatasourceType.JDBC, JdbcType.POSTGRESQL, DatasourceStatus.ENABLED,
                jdbcConfig, null, null, null, null, null);
    }
}