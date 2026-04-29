package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateDatasourceRequest(
        @NotNull(message = "数据源ID不能为空")
        Long id,
        @NotBlank(message = "数据源名称不能为空")
        String name,
        @NotBlank(message = "数据源类型不能为空")
        String type,
        @NotBlank(message = "数据源子类型不能为空")
        String subType,

        String description,
        
        JdbcConfigRequest jdbcConfig,
        ApiConfigRequest apiConfig
) {
}
