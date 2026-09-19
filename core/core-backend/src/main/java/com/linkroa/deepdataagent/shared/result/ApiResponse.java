package com.linkroa.deepdataagent.shared.result;

import org.apache.commons.lang3.StringUtils;

/**
 * 统一 API 响应包装类。
 *
 * @param success 是否成功
 * @param code    响应码，为空时按 success 自动填充默认值（成功 200 / 失败 500）
 * @param message 响应消息，为空时按 success 自动填充默认值（成功「操作成功」/ 失败「操作失败」）
 * @param data    响应数据
 * @param <T>     数据类型
 */
public record ApiResponse<T>(boolean success, String code, String message, T data) {

    /**
     * 紧凑构造器：当 code 或 message 为空时，根据 success 自动填充默认值。
     */
    public ApiResponse {
        if (StringUtils.isBlank(code)) {
            code = success ? "200" : "500";
        }
        if (StringUtils.isBlank(message)) {
            message = success ? "操作成功" : "操作失败";
        }
    }

    /**
     * 构造成功响应：{@code success=true}、code {@code 200}、message「操作成功」。
     *
     * @param data 响应数据（可为 null，如删除类接口）
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "200", "操作成功", data);
    }

    /**
     * 构造失败响应：{@code success=false}，不携带数据。
     *
     * @param code 错误码
     * @param message 错误消息
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    /**
     * 构造携带数据的失败响应（回传错误明细或部分结果）。
     *
     * @param code 错误码
     * @param message 错误消息
     * @param data 附加数据
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(false, code, message, data);
    }
}
