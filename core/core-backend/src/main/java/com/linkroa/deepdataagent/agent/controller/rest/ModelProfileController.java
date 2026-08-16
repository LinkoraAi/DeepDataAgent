package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.assembler.ModelProfileCommandAssembler;
import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.query.ListModelProfileQuery;
import com.linkroa.deepdataagent.agent.application.service.ModelProfileApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CreateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.request.ListModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.response.ModelProfileResponse;
import com.linkroa.deepdataagent.agent.controller.response.ModelProfileResponseMapper;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
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
 * 模型配置管理 REST 控制器（统一前缀 {@code /api/v1/agent/model-profiles}）。
 */
@RestController
@RequestMapping(path = "/agent/model-profiles", version = ApiVersionConstants.CURRENT_API_VERSION)
public class ModelProfileController {

    @Resource
    private ModelProfileApplicationService applicationService;
    @Resource
    private ModelProfileResponseMapper responseMapper;
    @Resource
    private ModelProfileCommandAssembler commandAssembler;

    @PostMapping
    public ApiResponse<ModelProfileResponse> create(@Valid @RequestBody CreateModelProfileRequest request) {
        CreateModelProfileCommand command = commandAssembler.toCreateCommand(request);
        return ApiResponse.success(responseMapper.toResponse(applicationService.createProfile(command)));
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<ModelProfileResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        ListModelProfileQuery query = toListQuery(keyword, status, page, size);
        List<ModelProfile> profiles = applicationService.listProfiles(query);
        long total = applicationService.countProfiles(query);
        List<ModelProfileResponse> responses = profiles.stream()
                .map(responseMapper::toResponse)
                .toList();
        return ApiResponse.success(new PaginatedResponse<>(responses, total, query.page(), query.size()));
    }

    @GetMapping("/{profileId}")
    public ApiResponse<ModelProfileResponse> detail(@PathVariable String profileId) {
        return ApiResponse.success(responseMapper.toResponse(applicationService.getProfile(profileId)));
    }

    @PostMapping("/{profileId}")
    public ApiResponse<ModelProfileResponse> update(
            @PathVariable String profileId,
            @Valid @RequestBody UpdateModelProfileRequest request
    ) {
        UpdateModelProfileCommand command = commandAssembler.toUpdateCommand(profileId, request);
        return ApiResponse.success(responseMapper.toResponse(applicationService.updateProfile(command)));
    }

    @PostMapping("/{profileId}/disable")
    public ApiResponse<Void> disable(@PathVariable String profileId) {
        applicationService.disableProfile(profileId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{profileId}/enable")
    public ApiResponse<Void> enable(@PathVariable String profileId) {
        applicationService.enableProfile(profileId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{profileId}")
    public ApiResponse<Void> delete(@PathVariable String profileId) {
        applicationService.deleteProfile(profileId);
        return ApiResponse.success(null);
    }

    private ListModelProfileQuery toListQuery(String keyword, String status, Integer page, Integer size) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        ModelProfileStatus statusEnum = StringUtils.isNotBlank(status)
                ? ModelProfileStatus.valueOf(status) : null;
        return new ListModelProfileQuery(keyword, statusEnum, safePage, safeSize);
    }
}