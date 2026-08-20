package com.linkroa.deepdataagent.datasource.infrastructure.adapter;

import com.linkroa.deepdataagent.datasource.application.contract.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.application.contract.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.application.port.DatasourceQueryPort;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据源查询出站端口实现（{@link DatasourceQueryPort}）。
 * <p>委托 datasource 领域仓储 / 连接策略完成「引用解析 / 表清单 / 表数据预览」，
 * 对外仅返回发布语言 DTO；凭证解密与连接细节不发散到 runtime BC。</p>
 */
@Service
public class DefaultDatasourceQueryPort implements DatasourceQueryPort {

    @Resource
    private DatasourceConnectionRepository connectionRepository;
    @Resource
    private DatasourceConnectionStrategyFactory strategyFactory;
    @Resource
    private DatabaseSchemaRepository databaseSchemaRepository;
    @Resource
    private TableInfoRepository tableInfoRepository;
    @Resource
    private ApiSchemaRepository apiSchemaRepository;

    @Override
    public List<DatasourceReferenceDTO> listDatasources(List<Long> dataSourceIds) {
        if (dataSourceIds == null || dataSourceIds.isEmpty()) {
            return List.of();
        }
        List<DatasourceReferenceDTO> result = new ArrayList<>();
        for (Long id : dataSourceIds) {
            connectionRepository.findById(id).ifPresent(connection ->
                    result.add(new DatasourceReferenceDTO(connection.id(), connection.name(), formatType(connection))));
        }
        return result;
    }

    @Override
    public List<String> listTableNames(Long dataSourceId) {
        DatasourceConnection connection = requireConnection(dataSourceId);
        if (connection.type() == DatasourceType.API) {
            return apiSchemaRepository.findByConnectionId(connection.id()).stream()
                    .map(ApiSchema::name)
                    .toList();
        }
        List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connection.id());
        if (schemas.isEmpty()) {
            return List.of();
        }
        // 单活动 schema 假设：JDBC 连接仅维护一个默认 schema（currentSchema），只列出首个 schema 下的表
        return tableInfoRepository.findByDatabaseSchemaId(schemas.getFirst().id()).stream()
                .map(TableInfo::tableName)
                .toList();
    }

    @Override
    public DatasourcePreviewDTO previewTable(Long dataSourceId, String tableName, int limit) {
        DatasourceConnection connection = requireConnection(dataSourceId);
        if (connection.status() != DatasourceStatus.ENABLED) {
            throw new DeepDataAgentException("数据源已禁用，请先启用数据源");
        }
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);

        List<Map<String, Object>> rows;
        if (connection.type() == DatasourceType.API) {
            rows = strategy.previewData(connection, null, tableName, effectiveLimit);
        } else {
            List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connection.id());
            // 单活动 schema 假设：与 listTableNames 一致，仅取首个 schema 作为查询 schema
            String schemaName = schemas.isEmpty() ? null : schemas.getFirst().schemaName();
            rows = strategy.previewData(connection, schemaName, tableName, effectiveLimit);
        }
        return new DatasourcePreviewDTO(connection.name(), tableName, deriveColumns(rows), rows);
    }

    private DatasourceConnection requireConnection(Long id) {
        return connectionRepository.findById(id)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
    }

    private static String formatType(DatasourceConnection connection) {
        if (connection.subType() == null) {
            return connection.type().name();
        }
        return connection.type().name() + ":" + connection.subType().name();
    }

    private static List<String> deriveColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.getFirst().keySet());
    }
}