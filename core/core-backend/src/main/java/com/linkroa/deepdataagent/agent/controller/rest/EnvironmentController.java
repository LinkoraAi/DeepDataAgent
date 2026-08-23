package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.convert.EnvironmentCommandConvert;
import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.application.service.EnvironmentApplicationService;
import com.linkroa.deepdataagent.agent.controller.convert.EnvironmentResponseConvert;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.response.EnvironmentResponse;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行环境管理 REST 控制器（统一前缀 {@code /api/v1/agent/environments}）。
 */
@RestController
@RequestMapping(path = "/agent/environments", version = ApiVersionConstants.CURRENT_API_VERSION)
public class EnvironmentController {

    @Resource
    private EnvironmentApplicationService applicationService;

    @PostMapping
    public ApiResponse<EnvironmentResponse> create(@Valid @RequestBody CreateEnvironmentRequest request) {
        CreateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<EnvironmentResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        ListEnvironmentQuery query = new ListEnvironmentQuery(safePage, safeSize);
        List<Environment> environments = applicationService.list(query);
        long total = applicationService.count();
        return ApiResponse.success(new PaginatedResponse<>(
                environments.stream().map(EnvironmentResponseConvert.INSTANCE::toResponse).toList(),
                total, safePage, safeSize));
    }

    @GetMapping("/{environmentId}")
    public ApiResponse<EnvironmentResponse> detail(@PathVariable String environmentId) {
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(applicationService.get(environmentId)));
    }

    @PostMapping("/{environmentId}")
    public ApiResponse<EnvironmentResponse> update(
            @PathVariable String environmentId,
            @Valid @RequestBody UpdateEnvironmentRequest request
    ) {
        UpdateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toUpdateCommand(environmentId, request);
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(applicationService.update(command)));
    }

    @DeleteMapping("/{environmentId}")
    public ApiResponse<Void> delete(@PathVariable String environmentId) {
        applicationService.delete(environmentId);
        return ApiResponse.success(null);
    }
}