package com.linkroa.deepdataagent.vault.infrastructure.client;

import com.linkroa.deepdataagent.vault.application.dto.VaultOAuthStateDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthStateStore;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * OAuth state 一次性存储（Redis 承载，见 design D7）。
 *
 * <p><b>物理键</b>：{@code vault:oauth-state:<state>}；<b>值</b>为 state 载荷 JSON
 * （客户端密钥以密文形态承载，Redis 中无明文）。</p>
 *
 * <p><b>一次性与过期</b>：消费走 Redis {@code GETDEL}（读删一体，重放必空），过期由键 TTL
 * 承担（默认 300s，见 {@code VaultOAuthProperties}），两者共同保证「已被消费 / 已过期 /
 * 从未存在」对调用方不可区分。仅用 Redisson 核心库的 {@code RBucket}，不引入额外 Lua。</p>
 */
@Service
public class RedisVaultOAuthStateStore implements VaultOAuthStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisVaultOAuthStateStore.class);

    /** 物理键前缀（Redis 键规划归基础设施，application / domain 不得感知）。 */
    private static final String KEY_PREFIX = "vault:oauth-state:";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void save(String state, VaultOAuthStateDTO data, Duration ttl) {
        bucket(state).set(writeJson(data), ttl);
    }

    @Override
    public Optional<VaultOAuthStateDTO> consume(String state) {
        if (StringUtils.isBlank(state)) {
            return Optional.empty();
        }
        String payload = bucket(state).getAndDelete();
        if (StringUtils.isBlank(payload)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(OBJECT_MAPPER.readValue(payload, VaultOAuthStateDTO.class));
        } catch (RuntimeException e) {
            // 载荷损坏按「无效 state」处理（不产生凭证），不向调用方抛技术异常
            log.warn("OAuth state 载荷解析失败，按无效 state 处理: reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** state 物理键（StringCodec：值与写入形态一致，不经对象编解码）。 */
    private RBucket<String> bucket(String state) {
        return redissonClient.getBucket(KEY_PREFIX + state, StringCodec.INSTANCE);
    }

    /** 载荷 JSON 序列化（仅含回调换码上下文，客户端密钥已是密文）。 */
    private static String writeJson(VaultOAuthStateDTO data) {
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("OAuth state 载荷序列化失败: " + e.getMessage(), e);
        }
    }
}