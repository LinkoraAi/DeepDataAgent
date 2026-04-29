package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 解析API响应请求对象
 * <p>用于前端发送测试请求配置，后端解析API响应并提取字段列表。
 * 支持两种场景：
 * 1. 新建数据源时：直接传入完整配置（apiUrl等），connectionId可为空
 * 2. 为已有数据源添加表时：传入connectionId，从数据库获取基础配置</p>
 *
 * @author system
 * @since 2026-05-12
 */
public record ParseApiResponseRequest(
    Long connectionId,
    
    @NotBlank(message = "API请求地址不能为空")
    String apiUrl,
    
    @NotBlank(message = "请求路径不能为空")
    String path,
    
    String method,
    Map<String, String> headers,
    Map<String, String> params,
    String body,
    String bodyType,
    String authType,
    String authToken,
    String authUsername,
    String authPassword,
    Integer timeout,
    Integer retryCount,
    
    @NotBlank(message = "JsonPath根路径不能为空")
    String rootPath,
    ApiPaginationConfigRequest paginationConfig
) {}