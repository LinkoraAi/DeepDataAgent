package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ListTablesRequest(
        @NotNull(message = "数据源连接ID不能为空")
        Long connectionId,
        @NotBlank(message = "数据源类型不能为空")
        String type,
        String keyword,
        Integer page,
        Integer size
) {
}
