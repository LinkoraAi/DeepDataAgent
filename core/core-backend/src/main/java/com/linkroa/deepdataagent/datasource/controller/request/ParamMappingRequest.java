package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 动态参数映射请求对象
 */
public record ParamMappingRequest(
    @NotBlank(message = "参数名称不能为空")
    String paramName,
    @NotBlank(message = "参数位置不能为空")
    String paramLocation,
    @NotBlank(message = "参数获取路径不能为空")
    String jsonPath
) {}
