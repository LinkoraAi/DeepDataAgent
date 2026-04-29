package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateApiSchemaRequest(
    @NotNull(message = "connectionId不能为空") Long connectionId,
    @NotNull(message = "schema不能为空") @Valid ApiSchemaRequest schema
) {}
