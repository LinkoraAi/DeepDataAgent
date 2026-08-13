package com.linkroa.deepdataagent.datasource.controller.request;

public record ApiAuthConfigRequest(
    String authType,
    String username,
    String password
) {}
