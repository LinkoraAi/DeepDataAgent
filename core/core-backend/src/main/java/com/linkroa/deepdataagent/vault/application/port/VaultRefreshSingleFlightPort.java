package com.linkroa.deepdataagent.vault.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 凭证刷新单飞出站端口（Redis 承载，见 design D6）。
 *
 * <p>与 DB 条件更新（CAS）分工：CAS 保证「只有一个实例的写入胜出」，单飞键保证
 * 「只有一个实例真正发出刷新请求」——两者缺一不可：若仅靠 CAS，两实例都会向服务商
 * 发出刷新，服务商若轮换 refresh token，后到达者会用已作废的值，导致凭证失效。</p>
 *
 * <p>获取语义为 {@code SET NX PX}（同键并发只有一个成功）；释放为「值等于本次持有令牌」
 * 才删（防止 TTL 过期后误删他人持有的键）。</p>
 */
public interface VaultRefreshSingleFlightPort {

    /**
     * 尝试取得某凭证的刷新单飞持有权。
     *
     * @param credentialId 凭证业务 ID
     * @param ttl          持有权存活时长（覆盖一次刷新出网的最坏耗时）
     * @return 持有令牌（未取得时为 {@link Optional#empty()}，调用方按「他人正在刷新」处理）
     */
    Optional<String> tryAcquire(String credentialId, Duration ttl);

    /**
     * 释放持有权（仅当仍由本次持有令牌持有；已过期被他人接管时不删除）。
     *
     * @param credentialId 凭证业务 ID
     * @param token        本次 acquire 返回的持有令牌
     */
    void release(String credentialId, String token);
}