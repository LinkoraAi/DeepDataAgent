package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 前置操作试跑请求体（返回原始响应文本供配置调试）。
 *
 * @param preOperationConfig 待试跑的前置操作配置（必填）
 * @param authType           试跑携带的鉴权类型（可空）
 * @param authUsername       试跑携带的鉴权用户名
 * @param authPassword       试跑携带的鉴权密码
 * @author DeepDataAgent
 */
public record PreOperationTestRequest(
        @NotNull(message = "前置操作配置不能为空")
        @Valid
        PreOperationConfigRequest preOperationConfig,

        String authType,
        String authUsername,
        String authPassword
) {
}