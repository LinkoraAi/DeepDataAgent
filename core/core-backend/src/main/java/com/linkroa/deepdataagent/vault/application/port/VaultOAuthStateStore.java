package com.linkroa.deepdataagent.vault.application.port;

import com.linkroa.deepdataagent.vault.application.dto.VaultOAuthStateDTO;

import java.time.Duration;
import java.util.Optional;

/**
 * OAuth state 一次性存储出站端口（Redis 承载，见 design D7）。
 *
 * <p>「一次性」是本端口的语义核心：{@link #consume} MUST 为<b>原子读删</b>——
 * 已被消费、已过期与从未存在三种情况对调用方不可区分（一律返回空），重放天然被拒。
 * 过期回收由 Redis 键 TTL 承担，无需清理任务。</p>
 */
public interface VaultOAuthStateStore {

    /**
     * 保存一次性 state 载荷（覆盖同值写入，TTL 到期即失效）。
     *
     * @param state 不透明 state 值（键段）
     * @param data  回调换码所需上下文
     * @param ttl   存活时长
     */
    void save(String state, VaultOAuthStateDTO data, Duration ttl);

    /**
     * 原子消费 state（读删一体）：返回载荷同时即删除该键，重放必空。
     *
     * @param state 回调携带的 state 值
     * @return 载荷（不存在 / 已过期 / 已被消费一律为空）
     */
    Optional<VaultOAuthStateDTO> consume(String state);
}