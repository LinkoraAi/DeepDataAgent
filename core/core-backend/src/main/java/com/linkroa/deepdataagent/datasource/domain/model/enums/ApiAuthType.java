package com.linkroa.deepdataagent.datasource.domain.model.enums;

/**
 * API认证类型枚举
 */
public enum ApiAuthType {

    NO_AUTH,
    BASIC_AUTH;

    /**
     * 从请求字符串解析认证类型
     * 支持 "basic", "basic_auth", "no_auth" 等格式
     */
    public static ApiAuthType fromRequestString(String authType) {
        if (authType == null) return NO_AUTH;
        return switch (authType.toLowerCase()) {
            case "basic", "basic_auth" -> BASIC_AUTH;
            default -> NO_AUTH;
        };
    }
}
