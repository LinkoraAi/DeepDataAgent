package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record JdbcConfigRequest(
    @NotBlank(message = "主机地址不能为空")
    String host,

    @NotNull(message = "端口号不能为空")
    Integer port,
    
    @NotBlank(message = "数据库名称不能为空")
    String database,
    
    @NotBlank(message = "用户名不能为空")
    String username,
    
    @NotBlank(message = "密码不能为空")
    String password
) {}
