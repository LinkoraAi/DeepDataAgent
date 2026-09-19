package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.convert.EnvironmentCommandConvert;
import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.service.EnvironmentApplicationService;
import com.linkroa.deepdataagent.agent.controller.convert.EnvironmentResponseConvert;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.response.EnvironmentResponse;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.security.AuthContext;
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

/**
 * 运行环境管理 REST 控制器（统一前缀 {@code /api/v1/cloud/environments}）。
 */
@RestController
@RequestMapping(path = "/cloud/environments", version = ApiVersionConstants.CURRENT_API_VERSION)
public class EnvironmentController {

    @Resource
    private EnvironmentApplicationService applicationService;

    /**
     * 创建运行环境（名称在 owner 内唯一）。
     *
     * @param request 环境创建请求
     * @return 新建的运行环境
     */
    @PostMapping
    public ApiResponse<EnvironmentResponse> create(@Valid @RequestBody CreateEnvironmentRequest request) {
        CreateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    /**
     * 游标分页列出运行环境（6.6 管理面 Cursor 约定，创建时间降序）。
     * <p>{@code metadata} 为 JSON 对象文本（JSONB 包含过滤，非法 → 400）；
     * {@code created_at[gte]} / {@code created_at[lte]} 为 ISO-8601 时间区间（非法 → 400）；
     * {@code limit / after_id / before_id} 统一游标约定（游标环境不存在 / 非本人 → 404）。</p>
     */
    @GetMapping
    public ApiResponse<CursorPage<EnvironmentResponse>> list(
            @RequestParam(name = "metadata", required = false) String metadata,
            @RequestParam(name = "created_at[gte]", required = false) String createdAfter,
            @RequestParam(name = "created_at[lte]", required = false) String createdBefore,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<Environment> page = applicationService.list(EnvironmentCommandConvert.INSTANCE
                .toListQuery(AuthContext.requireUserId(), metadata, createdAfter, createdBefore,
                        limit, afterId, beforeId));
        return ApiResponse.success(page.map(EnvironmentResponseConvert.INSTANCE::toResponse));
    }

    /**
     * 运行环境详情（owner 隔离；不存在或越权返回 404）。
     *
     * @param environmentId 环境业务 ID
     * @return 运行环境
     */
    @GetMapping("/{environmentId}")
    public ApiResponse<EnvironmentResponse> detail(@PathVariable String environmentId) {
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(applicationService.get(environmentId)));
    }

    /**
     * 更新运行环境（名称唯一性校验排除自身）。
     *
     * @param environmentId 环境业务 ID
     * @param request       环境更新请求
     * @return 更新后的运行环境
     */
    @PostMapping("/{environmentId}")
    public ApiResponse<EnvironmentResponse> update(
            @PathVariable String environmentId,
            @Valid @RequestBody UpdateEnvironmentRequest request
    ) {
        UpdateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toUpdateCommand(environmentId, request);
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(applicationService.update(command)));
    }

    /**
     * 删除运行环境：仍被未删除会话或未归档调度器引用时拒绝（409）。
     *
     * @param environmentId 环境业务 ID
     * @return 空响应
     */
    @DeleteMapping("/{environmentId}")
    public ApiResponse<Void> delete(@PathVariable String environmentId) {
        applicationService.delete(environmentId);
        return ApiResponse.success(null);
    }

    /**
     * 归档运行环境（{@code archived_at} 单列写入）：归档后不可被新 Session 引用
     * （创建 Session 指定已归档环境返回 404）；仍被未删除会话或未归档调度器引用时 409。
     */
    @PostMapping("/{environmentId}/archive")
    public ApiResponse<EnvironmentResponse> archive(@PathVariable String environmentId) {
        return ApiResponse.success(EnvironmentResponseConvert.INSTANCE.toResponse(
                applicationService.archive(environmentId)));
    }
}