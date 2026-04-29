package com.linkroa.deepdataagent.datasource.application.command;

public record ApiFieldCommand(
    String originalName,
    String displayName,
    String jsonPath,
    String fieldType,
    String description
) {}
