package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * API认证配置响应
 */
public record ApiAuthConfigResponse(
    String authType,
    String username
) {}
