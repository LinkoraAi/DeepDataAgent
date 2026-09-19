package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.convert.ModelProfileCommandConvert;
import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.query.ListModelProfileQuery;
import com.linkroa.deepdataagent.agent.application.service.ModelProfileApplicationService;
import com.linkroa.deepdataagent.agent.controller.convert.ModelProfileResponseConvert;
import com.linkroa.deepdataagent.agent.controller.request.CreateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelProfileRequest;
import com.linkroa.deepdataagent.agent.controller.response.ModelProfileResponse;
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
 * 模型配置管理 REST 控制器（统一前缀 {@code /api/v1/cloud/model-profiles}）。
 */
@RestController
@RequestMapping(path = "/cloud/model-profiles", version = ApiVersionConstants.CURRENT_API_VERSION)
public class ModelProfileController {

    @Resource
    private ModelProfileApplicationService applicationService;

    /**
     * 创建模型配置（display_name 全局唯一；凭证明文经独立密钥加密落库，不进响应）。
     *
     * @param request 模型配置创建请求
     * @return 新建的模型配置
     */
    @PostMapping
    public ApiResponse<ModelProfileResponse> create(@Valid @RequestBody CreateModelProfileRequest request) {
        CreateModelProfileCommand command = ModelProfileCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(ModelProfileResponseConvert.INSTANCE.toResponse(applicationService.createProfile(command)));
    }

    /**
     * 分页列出当前用户的模型配置（页码分页，附命中总数；{@code size} 上限 100）。
     *
     * @param keyword 名称关键字（可空）
     * @param status  状态过滤（可空）
     * @param page    页码（缺省 1，小于 1 按 1）
     * @param size    每页条数（缺省 20，上限 100）
     * @return 分页模型配置列表及总数
     */
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
                .map(ModelProfileResponseConvert.INSTANCE::toResponse)
                .toList();
        return ApiResponse.success(new PaginatedResponse<>(responses, total, query.page(), query.size()));
    }

    /**
     * 模型配置详情（owner 隔离；不存在或越权返回 404）。
     *
     * @param profileId 模型配置业务 ID
     * @return 模型配置
     */
    @GetMapping("/{profileId}")
    public ApiResponse<ModelProfileResponse> detail(@PathVariable String profileId) {
        return ApiResponse.success(ModelProfileResponseConvert.INSTANCE.toResponse(applicationService.getProfile(profileId)));
    }

    /**
     * 更新模型配置（display_name 唯一性排除自身；凭证按 {@code null} 保留 / 空串清空 / 其他重新加密处理）。
     *
     * @param profileId 模型配置业务 ID
     * @param request   模型配置更新请求
     * @return 更新后的模型配置
     */
    @PostMapping("/{profileId}")
    public ApiResponse<ModelProfileResponse> update(
            @PathVariable String profileId,
            @Valid @RequestBody UpdateModelProfileRequest request
    ) {
        UpdateModelProfileCommand command = ModelProfileCommandConvert.INSTANCE.toUpdateCommand(profileId, request);
        return ApiResponse.success(ModelProfileResponseConvert.INSTANCE.toResponse(applicationService.updateProfile(command)));
    }

    /**
     * 停用模型配置（状态置 {@code DISABLED}）。
     *
     * @param profileId 模型配置业务 ID
     * @return 空响应
     */
    @PostMapping("/{profileId}/disable")
    public ApiResponse<Void> disable(@PathVariable String profileId) {
        applicationService.disableProfile(profileId);
        return ApiResponse.success(null);
    }

    /**
     * 启用模型配置（状态置 {@code ENABLED}）。
     *
     * @param profileId 模型配置业务 ID
     * @return 空响应
     */
    @PostMapping("/{profileId}/enable")
    public ApiResponse<Void> enable(@PathVariable String profileId) {
        applicationService.enableProfile(profileId);
        return ApiResponse.success(null);
    }

    /**
     * 删除模型配置：仍被 Agent 版本引用时拒绝（409）。
     *
     * @param profileId 模型配置业务 ID
     * @return 空响应
     */
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