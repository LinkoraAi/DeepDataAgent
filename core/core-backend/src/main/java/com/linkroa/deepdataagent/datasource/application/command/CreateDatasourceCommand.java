package com.linkroa.deepdataagent.datasource.application.command;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;

public record CreateDatasourceCommand(
    String name,
    DatasourceType type,
    JdbcType subType,
    String description,
    
    JdbcConfigCommand jdbcConfig,
    ApiConfigCommand apiConfig
) {}
