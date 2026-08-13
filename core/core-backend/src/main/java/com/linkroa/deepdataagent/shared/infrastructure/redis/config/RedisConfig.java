package com.linkroa.deepdataagent.shared.infrastructure.redis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 基础设施配置。
 * <p>显式声明基于 {@link RedisConnectionFactory} 的 {@link StringRedisTemplate}，
 * 供 Schema 缓存、限流、分布式锁三个适配器使用。值序列化统一为 String，
 * 所有存储内容均为纯文本（Schema 描述、计数、锁 token），无需对象序列化。</p>
 */
@Configuration
public class RedisConfig {

    /**
     * 字符串 Redis 模板 Bean。
     *
     * @param connectionFactory Redis 连接工厂
     * @return StringRedisTemplate
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
