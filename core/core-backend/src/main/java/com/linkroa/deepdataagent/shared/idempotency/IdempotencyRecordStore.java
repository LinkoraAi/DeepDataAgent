package com.linkroa.deepdataagent.shared.idempotency;

import java.util.Optional;

/**
 * 幂等记录存储端口（技术能力，按 owner 隔离）。
 *
 * <p>键语义：{@code (ownerId, scope, idempotencyKey)} 三元组唯一；存储实现须对
 * 并发同键写入保持「先落库者为准」的收敛语义（唯一索引兜底，后者静默丢弃）。</p>
 */
public interface IdempotencyRecordStore {

    /**
     * 按 owner + 作用域 + 幂等键读取首次提交记录。
     */
    Optional<IdempotencyRecord> find(Long ownerId, String scope, String idempotencyKey);

    /**
     * 保存首次提交记录（并发同键冲突时静默忽略，以先落库者为准）。
     */
    void save(IdempotencyRecord record);
}