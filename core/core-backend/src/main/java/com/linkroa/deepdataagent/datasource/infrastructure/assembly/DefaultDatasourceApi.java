package com.linkroa.deepdataagent.datasource.infrastructure.assembly;

import com.linkroa.deepdataagent.datasource.api.DatasourceApi;
import com.linkroa.deepdataagent.datasource.api.dto.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.api.dto.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.api.dto.TableSchemaDTO;
import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link DatasourceApi} 进程内实现（datasource BC 能力出口，assembly 装配点）。
 *
 * <p>本类是 BC 边界的<b>中性返回收敛点</b>：把"数据源不存在"（{@link ResourceNotFoundException}）、
 * "数据源未启用"（{@link ResourceConflictException}）两类语义异常统一收敛为
 * {@link Optional#empty()}，使上游无法经返回值区分三者，避免泄露存在性；
 * <b>远端取数失败等真实运行期故障不收敛</b>，照常向上抛出。</p>
 *
 * <p><b>门禁口径同源</b>：表结构描述与表数据预览均要求数据源处于 {@link DatasourceStatus#ENABLED}
 * （与数据源管理面 REST 侧的读侧门禁一致）；预览取数委托 {@code DatasourceApplicationService}，
 * 行数钳制（1~100）与应用层同一实现，本面不做二次放宽。</p>
 *
 * <p>本实现<b>只读</b>：仅调用查询类仓储方法与只读应用服务方法，不触碰任何写路径。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class DefaultDatasourceApi implements DatasourceApi {

    /** 数据源连接仓储（引用解析与存在性/状态门禁）。 */
    @Resource
    private DatasourceConnectionRepository connectionRepository;

    /** 数据库 schema 仓储（JDBC 型表结构定位）。 */
    @Resource
    private DatabaseSchemaRepository databaseSchemaRepository;

    /** 表信息仓储（JDBC 型按表名定位表）。 */
    @Resource
    private TableInfoRepository tableInfoRepository;

    /** 字段信息仓储（JDBC 型表结构的字段清单）。 */
    @Resource
    private ColumnInfoRepository columnInfoRepository;

    /** API 数据表仓储（API 型表结构定位）。 */
    @Resource
    private ApiSchemaRepository apiSchemaRepository;

    /** API 字段仓储（API 型表结构的字段映射）。 */
    @Resource
    private ApiFieldRepository apiFieldRepository;

    /** 数据源应用服务（预览取数委托；承载状态门禁与行数钳制）。 */
    @Resource
    private DatasourceApplicationService applicationService;

    @Override
    public List<DatasourceReferenceDTO> listDatasources(List<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return List.of();
        }
        List<DatasourceReferenceDTO> references = new ArrayList<>();
        for (Long datasourceId : datasourceIds) {
            if (datasourceId == null) {
                continue;
            }
            // 缺失标识静默跳过；保持入参相对顺序；不做状态过滤（禁用态仍返回引用）
            connectionRepository.findById(datasourceId).ifPresent(
                    connection -> references.add(toReference(connection)));
        }
        return references;
    }

    @Override
    public Optional<TableSchemaDTO> describeTable(Long datasourceId, String tableName) {
        if (datasourceId == null || StringUtils.isBlank(tableName)) {
            return Optional.empty();
        }
        Optional<DatasourceConnection> usable = usableConnection(datasourceId);
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        DatasourceConnection connection = usable.get();
        return connection.type() == DatasourceType.API
                ? describeApiTable(connection, tableName)
                : describeJdbcTable(connection, tableName);
    }

    @Override
    public Optional<DatasourcePreviewDTO> previewTable(Long datasourceId, String tableName, int limit) {
        if (datasourceId == null || StringUtils.isBlank(tableName)) {
            return Optional.empty();
        }
        try {
            // 委托应用层：与 REST 读侧同一门禁（ENABLED）与同一行数钳制（1~100）
            List<Map<String, Object>> rows = applicationService.previewTableData(datasourceId, tableName, limit);
            return Optional.of(new DatasourcePreviewDTO(tableName, deriveColumns(rows), rows));
        } catch (ResourceNotFoundException | ResourceConflictException e) {
            // 不存在 / 未启用 → 中性返回（不泄露存在性）；远端故障等运行期异常不在此列，照常上抛
            return Optional.empty();
        }
    }

    /**
     * 按 datasourceId 查找并施加「存在 + 已启用」门禁（任一不满足返回空，不泄露存在性）。
     *
     * @param datasourceId 数据源标识
     * @return 处于已启用状态的数据源；不存在或未启用时为空
     */
    private Optional<DatasourceConnection> usableConnection(Long datasourceId) {
        return connectionRepository.findById(datasourceId)
                .filter(connection -> connection.status() == DatasourceStatus.ENABLED);
    }

    /**
     * 描述 JDBC 型数据源的表结构（本 BC 单活动 schema 口径：仅首个 schema）。
     *
     * @param connection 已确认可用的数据源连接
     * @param tableName  表名
     * @return 表结构；无 schema、表名不存在时为 {@link Optional#empty()}
     */
    private Optional<TableSchemaDTO> describeJdbcTable(DatasourceConnection connection, String tableName) {
        List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connection.id());
        if (schemas.isEmpty()) {
            return Optional.empty();
        }
        DatabaseSchema schema = schemas.getFirst();
        Optional<TableInfo> matched = tableInfoRepository.findByDatabaseSchemaId(schema.id()).stream()
                .filter(table -> tableName.equals(table.tableName()))
                .findFirst();
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        TableInfo table = matched.get();
        List<TableSchemaDTO.Column> columns = columnInfoRepository.findByTableId(table.id()).stream()
                .map(this::toColumn)
                .toList();
        return Optional.of(new TableSchemaDTO(table.tableName(), table.tableComment(), columns));
    }

    /**
     * 描述 API 型数据源的表结构（以已配置 API 数据表与字段映射为载体；API 型无表注释概念）。
     *
     * @param connection 已确认可用的数据源连接
     * @param tableName  API 数据表名
     * @return 表结构；数据表不存在时为 {@link Optional#empty()}
     */
    private Optional<TableSchemaDTO> describeApiTable(DatasourceConnection connection, String tableName) {
        Optional<ApiSchema> matched = apiSchemaRepository.findByConnectionIdAndName(connection.id(), tableName);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        ApiSchema schema = matched.get();
        List<TableSchemaDTO.Column> columns = apiFieldRepository.findByApiSchemaId(schema.id()).stream()
                .map(this::toColumn)
                .toList();
        return Optional.of(new TableSchemaDTO(schema.name(), null, columns));
    }

    /**
     * JDBC 字段 → 发布语言列（保留原始注释与自定义注释双来源，不做合并）。
     */
    private TableSchemaDTO.Column toColumn(ColumnInfo columnInfo) {
        return new TableSchemaDTO.Column(
                columnInfo.columnName(),
                columnInfo.dataType(),
                columnInfo.columnComment(),
                columnInfo.columnCustomComment());
    }

    /**
     * API 字段映射 → 发布语言列（原始名为列名、推断类型为类型、描述为注释；无自定义注释概念）。
     */
    private TableSchemaDTO.Column toColumn(ApiField apiField) {
        return new TableSchemaDTO.Column(
                apiField.originalName(),
                apiField.fieldType(),
                apiField.description(),
                null);
    }

    /**
     * 领域连接 → 发布语言引用（枚举以名称字符串传输，不跨 BC 传 Java 枚举类型）。
     */
    private DatasourceReferenceDTO toReference(DatasourceConnection connection) {
        return new DatasourceReferenceDTO(
                connection.id(),
                connection.name(),
                connection.type() == null ? null : connection.type().name(),
                connection.subType() == null ? null : connection.subType().name());
    }

    /**
     * 从预览行数据派生列名顺序（首行键序；无数据时为空列表）。
     *
     * @param rows 预览行数据
     * @return 列名列表
     */
    private static List<String> deriveColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.getFirst().keySet());
    }
}