package com.linkroa.deepdataagent.shared.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 幂等记录持久化实体（对应 idempotency_record 表）。
 * <p>唯一索引 {@code (owner_id, scope, idempotency_key)}（未删除行）保障并发同键收敛。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("idempotency_record")
public class IdempotencyRecordEntity extends BaseEntity {

    /** 请求 Idempotency-Key 头原值 */
    private String idempotencyKey;
    /** 归属用户 ID（幂等键按用户隔离） */
    private Long ownerId;
    /** 作用域（如 post_agents） */
    private String scope;
    /** 首次请求体 SHA-256（hex） */
    private String requestHash;
    /** 首次响应 HTTP 状态码 */
    private Integer responseStatus;
    /** 首次响应体（JSON 字符串） */
    private String responseBody;
}