package com.linkroa.deepdataagent.datasource.controller.rest;

import com.linkroa.deepdataagent.datasource.application.assembler.DatasourceCommandAssembler;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.service.DatasourceApplicationService;
import com.linkroa.deepdataagent.datasource.application.validation.DatasourceValidator;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.controller.response.*;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源管理REST控制器
 * <p>负责HTTP协议适配，接收Request并返回Response，
 * 复杂转换逻辑委托给DatasourceCommandFactory处理。
 * 所有接口统一路径，通过type字段区分数据源类型。</p>
 */
@RestController
@RequestMapping("/datasource")
public class DatasourceController {

    private final DatasourceApplicationService applicationService;

    public DatasourceController(DatasourceApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 获取支持的数据源类型列表
     */
    @GetMapping("/supported-types")
    public ApiResponse<List<DatasourceTypeResponse>> getSupportedTypes() {
        List<DatasourceTypeResponse> types = applicationService.getSupportedTypes();
        return ApiResponse.success(types);
    }

    @PostMapping("/test-connection")
    public ApiResponse<String> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        TestConnectionCommand command = DatasourceCommandAssembler.toTestCommand(request);
        DatasourceConnectionStrategy.ConnectionTestResult result = applicationService.testConnection(command);
        if (!result.success()) {
            throw new DeepDataAgentException(result.message());
        }
        return ApiResponse.success("连接测试成功");
    }

    @PostMapping("/create")
    public ApiResponse<String> create(@Valid @RequestBody CreateDatasourceRequest request) {
        CreateDatasourceCommand command = DatasourceCommandAssembler.toCreateCommand(request);
        applicationService.createDatasource(command);
        return ApiResponse.success("新增数据源成功");
    }

    @PostMapping("/update")
    public ApiResponse<String> update(@Valid @RequestBody UpdateDatasourceRequest request) {
        UpdateDatasourceCommand command = DatasourceCommandAssembler.toUpdateCommand(request);
        applicationService.updateDatasource(command);
        return ApiResponse.success("更新数据源成功");
    }

    @PostMapping("/enable")
    public ApiResponse<String> enable(@Valid @RequestBody IdRequest request) {
        applicationService.enableDatasource(request.id());
        return ApiResponse.success("启用数据源成功");
    }

    @PostMapping("/disable")
    public ApiResponse<String> disable(@Valid @RequestBody IdRequest request) {
        applicationService.disableDatasource(request.id());
        return ApiResponse.success("禁用数据源成功");
    }

    @PostMapping("/delete")
    public ApiResponse<String> delete(@Valid @RequestBody IdRequest request) {
        applicationService.deleteDatasource(request.id());
        return ApiResponse.success("删除数据源成功");
    }

    /**
     * 解析API响应并提取字段
     * <p>前端发送测试请求配置，后端执行请求并解析返回JSON，
     * 自动提取字段列表和预览数据供前端展示。</p>
     */
    @PostMapping("/api/parse-response")
    public Object parseResponse(@Valid @RequestBody ParseApiResponseRequest request) {
        ParseApiResponseCommand command = DatasourceCommandAssembler.toParseCommand(request);
        return applicationService.parseApiResponse(command);
    }

    @PostMapping("/sync")
    public ApiResponse<String> sync(@Valid @RequestBody IdRequest request) {
        applicationService.frontendSyncMetadata(request.id());
        return ApiResponse.success("同步元数据成功");
    }

    @PostMapping("/list")
    public ApiResponse<PaginatedResponse<DatasourceConnectionResponse>> list(@Valid @RequestBody ListDatasourceRequest request) {
        ListDatasourceQuery query = DatasourceCommandAssembler.toListQuery(request);
        DatasourceApplicationService.PaginatedResult<DatasourceConnection> result = applicationService.listDatasources(query);
        List<DatasourceConnectionResponse> responses = result.data().stream()
                .map(DatasourceConnectionResponse::from)
                .toList();
        return ApiResponse.success(new PaginatedResponse<>(responses, result.total(), result.page(), result.size()));
    }

    @PostMapping("/table/list")
    public ApiResponse<?> tableList(@Valid @RequestBody ListTablesRequest request) {
        DatasourceType type = DatasourceValidator.parseDatasourceType(request.type());
        
        if (type == DatasourceType.API) {
            List<ApiSchema> schemas = applicationService.listApiSchemas(request.connectionId());
            List<TableResponse> responses = schemas.stream()
                .map(TableResponse::fromApiSchema)
                .toList();
            return ApiResponse.success(responses);
        } else {
            TableListQuery query = DatasourceCommandAssembler.toTableListQuery(request);
            DatasourceApplicationService.PaginatedResult<TableInfo> result = applicationService.listTables(query);
            List<TableResponse> responses = result.data().stream()
                .map(TableResponse::fromTableInfo)
                .toList();
            return ApiResponse.success(new PaginatedResponse<>(responses, result.total(), result.page(), result.size()));
        }
    }

    @PostMapping("/column/list")
    public ApiResponse<List<ColumnInfoResponse>> columnList(@Valid @RequestBody ColumnListRequest request) {
        DatasourceType type = DatasourceValidator.parseDatasourceType(request.type());
        if (type == DatasourceType.API) {
            List<ApiField> fields = applicationService.listApiFields(request.schemaId());
            List<ColumnInfoResponse> responses = fields.stream()
                    .map(ColumnInfoResponse::fromApiField)
                    .toList();
            return ApiResponse.success(responses);
        } else {
            List<ColumnInfo> columns = applicationService.listColumns(request.tableId());
            List<ColumnInfoResponse> responses = columns.stream()
                    .map(ColumnInfoResponse::from)
                    .toList();
            return ApiResponse.success(responses);
        }
    }

    @PostMapping("/update-table-comment")
    public ApiResponse<String> updateTableComment(@Valid @RequestBody UpdateCommentRequest request) {
        applicationService.updateTableComment(request.id(), request.comment());
        return ApiResponse.success("更新表注释成功");
    }

    @PostMapping("/update-column-comment")
    public ApiResponse<String> updateColumnComment(@Valid @RequestBody UpdateCommentRequest request) {
        applicationService.updateColumnComment(request.id(), request.comment());
        return ApiResponse.success("更新字段注释成功");
    }

    @PostMapping("/table/preview")
    public ApiResponse<List<Map<String, Object>>> previewTable(@Valid @RequestBody PreviewTableRequest request) {
        int limit = request.limit() != null ? request.limit() : 100;
        List<Map<String, Object>> data = applicationService.previewTableData(
            request.connectionId(),
            request.tableName(),
            limit
        );
        return ApiResponse.success(data);
    }
}
