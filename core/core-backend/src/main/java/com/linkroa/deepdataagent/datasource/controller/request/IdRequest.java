package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotNull;

public record IdRequest(
        @NotNull(message = "ID不能为空")
        Long id
) {
}
