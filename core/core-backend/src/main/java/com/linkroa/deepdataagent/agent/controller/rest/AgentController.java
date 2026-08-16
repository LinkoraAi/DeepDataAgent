package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.assembler.AgentCommandAssembler;
import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.application.service.AgentApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.response.AgentDetailResponse;
import com.linkroa.deepdataagent.agent.controller.response.AgentResponse;
import com.linkroa.deepdataagent.agent.controller.response.AgentResponseMapper;
import com.linkroa.deepdataagent.agent.controller.response.AgentVersionResponse;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
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
 * Agent 定义与版本管理 REST 控制器（统一前缀 {@code /api/v1/agent/agents}）。
 */
@RestController
@RequestMapping(path = "/agent/agents", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentController {

    @Resource
    private AgentApplicationService applicationService;
    @Resource
    private AgentResponseMapper responseMapper;
    @Resource
    private AgentCommandAssembler commandAssembler;

    @PostMapping
    public ApiResponse<AgentResponse> create(@Valid @RequestBody AgentConfigRequest request) {
        CreateAgentCommand command = commandAssembler.toCreateCommand(request);
        return ApiResponse.success(responseMapper.toResponse(applicationService.createAgent(command)));
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<AgentResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean includeArchived,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        ListAgentQuery query = toListQuery(keyword, includeArchived, page, size);
        List<AgentDefinition> definitions = applicationService.listAgents(query);
        long total = applicationService.countAgents(query);
        List<AgentResponse> responses = definitions.stream()
                .map(responseMapper::toResponse)
                .toList();
        return ApiResponse.success(new PaginatedResponse<>(responses, total, query.page(), query.size()));
    }

    @GetMapping("/{agentId}")
    public ApiResponse<AgentDetailResponse> detail(@PathVariable String agentId) {
        AgentDefinition definition = applicationService.getAgent(agentId);
        AgentVersion latestVersion = applicationService.getLatestVersion(agentId);
        return ApiResponse.success(responseMapper.toDetailResponse(definition, latestVersion));
    }

    @PostMapping("/{agentId}/versions")
    public ApiResponse<AgentVersionResponse> publishVersion(
            @PathVariable String agentId,
            @Valid @RequestBody AgentConfigRequest request
    ) {
        PublishAgentVersionCommand command = commandAssembler.toPublishCommand(agentId, request);
        return ApiResponse.success(responseMapper.toVersionResponse(applicationService.publishVersion(command)));
    }

    @GetMapping("/{agentId}/versions")
    public ApiResponse<List<AgentVersionResponse>> listVersions(@PathVariable String agentId) {
        List<AgentVersionResponse> responses = applicationService.listVersions(agentId).stream()
                .map(responseMapper::toVersionResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    @PostMapping("/{agentId}/archive")
    public ApiResponse<Void> archive(@PathVariable String agentId) {
        applicationService.archiveAgent(agentId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> delete(@PathVariable String agentId) {
        applicationService.deleteAgent(agentId);
        return ApiResponse.success(null);
    }

    private ListAgentQuery toListQuery(String keyword, Boolean includeArchived, Integer page, Integer size) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        return new ListAgentQuery(keyword, includeArchived != null && includeArchived, safePage, safeSize);
    }
}