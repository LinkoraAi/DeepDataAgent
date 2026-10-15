package com.linkroa.deepdataagent.datasource.controller.rest;

import com.linkroa.deepdataagent.datasource.application.command.ApiFieldCommand;
import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.controller.request.ApiFieldRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ApiSchemaRequest;
import com.linkroa.deepdataagent.datasource.controller.request.CreateDatasourceRequest;
import com.linkroa.deepdataagent.datasource.controller.request.JdbcConfigRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ParseApiResponseRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ParamMappingRequest;
import com.linkroa.deepdataagent.datasource.controller.request.PreOperationConfigRequest;
import com.linkroa.deepdataagent.datasource.controller.request.PreOperationTestRequest;
import com.linkroa.deepdataagent.datasource.controller.request.TestConnectionRequest;
import com.linkroa.deepdataagent.datasource.controller.request.UpdateCommentRequest;
import com.linkroa.deepdataagent.datasource.controller.request.UpdateDatasourceRequest;
import com.linkroa.deepdataagent.datasource.controller.response.ApiSchemaDetailResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ApiSchemaResponse;
import com.linkroa.deepdataagent.datasource.controller.response.DatasourceResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ParseApiResponseResponse;
import com.linkroa.deepdataagent.datasource.controller.response.PreOperationTestResponse;
import com.linkroa.deepdataagent.datasource.controller.response.TestConnectionResponse;
import com.linkroa.deepdataagent.datasource.domain.model.ApiAuthConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.model.ApiPaginationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ApiRequestConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.JdbcConnectionConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ParamMapping;
import com.linkroa.deepdataagent.datasource.domain.model.ParsedField;
import com.linkroa.deepdataagent.datasource.domain.model.PreOperationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceTypeEnum;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DatasourceController} 契约测试（唯一入口，全部 23 个端点）。
 *
 * <p>对齐仓库纯 Mockito 直调先例：{@code @Mock} 应用服务 + {@code @InjectMocks} 控制器，
 * 断言「请求 → 命令」组装口径、响应投影形态、错误异常透传（HTTP 状态映射由
 * {@code GlobalExceptionHandler} 负责，其映射本身已由 {@code GlobalExceptionHandlerTest} 覆盖）。
 * 另以真实 {@link Validator} 校验请求 DTO 的 Bean Validation 约束，以真实 Jackson 序列化断言
 * **响应 JSON 不含 password**。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DatasourceControllerTest {

    /** 请求校验器（真实 JSR-303 实现，验证 DTO 注解口径）。 */
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** 序列化器（与运行时统一使用 Jackson 3）。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private DatasourceApplicationService applicationService;

    @InjectMocks
    private DatasourceController controller;

    // ==================== ① 连接生命周期与测试 ====================

    @Test
    void should_returnFourTypesWithDefaultPort_when_listTypes_given_supportedCatalog() {
        // given
        when(applicationService.getSupportedTypes()).thenReturn(List.of(DatasourceTypeEnum.values()));

        // when
        ApiResponse<?> response = controller.listTypes();

        // then：4 个类型项，JDBC 项带默认端口，API 项无默认端口
        @SuppressWarnings("unchecked")
        List<com.linkroa.deepdataagent.datasource.controller.response.DatasourceTypeResponse> types =
                (List<com.linkroa.deepdataagent.datasource.controller.response.DatasourceTypeResponse>)
                        response.data();
        assertEquals(4, types.size());
        assertEquals(3306, types.get(0).defaultPort());
        assertEquals("API", types.get(3).subType());
        assertEquals(null, types.get(3).defaultPort());
    }

    @Test
    void should_mapRequestToCommandAndReturnMaskedResponse_when_createConnection_given_jdbcRequest() {
        // given
        JdbcConfigRequest jdbc = new JdbcConfigRequest("localhost", 3306, "db", "root", "secret", null);
        CreateDatasourceRequest request = new CreateDatasourceRequest(
                "mysql-ds", "JDBC", "MYSQL", "desc", jdbc, null);
        DatasourceConnection saved = jdbcConnection(1L, "mysql-ds");
        when(applicationService.createDatasource(any(CreateDatasourceCommand.class))).thenReturn(saved);

        // when
        ApiResponse<DatasourceResponse> response = controller.createConnection(request);

        // then：命令按 type/subType 正确解析，响应脱敏
        ArgumentCaptor<CreateDatasourceCommand> captor = ArgumentCaptor.forClass(CreateDatasourceCommand.class);
        verify(applicationService).createDatasource(captor.capture());
        assertEquals(DatasourceType.JDBC, captor.getValue().type());
        assertEquals(JdbcType.MYSQL, captor.getValue().subType());
        assertEquals("secret", captor.getValue().jdbcConfig().password());
        assertEquals("mysql-ds", response.data().name());
        assertFalse(JSON.writeValueAsString(response.data()).contains("password"));
    }

    @Test
    void should_applyPagingDefaults_when_listConnections_given_noParams() {
        // given：service 返回分页结果
        when(applicationService.listDatasources(any(ListDatasourceQuery.class)))
                .thenReturn(new DatasourceApplicationService.PaginatedResult<>(List.of(jdbcConnection(1L, "a")), 1L, 1, 20));

        // when
        ApiResponse<PaginatedResponse<DatasourceResponse>> response =
                controller.listConnections(null, null, null, null, null);

        // then：page 兜底 1、size 兜底 20，包装为 PaginatedResponse{list,total,page,size}
        ArgumentCaptor<ListDatasourceQuery> captor = ArgumentCaptor.forClass(ListDatasourceQuery.class);
        verify(applicationService).listDatasources(captor.capture());
        assertEquals(1, captor.getValue().page());
        assertEquals(20, captor.getValue().size());
        assertEquals(1, response.data().list().size());
        assertEquals(1L, response.data().total());
    }

    @Test
    void should_capSizeAt100AndParseFilters_when_listConnections_given_oversizedPageAndFilters() {
        // given
        when(applicationService.listDatasources(any(ListDatasourceQuery.class)))
                .thenReturn(new DatasourceApplicationService.PaginatedResult<>(List.of(), 0L, 2, 100));

        // when
        controller.listConnections("kw", "jdbc", "enabled", 2, 500);

        // then：枚举字符串归一、size 截断 100
        ArgumentCaptor<ListDatasourceQuery> captor = ArgumentCaptor.forClass(ListDatasourceQuery.class);
        verify(applicationService).listDatasources(captor.capture());
        assertEquals(DatasourceType.JDBC, captor.getValue().type());
        assertEquals(DatasourceStatus.ENABLED, captor.getValue().status());
        assertEquals(100, captor.getValue().size());
    }

    @Test
    void should_returnDetail_when_getConnection_given_existingId() {
        // given
        when(applicationService.getDatasource(1L)).thenReturn(jdbcConnection(1L, "mysql-ds"));

        // when
        ApiResponse<DatasourceResponse> response = controller.getConnection(1L);

        // then
        assertEquals(1L, response.data().id());
        assertEquals("MYSQL", response.data().subType());
        assertNullPasswordInJson(response.data());
    }

    @Test
    void should_mapUpdateCommandWithPathId_when_updateConnection_given_request() {
        // given
        JdbcConfigRequest jdbc = new JdbcConfigRequest("localhost", 3306, "db2", "root", "", null);
        UpdateDatasourceRequest request = new UpdateDatasourceRequest("new-name", "d", jdbc);
        when(applicationService.updateDatasource(any(UpdateDatasourceCommand.class)))
                .thenReturn(jdbcConnection(1L, "new-name"));

        // when
        controller.updateConnection(1L, request);

        // then：路径 id 注入命令；密码空白原样下传（应用层据此保留旧密码）
        ArgumentCaptor<UpdateDatasourceCommand> captor = ArgumentCaptor.forClass(UpdateDatasourceCommand.class);
        verify(applicationService).updateDatasource(captor.capture());
        assertEquals(1L, captor.getValue().id());
        assertEquals("new-name", captor.getValue().name());
        assertEquals("", captor.getValue().jdbcConfig().password());
    }

    @Test
    void should_delegateDelete_when_deleteConnection_given_id() {
        // when
        ApiResponse<Void> response = controller.deleteConnection(9L);

        // then
        verify(applicationService).deleteDatasource(9L);
        assertEquals(null, response.data());
        assertTrue(response.success());
    }

    @Test
    void should_delegateStateTransitions_when_enableDisableSync_given_ids() {
        // when
        controller.enableConnection(1L);
        controller.disableConnection(1L);
        controller.syncConnection(1L);

        // then
        verify(applicationService).enableDatasource(1L);
        verify(applicationService).disableDatasource(1L);
        verify(applicationService).syncMetadata(1L);
    }

    @Test
    void should_returnTestResult_when_testSavedConnection_given_id() {
        // given
        when(applicationService.testConnection(any(TestConnectionCommand.class)))
                .thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.ok());

        // when
        ApiResponse<TestConnectionResponse> response = controller.testSavedConnection(1L);

        // then：id 分支命令只带 id，其余字段为空；测试不产生持久化
        ArgumentCaptor<TestConnectionCommand> captor = ArgumentCaptor.forClass(TestConnectionCommand.class);
        verify(applicationService).testConnection(captor.capture());
        assertEquals(1L, captor.getValue().id());
        assertEquals(null, captor.getValue().type());
        assertTrue(response.data().success());
    }

    @Test
    void should_passThroughTemporaryConfig_when_testTemporaryConnection_given_apiConfig() {
        // given
        TestConnectionRequest request = new TestConnectionRequest(null, "API", null, null, null, null, null,
                null, null, "https://api.example.com", "GET", null, null, null, null, "BASIC_AUTH", "u", "p",
                180, null);
        when(applicationService.testConnection(any(TestConnectionCommand.class)))
                .thenReturn(DatasourceConnectionStrategy.ConnectionTestResult.fail("连接被拒绝"));

        // when
        ApiResponse<TestConnectionResponse> response = controller.testTemporaryConnection(request);

        // then
        ArgumentCaptor<TestConnectionCommand> captor = ArgumentCaptor.forClass(TestConnectionCommand.class);
        verify(applicationService).testConnection(captor.capture());
        assertEquals("API", captor.getValue().type());
        assertEquals("https://api.example.com", captor.getValue().apiUrl());
        assertFalse(response.data().success());
        assertEquals("连接被拒绝", response.data().message());
    }

    // ==================== ② 表 / 字段 / 注释 / 预览 ====================

    @Test
    void should_defaultTableSizeTo50_when_listTables_given_noSize() {
        // given
        TableInfo table = new TableInfo(5L, 1L, "users", "用户表", null, null, null);
        when(applicationService.listTables(any(TableListQuery.class)))
                .thenReturn(new DatasourceApplicationService.PaginatedResult<>(List.of(table), 1L, 1, 50));

        // when
        controller.listTables(1L, null, null, null);

        // then
        ArgumentCaptor<TableListQuery> captor = ArgumentCaptor.forClass(TableListQuery.class);
        verify(applicationService).listTables(captor.capture());
        assertEquals(1L, captor.getValue().connectionId());
        assertEquals(50, captor.getValue().size());
    }

    @Test
    void should_returnColumns_when_listColumns_given_tableId() {
        // given
        ColumnInfo column = new ColumnInfo(7L, 5L, "id", "BIGINT", "主键", "自定义", null, null);
        when(applicationService.listColumns(5L)).thenReturn(List.of(column));

        // when
        ApiResponse<?> response = controller.listColumns(5L);

        // then
        assertEquals(1, ((List<?>) response.data()).size());
        verify(applicationService).listColumns(5L);
    }

    @Test
    void should_delegateComments_when_updateTableAndColumnComment_given_validComment() {
        // when
        controller.updateTableComment(5L, new UpdateCommentRequest("表注释"));
        controller.updateColumnComment(7L, new UpdateCommentRequest("字段注释"));

        // then
        verify(applicationService).updateTableComment(5L, "表注释");
        verify(applicationService).updateColumnComment(7L, "字段注释");
    }

    @Test
    void should_defaultPreviewLimitTo100_when_previewTableData_given_noLimit() {
        // given
        when(applicationService.previewTableData(1L, "users", 100)).thenReturn(List.of(Map.of("id", 1)));

        // when
        ApiResponse<List<Map<String, Object>>> response = controller.previewTableData(1L, "users", null);

        // then
        verify(applicationService).previewTableData(1L, "users", 100);
        assertEquals(1, response.data().size());
    }

    // ==================== ③ API 数据表 / 解析 / 前置试跑 ====================

    @Test
    void should_returnMaskedList_when_listApiSchemas_given_connectionId() {
        // given
        when(applicationService.listApiSchemas(1L)).thenReturn(List.of(apiSchemaWithBasicAuth()));

        // when
        ApiResponse<List<ApiSchemaResponse>> response = controller.listApiSchemas(1L);

        // then：鉴权密码不回显，只给 credentialConfigured
        ApiSchemaResponse schema = response.data().getFirst();
        assertEquals("BASIC_AUTH", schema.authConfig().authType());
        assertTrue(schema.authConfig().credentialConfigured());
        assertFalse(JSON.writeValueAsString(schema).contains("password"));
    }

    @Test
    void should_mapSchemaCommand_when_createApiSchema_given_fullRequest() {
        // given
        ApiSchemaRequest request = apiSchemaRequest("orders", "https://api.example.com/orders");
        when(applicationService.createApiSchema(any(Long.class), any(ApiSchemaCommand.class)))
                .thenReturn(apiSchemaWithBasicAuth());

        // when
        controller.createApiSchema(1L, request);

        // then：命令字段归一（枚举大小写、嵌套字段/前置操作）
        ArgumentCaptor<ApiSchemaCommand> captor = ArgumentCaptor.forClass(ApiSchemaCommand.class);
        verify(applicationService).createApiSchema(org.mockito.ArgumentMatchers.eq(1L), captor.capture());
        ApiSchemaCommand command = captor.getValue();
        assertEquals("orders", command.name());
        assertEquals(HttpMethod.POST, command.method());
        // bodyType 以字符串透传，归一（大写）由应用层负责，故此处保持请求原值
        assertEquals("json", command.bodyType());
        assertEquals(ApiAuthType.BASIC_AUTH, command.authType());
        assertEquals(1, command.fields().size());
        assertEquals(1, command.preOperationConfigs().size());
    }

    @Test
    void should_returnSchemaAndFields_when_getApiSchemaDetail_given_id() {
        // given
        ApiField field = new ApiField(3L, 2L, "id", "ID", "$.id", "number", "标识", null, null);
        when(applicationService.getApiSchemaDetail(2L))
                .thenReturn(new DatasourceApplicationService.ApiSchemaDetail(apiSchemaWithBasicAuth(), List.of(field)));

        // when
        ApiResponse<ApiSchemaDetailResponse> response = controller.getApiSchemaDetail(2L);

        // then
        assertEquals("orders-api", response.data().schema().name());
        assertEquals(1, response.data().fields().size());
        assertFalse(JSON.writeValueAsString(response.data()).contains("password"));
    }

    @Test
    void should_delegateDelete_when_deleteApiSchema_given_id() {
        // when
        controller.deleteApiSchema(2L);

        // then
        verify(applicationService).deleteApiSchema(2L);
    }

    @Test
    void should_returnFieldTreeAndRows_when_parseApiResponse_given_request() {
        // given
        ParseApiResponseRequest request = new ParseApiResponseRequest(1L, "https://api.example.com", "GET",
                null, null, null, null, null, null, null, 180, null, "$", null, null, null, null, null, null, null);
        ParsedField root = new ParsedField("id", "$.id", "number", List.of());
        when(applicationService.parseApiResponse(any(ParseApiResponseCommand.class)))
                .thenReturn(new DatasourceApplicationService.ParsedApiResponse(List.of(root), List.of(Map.of("id", 1))));

        // when
        ApiResponse<ParseApiResponseResponse> response = controller.parseApiResponse(request);

        // then
        assertEquals(1, response.data().fieldTree().size());
        assertEquals("id", response.data().fieldTree().getFirst().originalName());
        assertEquals(1, response.data().rows().size());
    }

    @Test
    void should_returnRawResponse_when_testPreOperation_given_preOperationConfig() {
        // given
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(true, "https://auth.example.com/token",
                "POST", null, null, null, null, List.of(new ParamMappingRequest("token", "header", "$.data.token")));
        PreOperationTestRequest request = new PreOperationTestRequest(preOp, "basic", "u", "p");
        when(applicationService.testPreOperation(any(PreOperationConfig.class), any(ApiAuthConfig.class)))
                .thenReturn(Map.of("success", true, "rawResponse", "{\"token\":\"abc\"}"));

        // when
        ApiResponse<PreOperationTestResponse> response = controller.testPreOperation(request);

        // then：前置操作与鉴权均正确映射；原始响应回传
        ArgumentCaptor<PreOperationConfig> preOpCaptor = ArgumentCaptor.forClass(PreOperationConfig.class);
        ArgumentCaptor<ApiAuthConfig> authCaptor = ArgumentCaptor.forClass(ApiAuthConfig.class);
        verify(applicationService).testPreOperation(preOpCaptor.capture(), authCaptor.capture());
        assertEquals(HttpMethod.POST, preOpCaptor.getValue().method());
        assertEquals(1, preOpCaptor.getValue().paramMappings().size());
        assertEquals(ApiAuthType.BASIC_AUTH, authCaptor.getValue().authType());
        assertTrue(response.data().success());
        assertEquals("{\"token\":\"abc\"}", response.data().rawResponse());
    }

    // ==================== 错误透传（HTTP 状态映射由 GlobalExceptionHandler 负责） ====================

    @Test
    void should_propagateNotFound_when_getConnection_given_missingId() {
        // given
        when(applicationService.getDatasource(404L)).thenThrow(new ResourceNotFoundException("数据源不存在"));

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> controller.getConnection(404L));
    }

    @Test
    void should_propagateConflict_when_createConnection_given_duplicateName() {
        // given
        CreateDatasourceRequest request = new CreateDatasourceRequest("dup", "API", null, null, null, null);
        when(applicationService.createDatasource(any(CreateDatasourceCommand.class)))
                .thenThrow(new ResourceConflictException("数据源名称已被使用"));

        // when / then
        assertThrows(ResourceConflictException.class, () -> controller.createConnection(request));
    }

    @Test
    void should_propagateConflict_when_previewTableData_given_disabledConnection() {
        // given
        when(applicationService.previewTableData(1L, "users", 100))
                .thenThrow(new ResourceConflictException("数据源已禁用，请先启用数据源"));

        // when / then
        assertThrows(ResourceConflictException.class, () -> controller.previewTableData(1L, "users", null));
    }

    @Test
    void should_propagateBadRequest_when_enableConnection_given_testFailure() {
        // given：void 方法须用 doThrow 形式打桩
        org.mockito.Mockito.doThrow(new DeepDataAgentException("连接测试失败: timeout"))
                .when(applicationService).enableDatasource(1L);

        // when / then
        assertThrows(DeepDataAgentException.class, () -> controller.enableConnection(1L));
    }

    @Test
    void should_throwIllegalArgument_when_listConnections_given_invalidTypeFilter() {
        // when / then：非法枚举值 → IllegalArgumentException（由 GlobalExceptionHandler 映射 400）
        assertThrows(IllegalArgumentException.class,
                () -> controller.listConnections(null, "oracle", null, null, null));
        verifyNoInteractions(applicationService);
    }

    // ==================== Bean Validation 约束 ====================

    @Test
    void should_reportViolation_when_validateCreateRequest_given_blankName() {
        // given
        CreateDatasourceRequest request = new CreateDatasourceRequest("", "JDBC", "MYSQL", null, null, null);

        // when
        Set<ConstraintViolation<CreateDatasourceRequest>> violations = VALIDATOR.validate(request);

        // then
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("name")));
    }

    @Test
    void should_reportViolation_when_validateJdbcConfig_given_portOutOfRange() {
        // given
        JdbcConfigRequest request = new JdbcConfigRequest("localhost", 70000, "db", "u", "p", null);

        // when
        Set<ConstraintViolation<JdbcConfigRequest>> violations = VALIDATOR.validate(request);

        // then
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("port")));
    }

    @Test
    void should_reportViolation_when_validateComment_given_tooLong() {
        // given
        UpdateCommentRequest request = new UpdateCommentRequest("a".repeat(151));

        // when
        Set<ConstraintViolation<UpdateCommentRequest>> violations = VALIDATOR.validate(request);

        // then
        assertFalse(violations.isEmpty());
    }

    @Test
    void should_reportViolations_when_validateApiSchemaRequest_given_boundsExceeded() {
        // given：retryCount>5、pageSize>500、maxPages>10000、空 name/url
        ApiSchemaRequest request = new ApiSchemaRequest("", "GET", "", null, null, null, null, null, null,
                9, null, null, null, null, null, null, null, 900, 20000, null, null);

        // when
        Set<ConstraintViolation<ApiSchemaRequest>> violations = VALIDATOR.validate(request);

        // then
        Set<String> paths = violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.contains("name"));
        assertTrue(paths.contains("url"));
        assertTrue(paths.contains("retryCount"));
        assertTrue(paths.contains("pageSize"));
        assertTrue(paths.contains("maxPages"));
    }

    @Test
    void should_reportViolation_when_validatePreOperationTestRequest_given_nullPreOperation() {
        // given
        PreOperationTestRequest request = new PreOperationTestRequest(null, null, null, null);

        // when
        Set<ConstraintViolation<PreOperationTestRequest>> violations = VALIDATOR.validate(request);

        // then
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("preOperationConfig")));
    }

    // ==================== 脱敏断言（响应 JSON 不含 password） ====================

    @Test
    void should_notExposePasswordInAnyResponseJson_when_serializeDatasourceResponses() {
        // given：含明文的领域连接（密码 "secret"）
        DatasourceConnection jdbc = jdbcConnection(1L, "mysql-ds");

        // when
        String jdbcJson = JSON.writeValueAsString(
                com.linkroa.deepdataagent.datasource.controller.convert.DatasourceWebConvert.INSTANCE
                        .toDatasourceResponse(jdbc));
        String listJson = JSON.writeValueAsString(
                com.linkroa.deepdataagent.datasource.controller.convert.DatasourceWebConvert.INSTANCE
                        .toDatasourceResponseList(List.of(jdbc)));
        String schemaJson = JSON.writeValueAsString(
                com.linkroa.deepdataagent.datasource.controller.convert.DatasourceWebConvert.INSTANCE
                        .toApiSchemaResponse(apiSchemaWithBasicAuth()));

        // then：任何响应 JSON 都不含 password，也不含明文密码值
        assertFalse(jdbcJson.contains("password"));
        assertFalse(jdbcJson.contains("secret"));
        assertFalse(listJson.contains("password"));
        assertFalse(schemaJson.contains("password"));
        assertFalse(schemaJson.contains("\"p\"") && schemaJson.contains("authConfig"));
        assertNotNull(jdbcJson);
    }

    // ==================== 构造辅助 ====================

    /**
     * 构造 JDBC 数据源领域对象（密码为明文，用于验证脱敏）。
     *
     * @param id   连接 id
     * @param name 连接名称
     * @return 领域对象
     */
    private DatasourceConnection jdbcConnection(Long id, String name) {
        JdbcConnectionConfig config = new JdbcConnectionConfig("localhost", 3306, "db", "root", "secret", null);
        return new DatasourceConnection(id, name, DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                config, "desc", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), "tester", "tester");
    }

    /**
     * 构造含 Basic Auth 密码与分页/前置操作的 API 数据表领域对象。
     *
     * @return 领域对象
     */
    private ApiSchema apiSchemaWithBasicAuth() {
        ParamMapping mapping = new ParamMapping("token", "header", "$.data.token");
        PreOperationConfig preOp = new PreOperationConfig(true, "https://auth.example.com/token", HttpMethod.POST,
                Map.of(), Map.of(), null, BodyType.JSON, List.of(mapping));
        ApiAuthConfig auth = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "apiuser", "apipass");
        ApiPaginationConfig pagination = new ApiPaginationConfig(ApiPaginationType.PAGE_BASED,
                "page", "size", "$.total", 100, 10);
        ApiRequestConfig config = new ApiRequestConfig(Map.of("X-App", "data"), Map.of("q", "1"), null,
                BodyType.JSON, "$.data.*", 180, 2, auth, pagination, List.of(preOp));
        return new ApiSchema(2L, 1L, "orders-api", "https://api.example.com/orders", HttpMethod.POST, config,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                "tester", "tester");
    }

    /**
     * 构造完整的 API 表请求体（含字段与前置操作）。
     *
     * @param name 表名
     * @param url  请求地址
     * @return 请求体
     */
    private ApiSchemaRequest apiSchemaRequest(String name, String url) {
        ApiFieldRequest field = new ApiFieldRequest("id", "ID", "$.id", "number", "标识");
        ParamMappingRequest mapping = new ParamMappingRequest("token", "header", "$.data.token");
        PreOperationConfigRequest preOp = new PreOperationConfigRequest(true, "https://auth.example.com/token",
                "POST", null, null, null, "JSON", List.of(mapping));
        return new ApiSchemaRequest(name, "post", url, Map.of("X-App", "data"), Map.of(), null, "json",
                "$.data.*", 180, 2, "basic", "u", "p", "PAGE_BASED", "size", "page", "$.total", 100, 10,
                List.of(preOp), List.of(field));
    }

    /**
     * 断言响应 JSON 中不含 password 字段。
     *
     * @param response 响应 DTO
     */
    private void assertNullPasswordInJson(DatasourceResponse response) {
        assertFalse(JSON.writeValueAsString(response).contains("password"));
    }
}