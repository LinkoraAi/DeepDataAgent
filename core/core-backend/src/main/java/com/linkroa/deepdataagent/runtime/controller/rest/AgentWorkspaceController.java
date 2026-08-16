package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.service.AgentWorkspaceApplicationService;
import com.linkroa.deepdataagent.runtime.controller.response.AgentWorkspaceResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工作空间 REST 控制器（v1 版本，URL {@code GET /api/v1/agent/workspace}）。
 */
@RestController
@RequestMapping(path = "/agent", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentWorkspaceController {

    @Resource
    private AgentWorkspaceApplicationService workspaceApplicationService;

    @GetMapping("/workspace")
    public ApiResponse<AgentWorkspaceResponse> workspace() {
        return ApiResponse.success(workspaceApplicationService.describeWorkspace());
    }
}
