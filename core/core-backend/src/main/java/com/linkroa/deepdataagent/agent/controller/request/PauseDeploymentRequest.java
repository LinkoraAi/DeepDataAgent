package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.Size;

/**
 * 暂停调度器请求（可空 body）。
 *
 * @param reason 暂停原因（可空，随调度器落库展示；长度对齐 paused_reason 列宽 VARCHAR(512)）
 */
public record PauseDeploymentRequest(
        @Size(max = 512, message = "暂停原因不能超过512个字符")
        String reason
) {
}
