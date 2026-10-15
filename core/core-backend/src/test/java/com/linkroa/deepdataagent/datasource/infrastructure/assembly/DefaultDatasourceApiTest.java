package com.linkroa.deepdataagent.datasource.infrastructure.assembly;

import com.linkroa.deepdataagent.datasource.api.DatasourceApi;
import com.linkroa.deepdataagent.datasource.api.dto.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.api.dto.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.api.dto.TableSchemaDTO;
import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.model.ApiRequestConfig;
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
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultDatasourceApi} 单元测试（数据源 BC 能力出口）。
 *
 * <p>覆盖：引用解析（缺失跳过/保序/空入参/禁用态仍可见/零副作用）、表结构描述
 * （JDBC 与 API 两分支、双来源注释、三类不可用情形中性返回）、表数据预览
 * （委托与透传、两类语义异常收敛为 empty、<b>远端故障异常照常上抛</b>）、
 * 以及契约面纯净性（方法集恰为三个只读方法、签名不泄漏 domain 类型）。</p>
 *
 * <p>注：预览行数钳制（1~100）由被委托的 {@code DatasourceApplicationService} 执行
 * （与本能力面同一实现，避免两处常量漂移）；本层断言的是"请求值原样透传、不被本面二次放宽"。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultDatasourceApiTest {

    /** 发布语言 DTO 包前缀（契约面纯净性断言的允许类型范围）。 */
    private static final String API_DTO_PACKAGE = "com.linkroa.deepdataagent.datasource.api.dto";

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
    private DatasourceApplicationService applicationService;

    @InjectMocks
    private DefaultDatasourceApi datasourceApi;

    // ==================== listDatasources ====================

    @Test
    void should_skipMissingAndKeepOrder_when_listDatasources_given_idsWithMissingEntry() {
        // given：1 存在、2 缺失、3 存在
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(enabledJdbc(1L, "it-ds-one")));
        when(connectionRepository.findById(2L)).thenReturn(Optional.empty());
        when(connectionRepository.findById(3L)).thenReturn(Optional.of(enabledApi(3L, "it-ds-three")));

        // when
        List<DatasourceReferenceDTO> references = datasourceApi.listDatasources(List.of(1L, 2L, 3L));

        // then：缺失跳过、保持入参相对顺序、类型以枚举名传输
        assertEquals(2, references.size());
        assertEquals(1L, references.get(0).id());
        assertEquals("it-ds-one", references.get(0).name());
        assertEquals("JDBC", references.get(0).type());
        assertEquals("MYSQL", references.get(0).subType());
        assertEquals(3L, references.get(1).id());
        assertEquals("API", references.get(1).type());
        assertNull(references.get(1).subType());
    }

    @Test
    void should_returnEmptyList_when_listDatasources_given_emptyInput() {
        // given 空入参

        // when
        List<DatasourceReferenceDTO> references = datasourceApi.listDatasources(List.of());

        // then
        assertTrue(references.isEmpty());
        verifyNoInteractions(connectionRepository);
    }

    @Test
    void should_returnEmptyList_when_listDatasources_given_nullInput() {
        // when
        List<DatasourceReferenceDTO> references = datasourceApi.listDatasources(null);

        // then
        assertTrue(references.isEmpty());
        verifyNoInteractions(connectionRepository);
    }

    @Test
    void should_includeDisabledReference_when_listDatasources_given_disabledConnection() {
        // given：禁用态数据源
        when(connectionRepository.findById(5L)).thenReturn(Optional.of(disabledJdbc(5L, "it-ds-off")));

        // when
        List<DatasourceReferenceDTO> references = datasourceApi.listDatasources(List.of(5L));

        // then：引用解析不做状态过滤
        assertEquals(1, references.size());
        assertEquals(5L, references.get(0).id());
    }

    @Test
    void should_touchOnlyConnectionRepository_when_listDatasources_given_ids() {
        // given
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(enabledJdbc(1L, "it-ds-one")));

        // when
        datasourceApi.listDatasources(List.of(1L));

        // then：零副作用——不触碰元数据仓储与应用服务
        verifyNoInteractions(databaseSchemaRepository, tableInfoRepository, columnInfoRepository,
                apiSchemaRepository, apiFieldRepository, applicationService);
    }

    // ==================== describeTable ====================

    @Test
    void should_returnSchemaWithColumns_when_describeTable_given_jdbcTable() {
        // given：启用 JDBC 源 → 首个 schema → 命中表 → 两列
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(enabledJdbc(1L, "it-ds-jdbc")));
        when(databaseSchemaRepository.findByConnectionId(1L))
                .thenReturn(List.of(new DatabaseSchema(10L, 1L, "ag_catalog", null, null, null)));
        when(tableInfoRepository.findByDatabaseSchemaId(10L)).thenReturn(List.of(
                new TableInfo(100L, 10L, "knowledge_base", "知识库表", null, null, null),
                new TableInfo(101L, 10L, "other_table", null, null, null, null)));
        when(columnInfoRepository.findByTableId(100L)).thenReturn(List.of(
                new ColumnInfo(1000L, 100L, "id", "bigint", "主键", "自定义主键", null, null),
                new ColumnInfo(1001L, 100L, "name", "varchar", null, null, null, null)));

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(1L, "knowledge_base");

        // then
        assertTrue(schema.isPresent());
        assertEquals("knowledge_base", schema.get().tableName());
        assertEquals("知识库表", schema.get().tableComment());
        assertEquals(2, schema.get().columns().size());
        assertEquals("id", schema.get().columns().get(0).name());
        assertEquals("bigint", schema.get().columns().get(0).dataType());
    }

    @Test
    void should_returnBothCommentSources_when_describeTable_given_customCommentPresent() {
        // given：字段同时存在原始注释与自定义注释
        stubJdbcTableWithSingleColumn(new ColumnInfo(1000L, 100L, "id", "bigint", "原始注释", "自定义注释", null, null));

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(1L, "knowledge_base");

        // then：双来源均返回，能力面不合并
        assertTrue(schema.isPresent());
        TableSchemaDTO.Column column = schema.get().columns().getFirst();
        assertEquals("原始注释", column.comment());
        assertEquals("自定义注释", column.customComment());
    }

    @Test
    void should_returnEmpty_when_describeTable_given_disabledConnection() {
        // given：禁用态
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(disabledJdbc(1L, "it-ds-off")));

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(1L, "knowledge_base");

        // then：中性返回，且不触碰元数据仓储
        assertTrue(schema.isEmpty());
        verifyNoInteractions(databaseSchemaRepository, tableInfoRepository, columnInfoRepository);
    }

    @Test
    void should_returnEmpty_when_describeTable_given_connectionNotFound() {
        // given
        when(connectionRepository.findById(9L)).thenReturn(Optional.empty());

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(9L, "knowledge_base");

        // then
        assertTrue(schema.isEmpty());
    }

    @Test
    void should_returnEmpty_when_describeTable_given_unknownTableName() {
        // given：schema 存在但无同名表
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(enabledJdbc(1L, "it-ds-jdbc")));
        when(databaseSchemaRepository.findByConnectionId(1L))
                .thenReturn(List.of(new DatabaseSchema(10L, 1L, "ag_catalog", null, null, null)));
        when(tableInfoRepository.findByDatabaseSchemaId(10L)).thenReturn(List.of(
                new TableInfo(100L, 10L, "knowledge_base", null, null, null, null)));

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(1L, "not_exists");

        // then
        assertTrue(schema.isEmpty());
        verifyNoInteractions(columnInfoRepository);
    }

    @Test
    void should_returnEmpty_when_describeTable_given_noSchema() {
        // given：启用 JDBC 源但尚无同步出的 schema
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(enabledJdbc(1L, "it-ds-jdbc")));
        when(databaseSchemaRepository.findByConnectionId(1L)).thenReturn(List.of());

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(1L, "knowledge_base");

        // then
        assertTrue(schema.isEmpty());
    }

    @Test
    void should_returnApiColumns_when_describeTable_given_apiDatasource() {
        // given：API 型数据源命中 API 数据表，字段映射作为列
        when(connectionRepository.findById(3L)).thenReturn(Optional.of(enabledApi(3L, "it-ds-api")));
        when(apiSchemaRepository.findByConnectionIdAndName(3L, "users"))
                .thenReturn(Optional.of(new ApiSchema(20L, 3L, "users", "https://api.example.com/users",
                        HttpMethod.GET, ApiRequestConfig.defaultConfig(), null, null, null, null)));
        when(apiFieldRepository.findByApiSchemaId(20L)).thenReturn(List.of(
                new ApiField(200L, 20L, "id", "ID", "$.id", "number", "标识", null, null)));

        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(3L, "users");

        // then：API 型无表注释概念（null），列取字段映射
        assertTrue(schema.isPresent());
        assertEquals("users", schema.get().tableName());
        assertNull(schema.get().tableComment());
        assertEquals(1, schema.get().columns().size());
        assertEquals("id", schema.get().columns().get(0).name());
        assertEquals("number", schema.get().columns().get(0).dataType());
        assertEquals("标识", schema.get().columns().get(0).comment());
        assertNull(schema.get().columns().get(0).customComment());
        verifyNoInteractions(databaseSchemaRepository);
    }

    @Test
    void should_returnEmpty_when_describeTable_given_blankTableName() {
        // when
        Optional<TableSchemaDTO> schema = datasourceApi.describeTable(1L, "  ");

        // then
        assertTrue(schema.isEmpty());
        verifyNoInteractions(connectionRepository);
    }

    // ==================== previewTable ====================

    @Test
    void should_delegateAndDeriveColumns_when_previewTable_given_rowsReturned() {
        // given：应用层返回两行（列序由首行键序派生）
        when(applicationService.previewTableData(1L, "api_table", 5)).thenReturn(List.of(
                Map.of("id", 1, "name", "a"),
                Map.of("id", 2, "name", "b")));

        // when
        Optional<DatasourcePreviewDTO> preview = datasourceApi.previewTable(1L, "api_table", 5);

        // then
        assertTrue(preview.isPresent());
        assertEquals("api_table", preview.get().tableName());
        assertEquals(2, preview.get().rows().size());
        assertTrue(preview.get().columns().containsAll(List.of("id", "name")));
        verify(applicationService).previewTableData(1L, "api_table", 5);
    }

    @Test
    void should_passLimitThroughUnchanged_when_previewTable_given_oversizedLimit() {
        // given：请求值超上限（钳制由被委托的应用层执行，本面不放宽也不篡改）
        when(applicationService.previewTableData(1L, "api_table", 200)).thenReturn(List.of());

        // when
        Optional<DatasourcePreviewDTO> preview = datasourceApi.previewTable(1L, "api_table", 200);

        // then：原样透传；空数据仍是"可用结果"（present + 空行）
        assertTrue(preview.isPresent());
        assertTrue(preview.get().rows().isEmpty());
        assertTrue(preview.get().columns().isEmpty());
        verify(applicationService).previewTableData(1L, "api_table", 200);
    }

    @Test
    void should_returnEmpty_when_previewTable_given_disabledConnection() {
        // given：应用层抛未启用语义异常
        when(applicationService.previewTableData(1L, "t", 10))
                .thenThrow(new ResourceConflictException("数据源已禁用，请先启用数据源"));

        // when
        Optional<DatasourcePreviewDTO> preview = datasourceApi.previewTable(1L, "t", 10);

        // then：收敛为中性返回（不泄露存在性）
        assertTrue(preview.isEmpty());
    }

    @Test
    void should_returnEmpty_when_previewTable_given_connectionNotFound() {
        // given：应用层抛不存在语义异常
        when(applicationService.previewTableData(9L, "t", 10))
                .thenThrow(new ResourceNotFoundException("数据源不存在"));

        // when
        Optional<DatasourcePreviewDTO> preview = datasourceApi.previewTable(9L, "t", 10);

        // then
        assertTrue(preview.isEmpty());
    }

    @Test
    void should_propagateRuntimeFailure_when_previewTable_given_remoteQueryFailure() {
        // given：远端取数失败（真实运行期故障，非"不存在/未启用"语义）
        when(applicationService.previewTableData(1L, "t", 10))
                .thenThrow(new IllegalStateException("API请求执行失败: 连接超时"));

        // when / then：MUST NOT 被静默收敛为空结果
        assertThrows(IllegalStateException.class, () -> datasourceApi.previewTable(1L, "t", 10));
    }

    @Test
    void should_returnEmpty_when_previewTable_given_blankTableName() {
        // when
        Optional<DatasourcePreviewDTO> preview = datasourceApi.previewTable(1L, "", 10);

        // then
        assertTrue(preview.isEmpty());
        verify(applicationService, never()).previewTableData(anyLong(), anyString(), anyInt());
    }

    // ==================== 契约面纯净性 ====================

    @Test
    void should_exposeExactlyThreeReadOnlyMethods_when_inspectDatasourceApi_given_capabilitySurface() {
        // when
        Method[] methods = DatasourceApi.class.getDeclaredMethods();

        // then：方法集恰为三个只读方法（防后续误加写操作/SQL 执行方法）
        assertEquals(3, methods.length);
        Set<String> names = java.util.Arrays.stream(methods).map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("listDatasources", "describeTable", "previewTable"), names);
    }

    @Test
    void should_referenceOnlyPublishedDtos_when_inspectDatasourceApi_given_capabilitySurface() {
        // when / then：返回值与参数类型不得泄漏 domain 模型（仅允许发布语言 DTO 与 JDK 类型）
        for (Method method : DatasourceApi.class.getDeclaredMethods()) {
            for (Class<?> type : referencedTypes(method.getGenericReturnType())) {
                assertPublishedOrJdk(method.getName(), type);
            }
            for (Type parameterType : method.getGenericParameterTypes()) {
                for (Class<?> type : referencedTypes(parameterType)) {
                    assertPublishedOrJdk(method.getName(), type);
                }
            }
        }
    }

    // ==================== 辅助 ====================

    /**
     * 断言类型位于发布语言 DTO 包或 JDK 包。
     */
    private static void assertPublishedOrJdk(String methodName, Class<?> type) {
        String packageName = type.getPackageName();
        boolean allowed = packageName.startsWith(API_DTO_PACKAGE) || packageName.startsWith("java.");
        assertTrue(allowed, "方法 " + methodName + " 签名泄漏非发布语言类型: " + type.getName());
    }

    /**
     * 收集类型（含泛型实参）中引用的具体类。
     */
    private static Set<Class<?>> referencedTypes(Type type) {
        Set<Class<?>> types = new HashSet<>();
        collect(type, types);
        return types;
    }

    /**
     * 递归收集类与泛型实参。
     */
    private static void collect(Type type, Set<Class<?>> out) {
        if (type instanceof Class<?> clazz) {
            out.add(clazz);
        } else if (type instanceof ParameterizedType parameterizedType) {
            collect(parameterizedType.getRawType(), out);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                collect(argument, out);
            }
        }
    }

    /**
     * 构造启用态 JDBC 数据源（JDBC 类型要求 subType 与连接配置非空）。
     */
    private static DatasourceConnection enabledJdbc(Long id, String name) {
        return new DatasourceConnection(id, name, DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("localhost", 3306, "db", "root", "pwd", null),
                null, null, null, null, null);
    }

    /**
     * 构造禁用态 JDBC 数据源。
     */
    private static DatasourceConnection disabledJdbc(Long id, String name) {
        return new DatasourceConnection(id, name, DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.DISABLED,
                new JdbcConnectionConfig("localhost", 3306, "db", "root", "pwd", null),
                null, null, null, null, null);
    }

    /**
     * 构造启用态 API 数据源。
     */
    private static DatasourceConnection enabledApi(Long id, String name) {
        return new DatasourceConnection(id, name, DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null);
    }

    /**
     * 打桩「启用 JDBC 源 → 首个 schema → 命中单表 → 单列」的读取链。
     */
    private void stubJdbcTableWithSingleColumn(ColumnInfo columnInfo) {
        when(connectionRepository.findById(1L)).thenReturn(Optional.of(enabledJdbc(1L, "it-ds-jdbc")));
        when(databaseSchemaRepository.findByConnectionId(1L))
                .thenReturn(List.of(new DatabaseSchema(10L, 1L, "ag_catalog", null, null, null)));
        when(tableInfoRepository.findByDatabaseSchemaId(10L)).thenReturn(List.of(
                new TableInfo(100L, 10L, "knowledge_base", null, null, null, null)));
        when(columnInfoRepository.findByTableId(100L)).thenReturn(List.of(columnInfo));
    }
}