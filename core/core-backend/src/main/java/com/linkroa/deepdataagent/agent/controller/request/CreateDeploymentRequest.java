package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建部署请求（激活 / 回滚）
 */
public record CreateDeploymentRequest(

        @NotBlank(message = "Agent ID不能为空")
        String agentId,

        @NotNull(message = "部署目标版本号不能为空")
        Integer versionNumber
) {
}