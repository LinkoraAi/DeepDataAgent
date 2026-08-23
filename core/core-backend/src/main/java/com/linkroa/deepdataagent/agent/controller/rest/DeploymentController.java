package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CreateDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.convert.DeploymentResponseConvert;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部署管理 REST 控制器（统一前缀 {@code /api/v1/agent/deployments}）。
 */
@RestController
@RequestMapping(path = "/agent/deployments", version = ApiVersionConstants.CURRENT_API_VERSION)
public class DeploymentController {

    @Resource
    private DeploymentApplicationService applicationService;

    @PostMapping
    public ApiResponse<DeploymentResponse> deploy(@Valid @RequestBody CreateDeploymentRequest request) {
        CreateDeploymentCommand command = new CreateDeploymentCommand(request.agentId(), request.versionNumber());
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.deploy(command)));
    }

    @GetMapping
    public ApiResponse<List<DeploymentResponse>> list(@RequestParam(required = false) String agentId) {
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        List<DeploymentResponse> responses = applicationService.list(agentId).stream()
                .map(DeploymentResponseConvert.INSTANCE::toResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    @GetMapping("/{deploymentId}")
    public ApiResponse<DeploymentResponse> detail(@PathVariable String deploymentId) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.get(deploymentId)));
    }
}