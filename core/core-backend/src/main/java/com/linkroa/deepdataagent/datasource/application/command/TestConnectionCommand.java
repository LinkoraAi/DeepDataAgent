package com.linkroa.deepdataagent.datasource.application.command;

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
        String apiUrl,
        String apiMethod,
        String apiAuthType,
        String apiAuthUsername,
        String apiAuthPassword,
        String apiAuthToken,
        Integer apiTimeout,
        String apiJsonPath
) {
}
