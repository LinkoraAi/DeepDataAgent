package com.linkroa.deepdataagent.datasource.application.command;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;

import java.util.List;

public record CreateDatasourceCommand(
    String name,
    DatasourceType type,
    JdbcType subType,
    String description,

    JdbcConfigCommand jdbcConfig,
    List<ApiSchemaCommand> apiSchemas
) {}
