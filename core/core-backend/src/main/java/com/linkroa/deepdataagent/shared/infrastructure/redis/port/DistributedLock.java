package com.linkroa.deepdataagent.shared.infrastructure.redis.port;

/**
 * 分布式锁句柄。
 * <p>锁获取成功后返回给调用方，业务执行完毕后调用 {@link #unlock()} 释放；
 * 实现 {@link AutoCloseable} 以支持 try-with-resources 简化释放逻辑。</p>
 */
public interface DistributedLock extends AutoCloseable {

    /**
     * 释放锁。实现必须保证幂等：重复释放或已过期释放不得抛异常。
     */
    void unlock();

    @Override
    default void close() {
        unlock();
    }
}
