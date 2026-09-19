package com.linkroa.deepdataagent.auth.application.port;

/**
 * 已撤销 JWT 标识存储（登出 / 安全事件主动作废 token 用）。
 * <p>进程内出站端口，按 {@code jti} 锚定撤销并保留至原 token 过期；基础设施层以 Redis 实现。</p>
 */
public interface RevokedTokenStore {

    /** 指定 jti 是否已被撤销。 */
    boolean isRevoked(String jti);

    /** 撤销 jti，保留 ttlSeconds 秒（一般取原 token 剩余有效期）。 */
    void revoke(String jti, long ttlSeconds);
}