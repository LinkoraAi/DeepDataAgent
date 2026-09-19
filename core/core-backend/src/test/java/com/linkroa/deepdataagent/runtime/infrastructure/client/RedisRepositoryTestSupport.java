package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.CoordinationLeaseStore;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseLuaScripts;
import com.linkroa.deepdataagent.runtime.infrastructure.config.RedissonConfig;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 协调租约 Redis 存储集成测试基类（move-coordination-leases-to-redis D7）。
 *
 * <p>仿 {@code DatasourceRepositoryTestSupport} 惯例：{@code @Tag("integration")}（surefire 已排除
 * 该组，需显式执行）+ 环境变量连接串（{@code TEST_REDIS_URL}），<b>不引 Testcontainers</b>。
 * 连接串经 {@code @DynamicPropertySource} 注入 {@code spring.data.redis.url}，与
 * {@link RedissonConfig} 的「url 优先取值」口径同源（design D1 定稿）。</p>
 *
 * <p><b>最小上下文</b>：只装配 {@link RedissonConfig} + {@link CoordLeaseLuaScripts} +
 * {@link RedisCoordLeaseStore} + {@link RedisCoordLeaseStoreProbe}
 * （协调租约唯一存储即 Redis，PR-C4 起无 db 备选实现），不启全量应用上下文，
 * 使本组用例同时充当「Redisson 与 Boot 4 / Netty BOM 共存 + RScript 真连冒烟」（tasks 1.1 冒烟项），
 * 并真机跑一次部署红线自检（tasks 3.3：CONFIG GET maxmemory-policy 回显）。</p>
 *
 * <p><b>键隔离</b>：全部用例的 sessionId / owner / deploymentId 以 {@code it-} 前缀命名，
 * 每个用例后按前缀清理 {@code runtime:turn:*} / {@code runtime:turn-owners:*} / {@code runtime:fire:*}，
 * 不触碰同库其他业务键（如 auth 的撤销令牌），故 MUST NOT 使用 flushdb。</p>
 */
@Tag("integration")
@SpringBootTest(classes = RedisRepositoryTestSupport.RedisEnabledTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class RedisRepositoryTestSupport {

    /** 本地 Redis 连接串环境变量（形如 {@code redis://:password@127.0.0.1:6379/9}）。 */
    private static final String ENV_REDIS_URL = "TEST_REDIS_URL";

    @Resource
    protected RedissonClient redissonClient;

    /** 被测端口（Redis 实现经开关装配，用例面向端口编程，口径与生产一致）。 */
    @Resource
    protected CoordinationLeaseStore leaseStore;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv(ENV_REDIS_URL);
        if (StringUtils.isBlank(url)) {
            throw new IllegalStateException(
                    "集成测试需要可用的 Redis：请设置环境变量 TEST_REDIS_URL"
                            + "（形如 redis://:password@127.0.0.1:6379/9）后运行；常规构建已排除 integration 组。");
        }
        // url 优先取值（design D1）：与生产 host/port 分项形态同源，Redisson 原生解析密码与库号
        registry.add("spring.data.redis.url", () -> url);
    }

    /** 用例间键空间清理（前缀隔离，见类注释）。 */
    @AfterEach
    void cleanUpLeaseKeys() {
        redissonClient.getKeys().deleteByPattern("runtime:turn:it-*");
        redissonClient.getKeys().deleteByPattern("runtime:turn-owners:it-*");
        redissonClient.getKeys().deleteByPattern("runtime:fire:it-*");
    }

    /** 集成测试装配面：仅租约 Redis 存储三件套 + 部署红线探针（不启全量上下文）。 */
    @Configuration(proxyBeanMethods = false)
    @Import({RedissonConfig.class, CoordLeaseLuaScripts.class, RedisCoordLeaseStore.class,
            RedisCoordLeaseStoreProbe.class})
    static class RedisEnabledTestConfig {
    }
}
