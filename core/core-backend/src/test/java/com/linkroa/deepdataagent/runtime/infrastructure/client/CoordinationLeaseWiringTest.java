package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseLuaScripts;
import com.linkroa.deepdataagent.runtime.infrastructure.config.RedissonConfig;
import com.linkroa.deepdataagent.runtime.infrastructure.execution.ScheduledPoolLeaseRenewalScheduler;
import com.linkroa.deepdataagent.runtime.infrastructure.execution.VirtualThreadLeaseRenewalScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协调租约装配面单测（move-coordination-leases-to-redis D3 / D5③ / tasks 3.5 + PR-C4 收口）。
 *
 * <p>PR-C4 起 {@code app.coordination.lease-store} 二选一开关与 PG 实现已下线（全仓 grep 零残留为
 * 收口判据）：本类锁定两件事——
 * ① Redis 存储与部署红线探针<b>无条件常驻装配</b>（Redis 为唯一租约存储，无需任何属性）；
 * ② 续约承载形态开关（{@code app.coordination.lease-renewal}）仍为「恰一个实现」的二选一，
 * 且生产默认值为平台线程定时池、配置面不再出现租约存储开关。</p>
 */
class CoordinationLeaseWiringTest {

    /**
     * 租约存储装配面：不含 {@link RedissonConfig}（其 @Bean 方法会真连 Redis），
     * 以 mock {@code RedissonClient} 满足 {@link RedisCoordLeaseStore} 的注入面。
     */
    private final ApplicationContextRunner leaseStoreRunner = new ApplicationContextRunner()
            .withBean("redissonClientMock", RedissonClient.class, () -> Mockito.mock(RedissonClient.class))
            .withUserConfiguration(CoordLeaseLuaScripts.class,
                    RedisCoordLeaseStore.class, RedisCoordLeaseStoreProbe.class);

    /** 续约承载形态装配面。 */
    private final ApplicationContextRunner leaseRenewalRunner = new ApplicationContextRunner()
            .withBean("leaseRenewalExecutor", ScheduledExecutorService.class,
                    () -> Mockito.mock(ScheduledExecutorService.class))
            .withUserConfiguration(ScheduledPoolLeaseRenewalScheduler.class,
                    VirtualThreadLeaseRenewalScheduler.class);

    @Test
    void should_wireRedisStoreAndProbe_when_coordinationLease_given_noProperty() {
        // given（PR-C4：无任何 lease-store 属性，Redis 实现常驻）

        // when // then（存储实现 + 部署红线探针均装配；探针读不到 CONFIG 只 WARN 不阻断启动，
        // run() 在上下文启动失败时即断言失败，故此处无需额外失败判据）
        leaseStoreRunner.run(context -> {
            assertTrue(context.getBeanNamesForType(RedisCoordLeaseStore.class).length == 1,
                    "Redis 租约存储应无条件装配");
            assertTrue(context.getBeanNamesForType(RedisCoordLeaseStoreProbe.class).length == 1,
                    "部署红线探针应无条件装配");
        });
    }

    @Test
    void should_wirePlatformPoolScheduler_when_leaseRenewalUnset_given_defaultCapacity() {
        // given（容量预案默认承载：平台线程定时池）

        // when // then
        leaseRenewalRunner.run(context -> {
            assertTrue(context.getBeanNamesForType(ScheduledPoolLeaseRenewalScheduler.class).length == 1);
            assertTrue(context.getBeanNamesForType(VirtualThreadLeaseRenewalScheduler.class).length == 0);
        });
    }

    @Test
    void should_wireVirtualThreadScheduler_when_leaseRenewalVirtualThread_given_capacityFallback() {
        // given（并发轮次上量：切每轮 daemon 虚拟线程 sleep 循环）

        // when // then
        leaseRenewalRunner.withPropertyValues("app.coordination.lease-renewal=virtual-thread")
                .run(context -> {
                    assertTrue(context.getBeanNamesForType(VirtualThreadLeaseRenewalScheduler.class).length == 1);
                    assertTrue(context.getBeanNamesForType(ScheduledPoolLeaseRenewalScheduler.class).length == 0);
                });
    }

    @Test
    void should_keepRenewalDefaultAndDropLeaseStoreKey_when_readApplicationYaml_given_prC4Rollout()
            throws IOException {
        // given（PR-C4：生产配置只留续约承载开关，租约存储开关随 PG 实现一并删除）

        // when
        String yaml = readApplicationYaml();

        // then
        assertTrue(yaml.contains("APP_COORDINATION_LEASE_RENEWAL:scheduler}"),
                "app.coordination.lease-renewal 默认值应保持 scheduler");
        assertTrue(!yaml.contains("lease-store:") && !yaml.contains("APP_COORDINATION_LEASE_STORE"),
                "app.coordination.lease-store 开关应随 PG 实现下线，配置面不得再出现");
    }

    /** 读主配置原文（不启 Spring 上下文，仅校验默认值口径）。 */
    private static String readApplicationYaml() throws IOException {
        try (InputStream in = new ClassPathResource("application.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
