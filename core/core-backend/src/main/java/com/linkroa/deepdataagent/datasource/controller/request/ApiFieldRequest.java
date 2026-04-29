package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;

public record ApiFieldRequest(
    @NotBlank(message = "字段原始名称不能为空")
    String originalName,
    
    String displayName,
    String jsonPath,
    
    @NotBlank(message = "字段类型不能为空")
    String fieldType,
    
    String description
) {}
