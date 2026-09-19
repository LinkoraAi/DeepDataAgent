package com.linkroa.deepdataagent.shared.idempotency;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 幂等记录（技术性横切模型，非业务聚合）。
 *
 * <p>承载「同一归属用户 + 同一作用域 + 同一 {@code Idempotency-Key}」的首次提交结果
 * （HTTP 状态码 + 响应体原文），供重复提交时原样回放；{@link #requestHash} 为首个请求体
 * 的 SHA-256（十六进制），用于同键异体冲突判定（键相同但请求体不同一律拒绝）。</p>
 *
 * @param idempotencyKey 请求 {@code Idempotency-Key} 头原值
 * @param ownerId        归属用户 ID（幂等键按用户隔离）
 * @param scope          作用域（如 {@code post_agents}）
 * @param requestHash    首次请求体 SHA-256（hex）
 * @param responseStatus 首次响应 HTTP 状态码
 * @param responseBody   首次响应体（JSON 字符串）
 */
public record IdempotencyRecord(
        String idempotencyKey,
        Long ownerId,
        String scope,
        String requestHash,
        int responseStatus,
        String responseBody
) {

    /**
     * 紧凑构造器：不变量校验与归一。
     */
    public IdempotencyRecord {
        if (StringUtils.isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("幂等键不能为空");
        }
        Objects.requireNonNull(ownerId, "幂等记录归属用户不能为空");
        if (StringUtils.isBlank(scope)) {
            throw new IllegalArgumentException("幂等作用域不能为空");
        }
        if (StringUtils.isBlank(requestHash)) {
            throw new IllegalArgumentException("请求体摘要不能为空");
        }
        if (responseBody == null) {
            responseBody = "";
        }
    }
}