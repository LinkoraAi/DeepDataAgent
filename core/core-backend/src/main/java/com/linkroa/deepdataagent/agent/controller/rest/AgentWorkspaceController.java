package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.service.AgentWorkspaceApplicationService;
import com.linkroa.deepdataagent.agent.controller.response.AgentWorkspaceResponse;
import com.linkroa.deepdataagent.agent.controller.request.GetWorkspaceRequest;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentWorkspaceController {

    private final AgentWorkspaceApplicationService workspaceApplicationService;

    public AgentWorkspaceController(AgentWorkspaceApplicationService workspaceApplicationService) {
        this.workspaceApplicationService = workspaceApplicationService;
    }

    @PostMapping("/workspace/get")
    public ApiResponse<AgentWorkspaceResponse> workspace(@RequestBody(required = false) GetWorkspaceRequest request) {
        return ApiResponse.success(workspaceApplicationService.describeWorkspace());
    }
}
