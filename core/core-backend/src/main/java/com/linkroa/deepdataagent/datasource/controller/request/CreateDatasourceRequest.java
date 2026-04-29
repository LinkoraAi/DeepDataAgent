package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建数据源请求
 */
public record CreateDatasourceRequest(
    @NotBlank(message = "数据源名称不能为空")
    String name,

    @NotBlank(message = "数据源类型不能为空")
    String type,

    String subType,

    String description,

    JdbcConfigRequest jdbcConfig,
    List<ApiSchemaRequest> apiSchemas
) {}
