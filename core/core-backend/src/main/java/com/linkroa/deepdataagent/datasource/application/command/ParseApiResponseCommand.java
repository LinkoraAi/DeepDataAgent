package com.linkroa.deepdataagent.datasource.application.command;

import java.util.Map;

/**
 * 解析API响应命令对象
 * <p>用于application层接收解析请求参数。</p>
 *
 * @author system
 * @since 2026-05-12
 */
public record ParseApiResponseCommand(
    Long connectionId,
    String apiUrl,
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
    String rootPath
) {
}