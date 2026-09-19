package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import com.linkroa.deepdataagent.agent.application.service.DeploymentTriggerResult;
import com.linkroa.deepdataagent.agent.controller.request.TriggerDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentTriggerResponse;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeploymentWebhookController} webhook 触发接口单测：锁定「路径 token 透传 +
 * 触发结果信封 {@code data{deployment_id, run_id, session_id}}」契约；空请求体收敛为 null input，
 * 未知 / 暂停 token 由服务层抛 404 逐层透传（公开端点，免 JWT，不泄露存在性）。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentWebhookControllerTest {

    @Mock
    private DeploymentApplicationService applicationService;

    @InjectMocks
    private DeploymentWebhookController controller;

    @Test
    void should_triggerByToken_when_triggerByWebhook_given_validTokenAndInput() {
        // given
        when(applicationService.triggerByWebhookToken("tok-1", "呼叫"))
                .thenReturn(new DeploymentTriggerResult("dep-1", "drun_1", "s-1"));

        // when（公开路径：/api/v1/cloud/webhook/deployments/{token}/trigger）
        ApiResponse<DeploymentTriggerResponse> response =
                controller.triggerByWebhook("tok-1", new TriggerDeploymentRequest("呼叫"));

        // then（触发结果信封：调度器 ID + 运行记录 ID + 每次触发独立新建会话 ID）
        assertTrue(response.success());
        assertEquals("dep-1", response.data().deployment_id());
        assertEquals("drun_1", response.data().run_id());
        assertEquals("s-1", response.data().session_id());
        verify(applicationService).triggerByWebhookToken("tok-1", "呼叫");
    }

    @Test
    void should_delegateNullInput_when_triggerByWebhook_given_nullBody() {
        // given（请求体可空：缺省时服务层回退首批事件合成或默认调度提示）
        when(applicationService.triggerByWebhookToken("tok-1", null))
                .thenReturn(new DeploymentTriggerResult("dep-1", "drun_1", "s-1"));

        // when
        ApiResponse<DeploymentTriggerResponse> response = controller.triggerByWebhook("tok-1", null);

        // then
        assertTrue(response.success());
        assertEquals("dep-1", response.data().deployment_id());
        verify(applicationService).triggerByWebhookToken(eq("tok-1"), eq(null));
    }

    @Test
    void should_propagateNotFound_when_triggerByWebhook_given_unknownToken() {
        // given（未知 / 禁用 token 与不存在不可区分：统一 404，避免泄露调度器存在性）
        when(applicationService.triggerByWebhookToken("bad-token", null))
                .thenThrow(new ResourceNotFoundException("调度器不存在"));

        // when & then
        assertThrows(ResourceNotFoundException.class,
                () -> controller.triggerByWebhook("bad-token", null));
    }
}