package com.linkroa.deepdataagent.runtime.infrastructure.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisMaster;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSingle;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link RedisCoordLeaseStoreProbe} 单测：部署红线自检的判定口径与「探针异常不阻断启动」
 * （move-coordination-leases-to-redis D4）。
 */
@ExtendWith(MockitoExtension.class)
class RedisCoordLeaseStoreProbeTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RedisSingle redisSingle;
    @Mock private RedisMaster redisMaster;

    private RedisCoordLeaseStoreProbe probe;

    @BeforeEach
    void setUp() {
        probe = new RedisCoordLeaseStoreProbe();
        ReflectionTestUtils.setField(probe, "redissonClient", redissonClient);
    }

    /** 打桩：CONFIG GET maxmemory-policy 返回给定值。 */
    private void stubPolicy(String policy) {
        when(redissonClient.getRedisNodes(RedisNodes.SINGLE)).thenReturn(redisSingle);
        when(redisSingle.getInstance()).thenReturn(redisMaster);
        when(redisMaster.getConfig(RedisCoordLeaseStoreProbe.CONFIG_MAXMEMORY_POLICY))
                .thenReturn(policy == null ? Map.of()
                        : Map.of(RedisCoordLeaseStoreProbe.CONFIG_MAXMEMORY_POLICY, policy));
    }

    @Test
    void should_acceptOnlyNoeviction_when_isCompliant_given_policyVariants() {
        // given // when // then（大小写 / 空白无关；其余淘汰策略一律不合规）
        assertTrue(RedisCoordLeaseStoreProbe.isCompliant("noeviction"));
        assertTrue(RedisCoordLeaseStoreProbe.isCompliant(" NOEVICTION "));
        assertFalse(RedisCoordLeaseStoreProbe.isCompliant("allkeys-lru"));
        assertFalse(RedisCoordLeaseStoreProbe.isCompliant("volatile-ttl"));
        assertFalse(RedisCoordLeaseStoreProbe.isCompliant(null));
    }

    @Test
    void should_reportQuietly_when_reportMaxMemoryPolicy_given_compliantPolicy() {
        // given（合规：租约 key 只由 TTL 过期）
        stubPolicy("noeviction");

        // when // then（INFO 回显，不外抛）
        assertDoesNotThrow(probe::reportMaxMemoryPolicy);
    }

    @Test
    void should_warnButNotFail_when_reportMaxMemoryPolicy_given_evincingPolicy() {
        // given（红线不满足：内存压力下租约 key 会被随机淘汰 → 只 WARN，不阻断启动）
        stubPolicy("allkeys-lru");

        // when // then
        assertDoesNotThrow(probe::reportMaxMemoryPolicy);
    }

    @Test
    void should_skipSelfCheck_when_reportMaxMemoryPolicy_given_configCommandRejected() {
        // given（受限托管 Redis 禁用 CONFIG 命令：读取抛运行时异常）
        when(redissonClient.getRedisNodes(RedisNodes.SINGLE)).thenReturn(redisSingle);
        when(redisSingle.getInstance()).thenReturn(redisMaster);
        when(redisMaster.getConfig(RedisCoordLeaseStoreProbe.CONFIG_MAXMEMORY_POLICY))
                .thenThrow(new UnsupportedOperationException("ERR unknown command 'CONFIG'"));

        // when // then（探针降级为 WARN，MUST NOT 让应用启动失败）
        assertDoesNotThrow(probe::reportMaxMemoryPolicy);
    }

    @Test
    void should_skipSelfCheck_when_reportMaxMemoryPolicy_given_blankConfigResult() {
        // given（返回空 map：无法判定策略）
        stubPolicy(null);

        // when // then
        assertDoesNotThrow(probe::reportMaxMemoryPolicy);
    }
}
