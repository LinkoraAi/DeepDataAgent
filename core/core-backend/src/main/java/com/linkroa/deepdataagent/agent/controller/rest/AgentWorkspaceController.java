package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.dto.WorkspaceDTO;
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
        WorkspaceDTO dto = workspaceApplicationService.describeWorkspace();
        return ApiResponse.success(toResponse(dto));
    }

    /**
     * 将应用层 DTO 转换为控制器层响应对象
     *
     * @param dto 工作区 DTO
     * @return 工作区响应对象
     */
    private AgentWorkspaceResponse toResponse(WorkspaceDTO dto) {
        return new AgentWorkspaceResponse(
                dto.applicationName(),
                dto.boundedContexts(),
                dto.sandboxEnabled(),
                dto.serverProxyEnabled(),
                dto.sqlitePath()
        );
    }
}
