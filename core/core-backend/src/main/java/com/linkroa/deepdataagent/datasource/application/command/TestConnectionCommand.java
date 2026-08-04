package com.linkroa.deepdataagent.datasource.application.command;

import java.util.Map;

/**
 * 测试数据源连接命令
 */
public record TestConnectionCommand(
        Long id,
        String type,
        String subType,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        String schema,
        String apiUrl,
        String apiMethod,
        Map<String, String> apiHeaders,
        Map<String, String> apiParams,
        String apiBody,
        String apiBodyType,
        String apiAuthType,
        String apiAuthUsername,
        String apiAuthPassword,
        Integer apiTimeout,
        String apiJsonPath
) {
}
