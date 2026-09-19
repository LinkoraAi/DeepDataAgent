package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import com.linkroa.deepdataagent.agent.application.service.DeploymentTriggerResult;
import com.linkroa.deepdataagent.agent.controller.request.TriggerDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentTriggerResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度器 webhook 回调 REST 控制器（公开端点，<b>免 JWT</b>）。
 *
 * <p>对齐 Managed Agents 语义：以路径中的 {@code webhook_token} 触发开放 webhook 的调度器，
 * MUST NOT 要求用户 JWT；暂停 / 已归档 / 未知 token 与不存在不可区分（统一 404，
 * 不泄露调度器存在性）。公开路径放行见 {@code JwtAuthenticationFilter} 的
 * {@code /api/v1/cloud/webhook/} 前缀。</p>
 */
@RestController
@RequestMapping(path = "/cloud/webhook/deployments", version = ApiVersionConstants.CURRENT_API_VERSION)
public class DeploymentWebhookController {

    @Resource
    private DeploymentApplicationService applicationService;

    /**
     * webhook 触发调度器：按路径 token 定位可触发调度器，新建打标 Session 跑 turn。
     *
     * @param webhookToken 路径中的 webhook token
     * @param request      触发消息（可空）
     * @return 触发结果（调度器 ID + 新建会话 ID）
     */
    @PostMapping("/{webhookToken}/trigger")
    public ApiResponse<DeploymentTriggerResponse> triggerByWebhook(@PathVariable String webhookToken,
                                                                   @RequestBody(required = false) TriggerDeploymentRequest request) {
        DeploymentTriggerResult result = applicationService.triggerByWebhookToken(webhookToken,
                request == null ? null : request.input());
        return ApiResponse.success(new DeploymentTriggerResponse(result.deploymentId(), result.runId(), result.sessionId()));
    }
}