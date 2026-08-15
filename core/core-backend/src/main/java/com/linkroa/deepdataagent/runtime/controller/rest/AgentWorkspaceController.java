package com.linkroa.deepdataagent.runtime.controller.rest;

import com.linkroa.deepdataagent.runtime.application.service.AgentWorkspaceApplicationService;
import com.linkroa.deepdataagent.runtime.controller.response.AgentWorkspaceResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentWorkspaceController {

    @Resource
    private AgentWorkspaceApplicationService workspaceApplicationService;

    @GetMapping("/workspace")
    public ApiResponse<AgentWorkspaceResponse> workspace() {
        return ApiResponse.success(workspaceApplicationService.describeWorkspace());
    }
}
