package com.linkroa.deepdataagent.runtime.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 协调租约 Lua 脚本加载器（move-coordination-leases-to-redis D2）。
 *
 * <p>启动时从 {@code classpath:lua/runtime/} 读入五段脚本原文（UTF-8），供
 * {@code RedisCoordLeaseStore} 经 {@code RScript} EVALSHA 执行。脚本置于 resources 而非内联，
 * 保持 Lua 逻辑可读、可 grep、可跨实现复用（退路 StringRedisTemplate + DefaultRedisScript 共用同批脚本）。
 * 任一脚本缺失即启动失败（fail-fast）。</p>
 */
@Component
public class CoordLeaseLuaScripts {

    /** turn 抢占：SET NX PX + SADD owner 索引原子同写。 */
    public static final String TURN_ACQUIRE = "lua/runtime/coord-turn-acquire.lua";
    /** turn 续约：GET==owner 则 PEXPIRE。 */
    public static final String TURN_RENEW = "lua/runtime/coord-turn-renew.lua";
    /** turn owner-scoped 释放：GET==owner 则 DEL + SREM。 */
    public static final String TURN_RELEASE_OWNED = "lua/runtime/coord-turn-release-owned.lua";
    /** fire 抢占：单 key SET NX PX（无索引）。 */
    public static final String FIRE_ACQUIRE = "lua/runtime/coord-fire-acquire.lua";
    /** fire owner-scoped 释放：GET==owner 则 DEL（无索引）。 */
    public static final String FIRE_RELEASE_OWNED = "lua/runtime/coord-fire-release-owned.lua";

    private String turnAcquireScript;
    private String turnRenewScript;
    private String turnReleaseOwnedScript;
    private String fireAcquireScript;
    private String fireReleaseOwnedScript;

    @PostConstruct
    void load() {
        turnAcquireScript = read(TURN_ACQUIRE);
        turnRenewScript = read(TURN_RENEW);
        turnReleaseOwnedScript = read(TURN_RELEASE_OWNED);
        fireAcquireScript = read(FIRE_ACQUIRE);
        fireReleaseOwnedScript = read(FIRE_RELEASE_OWNED);
    }

    /** turn 抢占脚本原文。 */
    public String turnAcquire() {
        return turnAcquireScript;
    }

    /** turn 续约脚本原文。 */
    public String turnRenew() {
        return turnRenewScript;
    }

    /** turn owner-scoped 释放脚本原文。 */
    public String turnReleaseOwned() {
        return turnReleaseOwnedScript;
    }

    /** fire 抢占脚本原文。 */
    public String fireAcquire() {
        return fireAcquireScript;
    }

    /** fire owner-scoped 释放脚本原文。 */
    public String fireReleaseOwned() {
        return fireReleaseOwnedScript;
    }

    /** 读 classpath 脚本为 UTF-8 文本；缺失即抛（启动期暴露配置错误）。 */
    static String read(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("协调租约 Lua 脚本加载失败: " + classpathLocation, ex);
        }
    }
}
