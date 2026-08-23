package com.linkroa.deepdataagent.runtime.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 人工确认指令请求（对齐 HITL 确认 / 拒绝端点）。
 *
 * @param confirmed true=确认并恢复执行；false=拒绝并终止当前执行
 */
public record ResolveHumanConfirmationRequest(
        @NotNull(message = "确认结果不能为空")
        Boolean confirmed
) {
}