package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateCommentRequest(
        @NotNull(message = "ID不能为空")
        Long id,
        @NotBlank(message = "注释内容不能为空")
        String comment
) {
}
