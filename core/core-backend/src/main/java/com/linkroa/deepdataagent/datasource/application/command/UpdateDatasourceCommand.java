package com.linkroa.deepdataagent.datasource.application.command;

/**
 * 更新数据源命令
 */
public record UpdateDatasourceCommand(
    Long id,
    String name,
    String description,
    JdbcConfigCommand jdbcConfig
) {}
