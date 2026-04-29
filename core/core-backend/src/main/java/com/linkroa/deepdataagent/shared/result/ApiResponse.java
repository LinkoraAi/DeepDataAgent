package com.linkroa.deepdataagent.shared.result;

/**
 * 统一API响应结果封装
 * <p>包含成功/失败状态、业务错误码、错误消息和响应数据</p>
 *
 * @param <T> 响应数据类型
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    public ApiResponse {
        if (code == null) {
            code = success ? "200" : "500";
        }
        if (message == null) {
            message = success ? "操作成功" : "操作失败";
        }
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "200", "操作成功", data);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(false, code, message, data);
    }
}
