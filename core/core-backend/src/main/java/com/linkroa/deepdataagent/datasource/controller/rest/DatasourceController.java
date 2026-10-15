package com.linkroa.deepdataagent.datasource.controller.rest;

import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.controller.convert.DatasourceWebConvert;
import com.linkroa.deepdataagent.datasource.controller.request.ApiSchemaRequest;
import com.linkroa.deepdataagent.datasource.controller.request.CreateDatasourceRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ParseApiResponseRequest;
import com.linkroa.deepdataagent.datasource.controller.request.PreOperationTestRequest;
import com.linkroa.deepdataagent.datasource.controller.request.TestConnectionRequest;
import com.linkroa.deepdataagent.datasource.controller.request.UpdateCommentRequest;
import com.linkroa.deepdataagent.datasource.controller.request.UpdateDatasourceRequest;
import com.linkroa.deepdataagent.datasource.controller.response.ApiSchemaDetailResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ApiSchemaResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ColumnInfoResponse;
import com.linkroa.deepdataagent.datasource.controller.response.DatasourceResponse;
import com.linkroa.deepdataagent.datasource.controller.response.DatasourceTypeResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ParseApiResponseResponse;
import com.linkroa.deepdataagent.datasource.controller.response.PreOperationTestResponse;
import com.linkroa.deepdataagent.datasource.controller.response.TableInfoResponse;
import com.linkroa.deepdataagent.datasource.controller.response.TestConnectionResponse;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源管理 REST 控制器（**唯一入口**）。
 *
 * <p>数据源域的全部 23 个端点都定义在本类内，按三段分区组织（便于阅读，不分散入口）：
 * ① 连接生命周期与测试；② 表 / 字段 / 注释 / 数据预览；③ API 数据表 / 响应解析 / 前置试跑。
 * 本类只做协议适配（请求 → 命令、调应用服务、领域 → 响应），不含业务逻辑、不加事务注解
 * （事务边界由 {@link DatasourceApplicationService} 内的 TransactionTemplate 管理）。</p>
 *
 * <p>经 {@code ApiVersioningConfig} 前缀挂载后，全部端点对外为 {@code /api/v1/datasource/*}。</p>
 *
 * @author DeepDataAgent
 */
@RestController
@RequestMapping(path = "/datasource", version = ApiVersionConstants.CURRENT_API_VERSION)
public class DatasourceController {

    /** 默认页码。 */
    private static final int DEFAULT_PAGE = 1;

    /** 连接列表默认每页条数。 */
    private static final int DEFAULT_SIZE = 20;

    /** 表列表默认每页条数。 */
    private static final int TABLE_DEFAULT_SIZE = 50;

    /** 每页条数上限。 */
    private static final int MAX_SIZE = 100;

    /** 数据预览默认返回行数（应用层会钳制到 1~100）。 */
    private static final int DEFAULT_PREVIEW_LIMIT = 100;

    private final DatasourceApplicationService applicationService;

    public DatasourceController(DatasourceApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    // ==================== ① 连接生命周期与测试 ====================

    /**
     * 查询受支持的数据源类型清单（MySQL / ClickHouse / PostgreSQL / API）。
     *
     * @return 4 个类型项（含默认端口）
     */
    @GetMapping("/types")
    public ApiResponse<List<DatasourceTypeResponse>> listTypes() {
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toTypeResponseList(
                applicationService.getSupportedTypes()));
    }

    /**
     * 创建数据源（JDBC 保存后自动同步元数据；API 可内嵌多张表）。
     *
     * @param request 创建请求体
     * @return 创建后的连接详情（脱敏）
     */
    @PostMapping("/connections")
    public ApiResponse<DatasourceResponse> createConnection(
            @Valid @RequestBody CreateDatasourceRequest request) {
        DatasourceConnection created = applicationService.createDatasource(
                DatasourceWebConvert.INSTANCE.toCreateCommand(request));
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toDatasourceResponse(created));
    }

    /**
     * 分页查询数据源连接（支持名称关键字、类型、状态过滤）。
     *
     * @param keyword 名称关键字（可空）
     * @param type    主类型过滤（可空）
     * @param status  状态过滤（可空）
     * @param page    页码（缺省 1）
     * @param size    每页条数（缺省 20，上限 100）
     * @return 分页连接列表（脱敏）
     */
    @GetMapping("/connections")
    public ApiResponse<PaginatedResponse<DatasourceResponse>> listConnections(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int safePage = page == null || page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        DatasourceApplicationService.PaginatedResult<DatasourceConnection> result =
                applicationService.listDatasources(
                        DatasourceWebConvert.INSTANCE.toListQuery(keyword, type, status, safePage, safeSize));
        return ApiResponse.success(new PaginatedResponse<>(
                DatasourceWebConvert.INSTANCE.toDatasourceResponseList(result.data()),
                result.total(), result.page(), result.size()));
    }

    /**
     * 查询数据源连接详情。
     *
     * @param id 连接 id
     * @return 连接详情（脱敏）
     */
    @GetMapping("/connections/{id}")
    public ApiResponse<DatasourceResponse> getConnection(@PathVariable Long id) {
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toDatasourceResponse(
                applicationService.getDatasource(id)));
    }

    /**
     * 编辑数据源（类型不可改；JDBC 密码留空表示沿用原密码；JDBC 更新后自动同步元数据）。
     *
     * @param id      连接 id
     * @param request 编辑请求体
     * @return 更新后的连接详情（脱敏）
     */
    @PutMapping("/connections/{id}")
    public ApiResponse<DatasourceResponse> updateConnection(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDatasourceRequest request) {
        DatasourceConnection updated = applicationService.updateDatasource(
                DatasourceWebConvert.INSTANCE.toUpdateCommand(id, request));
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toDatasourceResponse(updated));
    }

    /**
     * 删除数据源（仅禁用态可删；级联清理元数据）。
     *
     * @param id 连接 id
     * @return 成功空数据
     */
    @DeleteMapping("/connections/{id}")
    public ApiResponse<Void> deleteConnection(@PathVariable Long id) {
        applicationService.deleteDatasource(id);
        return ApiResponse.success(null);
    }

    /**
     * 启用数据源（启用前真实测试连接）。
     *
     * @param id 连接 id
     * @return 成功空数据
     */
    @PostMapping("/connections/{id}/enable")
    public ApiResponse<Void> enableConnection(@PathVariable Long id) {
        applicationService.enableDatasource(id);
        return ApiResponse.success(null);
    }

    /**
     * 禁用数据源（仅启用态可禁用）。
     *
     * @param id 连接 id
     * @return 成功空数据
     */
    @PostMapping("/connections/{id}/disable")
    public ApiResponse<Void> disableConnection(@PathVariable Long id) {
        applicationService.disableDatasource(id);
        return ApiResponse.success(null);
    }

    /**
     * 手动同步元数据（仅启用态可同步；JDBC 增量对账）。
     *
     * @param id 连接 id
     * @return 成功空数据
     */
    @PostMapping("/connections/{id}/sync")
    public ApiResponse<Void> syncConnection(@PathVariable Long id) {
        applicationService.syncMetadata(id);
        return ApiResponse.success(null);
    }

    /**
     * 测试已保存数据源的连接（按 id）。
     *
     * @param id 连接 id
     * @return 测试结果（不产生持久化）
     */
    @PostMapping("/connections/{id}/test")
    public ApiResponse<TestConnectionResponse> testSavedConnection(@PathVariable Long id) {
        DatasourceConnectionStrategy.ConnectionTestResult result = applicationService.testConnection(
                DatasourceWebConvert.INSTANCE.toTestConnectionCommand(
                        new TestConnectionRequest(id, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null)));
        return ApiResponse.success(new TestConnectionResponse(result.success(), result.message()));
    }

    /**
     * 测试未保存的临时配置（JDBC 或 API）。
     *
     * @param request 测试请求体（id 为空，字段按 type 区分）
     * @return 测试结果（不产生持久化）
     */
    @PostMapping("/connections/test")
    public ApiResponse<TestConnectionResponse> testTemporaryConnection(
            @Valid @RequestBody TestConnectionRequest request) {
        DatasourceConnectionStrategy.ConnectionTestResult result = applicationService.testConnection(
                DatasourceWebConvert.INSTANCE.toTestConnectionCommand(request));
        return ApiResponse.success(new TestConnectionResponse(result.success(), result.message()));
    }

    // ==================== ② 表 / 字段 / 注释 / 数据预览 ====================

    /**
     * 分页查询某连接下的表元数据（默认每页 50）。
     *
     * @param id      连接 id
     * @param keyword 表名关键字（可空）
     * @param page    页码（缺省 1）
     * @param size    每页条数（缺省 50，上限 100）
     * @return 分页表列表
     */
    @GetMapping("/connections/{id}/tables")
    public ApiResponse<PaginatedResponse<TableInfoResponse>> listTables(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int safePage = page == null || page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
        int safeSize = size == null || size < 1 ? TABLE_DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        DatasourceApplicationService.PaginatedResult<TableInfo> result = applicationService.listTables(
                DatasourceWebConvert.INSTANCE.toTableListQuery(id, keyword, safePage, safeSize));
        return ApiResponse.success(new PaginatedResponse<>(
                DatasourceWebConvert.INSTANCE.toTableInfoResponseList(result.data()),
                result.total(), result.page(), result.size()));
    }

    /**
     * 查询某张表的字段列表（保持原始顺序）。
     *
     * @param tableId 表 id
     * @return 字段列表
     */
    @GetMapping("/tables/{tableId}/columns")
    public ApiResponse<List<ColumnInfoResponse>> listColumns(@PathVariable Long tableId) {
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toColumnInfoResponseList(
                applicationService.listColumns(tableId)));
    }

    /**
     * 更新表自定义注释（0-150 字符）。
     *
     * @param tableId 表 id
     * @param request 注释请求体
     * @return 成功空数据
     */
    @PutMapping("/tables/{tableId}/comment")
    public ApiResponse<Void> updateTableComment(
            @PathVariable Long tableId,
            @Valid @RequestBody UpdateCommentRequest request) {
        applicationService.updateTableComment(tableId, request.comment());
        return ApiResponse.success(null);
    }

    /**
     * 更新字段自定义注释（0-150 字符）。
     *
     * @param columnId 字段 id
     * @param request  注释请求体
     * @return 成功空数据
     */
    @PutMapping("/columns/{columnId}/comment")
    public ApiResponse<Void> updateColumnComment(
            @PathVariable Long columnId,
            @Valid @RequestBody UpdateCommentRequest request) {
        applicationService.updateColumnComment(columnId, request.comment());
        return ApiResponse.success(null);
    }

    /**
     * 预览表数据（只读；仅启用态可预览；行数上限 100）。
     *
     * @param id        连接 id
     * @param tableName 表名（JDBC 表名或 API 表名）
     * @param limit     返回行数（缺省 100，应用层钳制 1~100）
     * @return 只读数据行列表
     */
    @GetMapping("/connections/{id}/tables/{tableName}/preview")
    public ApiResponse<List<Map<String, Object>>> previewTableData(
            @PathVariable Long id,
            @PathVariable String tableName,
            @RequestParam(required = false) Integer limit) {
        int safeLimit = limit == null ? DEFAULT_PREVIEW_LIMIT : limit;
        return ApiResponse.success(applicationService.previewTableData(id, tableName, safeLimit));
    }

    // ==================== ③ API 数据表 / 响应解析 / 前置试跑 ====================

    /**
     * 查询某连接下的 API 数据表列表（脱敏）。
     *
     * @param id 连接 id
     * @return API 表列表
     */
    @GetMapping("/connections/{id}/api-schemas")
    public ApiResponse<List<ApiSchemaResponse>> listApiSchemas(@PathVariable Long id) {
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toApiSchemaResponseList(
                applicationService.listApiSchemas(id)));
    }

    /**
     * 在某 API 类型数据源下创建 API 数据表。
     *
     * @param id      连接 id
     * @param request 表配置请求体
     * @return 创建后的表详情（脱敏）
     */
    @PostMapping("/connections/{id}/api-schemas")
    public ApiResponse<ApiSchemaResponse> createApiSchema(
            @PathVariable Long id,
            @Valid @RequestBody ApiSchemaRequest request) {
        ApiSchema created = applicationService.createApiSchema(
                id, DatasourceWebConvert.INSTANCE.toApiSchemaCommand(request));
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toApiSchemaResponse(created));
    }

    /**
     * 查询 API 数据表详情（表配置 + 字段映射）。
     *
     * @param schemaId 表 id
     * @return 组合详情（脱敏）
     */
    @GetMapping("/api-schemas/{schemaId}")
    public ApiResponse<ApiSchemaDetailResponse> getApiSchemaDetail(@PathVariable Long schemaId) {
        DatasourceApplicationService.ApiSchemaDetail detail = applicationService.getApiSchemaDetail(schemaId);
        return ApiResponse.success(new ApiSchemaDetailResponse(
                DatasourceWebConvert.INSTANCE.toApiSchemaResponse(detail.schema()),
                DatasourceWebConvert.INSTANCE.toApiFieldResponseList(detail.fields())));
    }

    /**
     * 更新 API 数据表（增量合并：缺省项沿用原值；fields 非空则全量重建字段映射）。
     *
     * @param schemaId 表 id
     * @param request  表配置请求体
     * @return 更新后的表详情（脱敏）
     */
    @PutMapping("/api-schemas/{schemaId}")
    public ApiResponse<ApiSchemaResponse> updateApiSchema(
            @PathVariable Long schemaId,
            @Valid @RequestBody ApiSchemaRequest request) {
        ApiSchema updated = applicationService.updateApiSchema(
                schemaId, DatasourceWebConvert.INSTANCE.toApiSchemaCommand(request));
        return ApiResponse.success(DatasourceWebConvert.INSTANCE.toApiSchemaResponse(updated));
    }

    /**
     * 删除 API 数据表（级联删除其字段映射）。
     *
     * @param schemaId 表 id
     * @return 成功空数据
     */
    @DeleteMapping("/api-schemas/{schemaId}")
    public ApiResponse<Void> deleteApiSchema(@PathVariable Long schemaId) {
        applicationService.deleteApiSchema(schemaId);
        return ApiResponse.success(null);
    }

    /**
     * 发送一次请求并解析响应（返回字段树与前 10 行样例，不持久化）。
     *
     * @param request 解析请求体
     * @return 字段树 + 样例行
     */
    @PostMapping("/api-schemas/parse")
    public ApiResponse<ParseApiResponseResponse> parseApiResponse(
            @Valid @RequestBody ParseApiResponseRequest request) {
        DatasourceApplicationService.ParsedApiResponse parsed = applicationService.parseApiResponse(
                DatasourceWebConvert.INSTANCE.toParseCommand(request));
        return ApiResponse.success(new ParseApiResponseResponse(
                DatasourceWebConvert.INSTANCE.toParsedFieldResponseList(parsed.fieldTree()),
                parsed.rows()));
    }

    /**
     * 试跑单个前置操作（返回原始响应文本，供配置参数映射前校验）。
     *
     * @param request 试跑请求体
     * @return 成功标志与原始响应
     */
    @PostMapping("/api-schemas/pre-operation-test")
    public ApiResponse<PreOperationTestResponse> testPreOperation(
            @Valid @RequestBody PreOperationTestRequest request) {
        Map<String, Object> result = applicationService.testPreOperation(
                DatasourceWebConvert.INSTANCE.toPreOperationConfig(request.preOperationConfig()),
                DatasourceWebConvert.INSTANCE.toTestAuthConfig(request));
        return ApiResponse.success(new PreOperationTestResponse(
                Boolean.TRUE.equals(result.get("success")),
                result.get("rawResponse") == null ? null : String.valueOf(result.get("rawResponse"))));
    }
}