package com.linkroa.deepdataagent.shared.infrastructure.redis.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 分布式锁端口。
 * <p>为"唯一默认模型"等并发写操作提供跨实例互斥保护。锁携带自动过期时间，
 * 持有方异常退出后由过期时间兜底释放。该端口隔离了具体锁实现（Redis 或内存）。</p>
 */
public interface DistributedLockPort {

    /**
     * 尝试获取锁。
     *
     * @param key       锁键（如默认模型切换）
     * @param leaseTime 锁持有时间，到期自动释放
     * @return 获取成功返回锁句柄（使用完毕后必须 {@link DistributedLock#unlock()}）；
     *         锁已被其他实例持有返回 {@link Optional#empty()}
     */
    Optional<DistributedLock> tryLock(String key, Duration leaseTime);
}
