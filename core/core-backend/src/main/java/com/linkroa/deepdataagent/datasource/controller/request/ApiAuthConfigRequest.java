package com.linkroa.deepdataagent.datasource.controller.request;

public record ApiAuthConfigRequest(
    String type,
    String token,
    String username,
    String password
) {}
