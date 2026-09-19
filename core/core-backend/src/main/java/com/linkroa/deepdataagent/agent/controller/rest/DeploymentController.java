package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.convert.DeploymentCommandConvert;
import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import com.linkroa.deepdataagent.agent.application.service.DeploymentTriggerResult;
import com.linkroa.deepdataagent.agent.controller.convert.DeploymentResponseConvert;
import com.linkroa.deepdataagent.agent.controller.request.CreateDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.request.PauseDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.request.TriggerDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentRunResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentTriggerResponse;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度器管理 REST 控制器（统一前缀 {@code /api/v1/cloud/deployments}，6.5 管理面）。
 *
 * <p>Deployment 为「调度器」资源：创建（调度配置 schedule + webhook 开关 + 触发会话挂载材料，
 * Agent 版本创建时解析并固定）、游标列表（status / agent_id / created_at 区间过滤 +
 * include_archived）、详情、merge-patch 更新（PATCH，可替换 / 显式 null 清空）、
 * 暂停（可携带原因）/ 恢复 / 归档、手动运行 run（新建打标 Session 跑 turn 并落运行记录）、
 * 运行记录游标查询（单调度器与全局两种作用域 + 详情）。
 * webhook 回调路径（免 JWT）见 {@link DeploymentWebhookController}。</p>
 *
 * <p>路由说明：{@code GET /runs}、{@code GET /runs/{runId}} 为字面段路径，
 * Spring MVC 模式匹配优先于 {@code /{deploymentId}} 模板，无冲突。</p>
 */
@RestController
@RequestMapping(path = "/cloud/deployments", version = ApiVersionConstants.CURRENT_API_VERSION)
public class DeploymentController {

    @Resource
    private DeploymentApplicationService applicationService;

    /**
     * 创建调度器（name / agent_id 缺失由请求校验拒绝；cron 表达式与挂载载荷合法性由
     * 领域值对象与命令装配校验拒绝；省略版本时解析激活版本并固定）。
     */
    @PostMapping
    public ApiResponse<DeploymentResponse> create(@Valid @RequestBody CreateDeploymentRequest request) {
        CreateDeploymentCommand command = DeploymentCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    /**
     * 游标分页列出调度器（6.5 管理面 Cursor 约定，创建时间降序）。
     * <p>{@code status} 取 active/paused（非法 → 400）；{@code agent_id} 指向 Agent 过滤
     * （非 owner → 404）；{@code created_at[gte]} / {@code created_at[lte]} 为 ISO-8601
     * 时间区间（非法 → 400）；{@code include_archived=true} 时包含归档（缺省排除）；
     * {@code limit / after_id / before_id} 统一游标约定（游标调度器不存在 / 非本人 → 404）。</p>
     */
    @GetMapping
    public ApiResponse<CursorPage<DeploymentResponse>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "created_at[gte]", required = false) String createdAfter,
            @RequestParam(name = "created_at[lte]", required = false) String createdBefore,
            @RequestParam(name = "include_archived", required = false) String includeArchived,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<Deployment> page = applicationService.list(DeploymentCommandConvert.INSTANCE
                .toListQuery(AuthContext.requireUserId(), status, agentId, createdAfter, createdBefore,
                        includeArchived, limit, afterId, beforeId));
        return ApiResponse.success(page.map(DeploymentResponseConvert.INSTANCE::toResponse));
    }

    /**
     * 全局运行记录游标列表（跨全部调度器，触发时间降序；经归属子查询过滤）。
     * <p>字面段 {@code /runs} 优先于 {@code /{deploymentId}} 模板匹配。
     * {@code created_at[gte]/[lte]} 时间区间过滤 + 统一游标约定。</p>
     */
    @GetMapping("/runs")
    public ApiResponse<CursorPage<DeploymentRunResponse>> listAllRuns(
            @RequestParam(name = "created_at[gte]", required = false) String createdAfter,
            @RequestParam(name = "created_at[lte]", required = false) String createdBefore,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<DeploymentRun> page = applicationService.listRuns(DeploymentCommandConvert.INSTANCE
                .toListRunsQuery(null, AuthContext.requireUserId(), createdAfter, createdBefore,
                        limit, afterId, beforeId));
        return ApiResponse.success(page.map(DeploymentResponseConvert.INSTANCE::toRunResponse));
    }

    /**
     * 全局运行记录详情（跨全部调度器）：非本人所属 → 404（不泄露存在性）。
     */
    @GetMapping("/runs/{runId}")
    public ApiResponse<DeploymentRunResponse> getRunGlobal(@PathVariable String runId) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE
                .toRunResponse(applicationService.getRunGlobal(runId)));
    }

    /**
     * 调度器详情（含已归档记录）。
     */
    @GetMapping("/{deploymentId}")
    public ApiResponse<DeploymentResponse> detail(@PathVariable String deploymentId) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.get(deploymentId)));
    }

    /**
     * merge-patch 部分更新调度器（6.5 管理面）：仅被提供的字段更新、缺省字段保留、
     * 显式置 null 的字段清空（schedule 清空后转仅手动/webhook 触发并重算到期时间；
     * metadata 为浅合并增量、键值 null 删除该键）；未知键与不可调绑定字段拒绝（400）。
     */
    @PatchMapping("/{deploymentId}")
    public ApiResponse<DeploymentResponse> update(@PathVariable String deploymentId,
                                                  @RequestBody UpdateDeploymentRequest request) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService
                .update(DeploymentCommandConvert.INSTANCE.toUpdateCommand(deploymentId, request))));
    }

    /**
     * 暂停调度器（{@code status=paused} + 可空原因；暂停期间不被轮询领取、不可手动运行）。
     */
    @PostMapping("/{deploymentId}/pause")
    public ApiResponse<DeploymentResponse> pause(@PathVariable String deploymentId,
                                                 @Valid @RequestBody(required = false) PauseDeploymentRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.pause(deploymentId, reason)));
    }

    /**
     * 恢复调度器（{@code status=active}，到期时间按恢复时刻重算；已归档调度器不可恢复）。
     */
    @PostMapping("/{deploymentId}/unpause")
    public ApiResponse<DeploymentResponse> unpause(@PathVariable String deploymentId) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.unpause(deploymentId)));
    }

    /**
     * 归档调度器（{@code archived_at} + {@code status=paused} 双写，不再触发、列表不可见）。
     */
    @PostMapping("/{deploymentId}/archive")
    public ApiResponse<DeploymentResponse> archive(@PathVariable String deploymentId) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE.toResponse(applicationService.archive(deploymentId)));
    }

    /**
     * 手动运行调度器（run，受保护端点，需用户 JWT；6.5 由 trigger 更名）：
     * 新建打标 Session 跑 turn 并落运行记录，每次运行独立新会话（绝不复用），
     * 随后刷新最近运行信息；paused / 已归档调度器触发被拒（404）。
     */
    @PostMapping("/{deploymentId}/run")
    public ApiResponse<DeploymentTriggerResponse> run(@PathVariable String deploymentId,
                                                      @RequestBody(required = false) TriggerDeploymentRequest request) {
        DeploymentTriggerResult result = applicationService.run(deploymentId, request == null ? null : request.input());
        return ApiResponse.success(new DeploymentTriggerResponse(result.deploymentId(), result.runId(), result.sessionId()));
    }

    /**
     * 单调度器运行记录游标列表（触发时间降序 + 时间区间过滤 + 统一游标约定）。
     */
    @GetMapping("/{deploymentId}/runs")
    public ApiResponse<CursorPage<DeploymentRunResponse>> listRuns(
            @PathVariable String deploymentId,
            @RequestParam(name = "created_at[gte]", required = false) String createdAfter,
            @RequestParam(name = "created_at[lte]", required = false) String createdBefore,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<DeploymentRun> page = applicationService.listRuns(DeploymentCommandConvert.INSTANCE
                .toListRunsQuery(deploymentId, AuthContext.requireUserId(), createdAfter, createdBefore,
                        limit, afterId, beforeId));
        return ApiResponse.success(page.map(DeploymentResponseConvert.INSTANCE::toRunResponse));
    }

    /**
     * 单调度器运行记录详情（记录不属于该调度器 → 404）。
     */
    @GetMapping("/{deploymentId}/runs/{runId}")
    public ApiResponse<DeploymentRunResponse> getRun(@PathVariable String deploymentId, @PathVariable String runId) {
        return ApiResponse.success(DeploymentResponseConvert.INSTANCE
                .toRunResponse(applicationService.getRun(deploymentId, runId)));
    }
}
