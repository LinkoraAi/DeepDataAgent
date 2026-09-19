package com.linkroa.deepdataagent.shared.result;

/**
 * 统一错误信封的机器可读错误类别（api-conventions 通用规范观测集合）。
 * <p>客户端以 HTTP 状态码 + {@code message} 呈现为准，{@code type} 仅作机读分类；
 * 本系统规范 409 默认 {@link #CONFLICT_ERROR}，唯一特例为会话忙 / 会话资源冲突路径的
 * {@link #INVALID_REQUEST_ERROR}（契约明文要求，见 spec「会话忙冲突的错误类型」）。</p>
 */
public enum ErrorType {

    /** 请求非法（400）：参数校验失败、字段超长、枚举非法、JSON 不可解析等。 */
    INVALID_REQUEST_ERROR("invalid_request_error"),

    /** 认证失败（401）：缺失 / 无效 / 过期凭证。 */
    AUTHENTICATION_ERROR("authentication_error"),

    /** 权限拒绝（403）：资源存在但操作被策略禁止。 */
    PERMISSION_ERROR("permission_error"),

    /** 资源不存在（404）：不存在或已删除。 */
    NOT_FOUND_ERROR("not_found_error"),

    /** 并发 / 约束冲突（409）：OCC 版本不匹配、删除被引用资源、唯一键撞库等。 */
    CONFLICT_ERROR("conflict_error"),

    /** 请求载荷超限（413）：请求体 / multipart 包体超过服务端限额。 */
    REQUEST_TOO_LARGE_ERROR("request_too_large_error"),

    /** 请求过于频繁（429）。 */
    RATE_LIMIT_ERROR("rate_limit_error"),

    /** 服务端错误（500）：未预期异常的兜底类别。 */
    API_ERROR("api_error");

    private final String value;

    ErrorType(String value) {
        this.value = value;
    }

    /** 对外序列化值（snake_case）。 */
    public String value() {
        return value;
    }
}
