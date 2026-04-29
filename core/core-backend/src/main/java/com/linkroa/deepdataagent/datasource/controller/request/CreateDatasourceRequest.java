package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

public record CreateDatasourceRequest(
    @NotBlank(message = "数据源名称不能为空")
    String name,
    
    @NotBlank(message = "数据源类型不能为空")
    String type,

    @NotBlank(message = "数据源子类型不能为空")
    String subType,

    String description,
    
    JdbcConfigRequest jdbcConfig,
    ApiConfigRequest apiConfig
) {}
