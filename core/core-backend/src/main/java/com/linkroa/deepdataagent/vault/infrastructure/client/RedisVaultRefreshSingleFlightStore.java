package com.linkroa.deepdataagent.vault.infrastructure.client;

import com.linkroa.deepdataagent.vault.application.port.VaultRefreshSingleFlightPort;
import jakarta.annotation.Resource;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.bucket.CompareAndDeleteArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 凭证刷新单飞存储（Redis 承载，见 design D6）。
 *
 * <p><b>物理键</b>：{@code vault:credential-refresh:<credentialId>}；<b>值</b>为本次持有令牌
 * （随机 UUID）。获取为 {@code SET NX PX}（同键并发只有一个成功），释放为「值等于本次持有令牌」
 * 才删——避免 TTL 过期后误删已由他人接管的键。仅用 Redisson 核心库的 {@code RBucket}
 * 两个原语，不引入额外 Lua。</p>
 */
@Service
public class RedisVaultRefreshSingleFlightStore implements VaultRefreshSingleFlightPort {

    /** 物理键前缀（Redis 键规划归基础设施，application / domain 不得感知）。 */
    private static final String KEY_PREFIX = "vault:credential-refresh:";

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Optional<String> tryAcquire(String credentialId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        // setIfAbsent(value, ttl) 即 SET NX PX：同键并发只有一个持有者胜出
        return bucket(credentialId).setIfAbsent(token, ttl) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public void release(String credentialId, String token) {
        bucket(credentialId).compareAndDelete(CompareAndDeleteArgs.expected(token));
    }

    /** 单飞物理键（StringCodec：值即持有令牌字符串）。 */
    private RBucket<String> bucket(String credentialId) {
        return redissonClient.getBucket(KEY_PREFIX + credentialId, StringCodec.INSTANCE);
    }
}