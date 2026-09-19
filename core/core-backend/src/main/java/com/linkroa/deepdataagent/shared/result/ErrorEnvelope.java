package com.linkroa.deepdataagent.shared.result;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

/**
 * 统一错误信封（api-conventions 通用规范：全部错误响应 MUST 使用本形状）。
 * <p>JSON 形状：{@code {"error": {"type", "message"}, "request_id": "...", "type": "error"}}，
 * 顶层 {@code request_id} 为一次性随机标识（工单追溯用），顶层 {@code type} 恒为 {@code error}；
 * 错误响应的 HTTP 状态码 MUST 与语义匹配（400/401/403/404/409/429/500），
 * MUST NOT 再以 HTTP 200 包装业务错误。成功响应仍走
 * {@link ApiResponse} 包装（本期不改写成功侧形状）。</p>
 *
 * @param error     错误体（机读类型 + 人读消息）
 * @param requestId 请求追踪 ID（UUID，供工单 / 日志追溯）
 * @param type      信封类型，恒为 {@code error}
 */
public record ErrorEnvelope(ErrorBody error, @JsonProperty("request_id") String requestId, String type) {

    /** 信封类型常量（顶层 type 恒为 error）。 */
    public static final String ENVELOPE_TYPE = "error";

    public ErrorEnvelope {
        Objects.requireNonNull(error, "error 不能为空");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        if (type == null || type.isBlank()) {
            type = ENVELOPE_TYPE;
        }
    }

    /**
     * 装配错误信封（request_id 自动生成 UUID）。
     *
     * @param errorType 机器可读错误类别
     * @param message   人读错误消息
     * @return 错误信封
     */
    public static ErrorEnvelope of(ErrorType errorType, String message) {
        return new ErrorEnvelope(new ErrorBody(errorType.value(), message), null, ENVELOPE_TYPE);
    }

    /**
     * 错误体。
     *
     * @param type    机器可读错误类别（见 {@link ErrorType}，客户端 MUST 容忍未知值）
     * @param message 人读错误消息
     */
    public record ErrorBody(String type, String message) {

        public ErrorBody {
            Objects.requireNonNull(type, "error.type 不能为空");
        }
    }
}
