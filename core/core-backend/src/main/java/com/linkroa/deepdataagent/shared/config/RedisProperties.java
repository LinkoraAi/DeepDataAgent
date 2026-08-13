package com.linkroa.deepdataagent.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis 能力相关配置属性。
 * <p>承载缓存、限流、分布式锁三块能力的关键参数，通过 {@code app.redis.*} 前缀注入，
 * 均提供默认值以支持 Redis 停用场景下的本地运行。</p>
 */
@ConfigurationProperties(prefix = "app.redis")
public class RedisProperties {

    /** Schema 缓存默认有效期 */
    private Duration schemaCacheTtl = Duration.ofSeconds(60);

    /** 模型连通性测试限流固定窗口 */
    private Duration rateLimitWindow = Duration.ofSeconds(5);

    /** 限流窗口内允许的最大请求次数 */
    private int rateLimitMax = 1;

    /** 分布式锁默认持有时间（到期自动释放） */
    private Duration lockLeaseTime = Duration.ofSeconds(10);

    public Duration getSchemaCacheTtl() {
        return schemaCacheTtl;
    }

    public void setSchemaCacheTtl(Duration schemaCacheTtl) {
        this.schemaCacheTtl = schemaCacheTtl;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public int getRateLimitMax() {
        return rateLimitMax;
    }

    public void setRateLimitMax(int rateLimitMax) {
        this.rateLimitMax = rateLimitMax;
    }

    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    public void setLockLeaseTime(Duration lockLeaseTime) {
        this.lockLeaseTime = lockLeaseTime;
    }
}
