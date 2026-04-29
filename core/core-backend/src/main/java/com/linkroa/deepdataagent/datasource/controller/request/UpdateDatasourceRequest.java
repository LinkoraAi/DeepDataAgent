package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateDatasourceRequest(
    @NotNull(message = "数据源ID不能为空")
    Long id,
    @NotBlank(message = "数据源名称不能为空")
    String name,
    String description
) {}
