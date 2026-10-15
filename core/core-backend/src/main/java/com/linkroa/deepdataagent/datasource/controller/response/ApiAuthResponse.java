package com.linkroa.deepdataagent.datasource.controller.response;

/**
 * API 数据表鉴权响应体（**不回显密码**）。
 *
 * @param authType            鉴权类型（{@code NO_AUTH} / {@code BASIC_AUTH}）
 * @param username            鉴权用户名
 * @param credentialConfigured 是否已配置凭证（密码非空白时为 true，供前端展示"已配置/未配置"）
 * @author DeepDataAgent
 */
public record ApiAuthResponse(
        String authType,
        String username,
        boolean credentialConfigured
) {
}