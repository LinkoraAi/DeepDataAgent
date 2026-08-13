package com.linkroa.deepdataagent.shared.infrastructure.redis.port;

/**
 * 限流端口。
 * <p>基于固定时间窗口对指定键（如模型配置 ID）进行限流，防止外部依赖被高频调用。
 * 该端口隔离了具体限流实现（Redis 或内存），调用方无需关心计数存储位置。</p>
 */
public interface RateLimiterPort {

    /**
     * 尝试获取一次调用配额。
     *
     * @param key 限流维度键（如模型配置 ID）
     * @return true 表示放行；false 表示窗口内已达上限，应拒绝请求
     */
    boolean tryAcquire(String key);
}
