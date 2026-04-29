package com.linkroa.deepdataagent.datasource.application.command;

public record JdbcConfigCommand(
    String host,
    Integer port,
    String database,
    String username,
    String password
) {}
