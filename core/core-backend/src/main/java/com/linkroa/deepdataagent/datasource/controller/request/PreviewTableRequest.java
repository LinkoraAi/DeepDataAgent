package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PreviewTableRequest(
    @NotNull(message = "数据源连接ID不能为空")
    Long connectionId,
    @NotBlank(message = "表名不能为空")
    String tableName,
    @NotBlank(message = "数据源类型不能为空")
    String type,
    Integer limit
) {
}
