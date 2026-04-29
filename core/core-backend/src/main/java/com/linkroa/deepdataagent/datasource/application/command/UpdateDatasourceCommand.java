package com.linkroa.deepdataagent.datasource.application.command;

public record UpdateDatasourceCommand(
    Long id,
    String name,
    String description
) {}
