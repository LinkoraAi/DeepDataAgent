package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.DeepDataAgentApplication;
import com.linkroa.deepdataagent.runtime.application.port.CoordinationLeaseStore;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.config.CoordLeaseKeys;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Redis 租约 + PostgreSQL 双存储」全量上下文集成测试基类（move-coordination-leases-to-redis
 * tasks 2.4 / 2.6：交错与灰度用例需要真实 MyBatis 仓储与事务管理器，不能走最小上下文）。
 *
 * <p>与 {@link RedisRepositoryTestSupport}（最小上下文、纯 Redis 语义）的分工：本基类以全量应用
 * 上下文启动（协调租约唯一存储即 Redis），同时充当「Redis 租约可装配启动」冒烟；
 * 并显式关闭启动恢复（{@code app.agent.startup-recovery-enabled=false}），避免回写本机残留会话。</p>
 *
 * <p>需要环境变量 {@code TEST_REDIS_URL} 与 {@code TEST_PG_URL}（可选 {@code TEST_PG_USERNAME} /
 * {@code TEST_PG_PASSWORD}，默认 postgres），缺失即快速失败并给出提示。</p>
 */
@Tag("integration")
@SpringBootTest(classes = DeepDataAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class RedisFullContextTestSupport {

    private static final String ENV_REDIS_URL = "TEST_REDIS_URL";
    private static final String ENV_PG_URL = "TEST_PG_URL";

    @Resource
    protected CoordinationLeaseStore leaseStore;

    @Resource
    protected CoordinationLeaseService leaseService;

    @Resource
    protected AgentSessionRepository sessionRepository;

    @Resource
    protected PlatformTransactionManager transactionManager;

    @Resource
    protected JdbcTemplate jdbcTemplate;

    @Resource
    protected RedissonClient redissonClient;

    @Resource
    protected ApplicationContext applicationContext;

    /** 本用例创建的会话业务 ID（全量上下文不随用例事务回滚，逐条物理清理）。 */
    private final List<String> createdSessionIds = new ArrayList<>();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String redisUrl = System.getenv(ENV_REDIS_URL);
        if (StringUtils.isBlank(redisUrl)) {
            throw new IllegalStateException(
                    "交错 / 灰度集成测试需要可用的 Redis：请设置环境变量 TEST_REDIS_URL 后运行。");
        }
        String pgUrl = System.getenv(ENV_PG_URL);
        if (StringUtils.isBlank(pgUrl)) {
            throw new IllegalStateException(
                    "交错 / 灰度集成测试需要可用的 PostgreSQL：请设置环境变量 TEST_PG_URL 后运行"
                            + "（可选 TEST_PG_USERNAME / TEST_PG_PASSWORD，默认 postgres）。");
        }
        // 协调租约唯一存储为 Redis（PR-C4 起无 lease-store 开关），此处只注入连接串
        registry.add("spring.data.redis.url", () -> redisUrl);
        registry.add("spring.datasource.url", () -> pgUrl);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_PG_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_PG_PASSWORD", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // TEST_PG_URL 建议指向空库（Flyway 自 V1 基线完整迁移）；复用存量库时历史脚本校验和可能因
        // 分支演进不符，集成测试只要求表结构就位，故关闭篡改校验
        registry.add("spring.flyway.validate-on-migrate", () -> "false");
        registry.add("model.encryption.key", () -> "integration-test-model-secret");
        registry.add("vault.encryption.key", () -> "integration-test-vault-secret-32bytes-long");
        registry.add("auth.jwt.secret", () -> "integration-test-jwt-secret-please-change-32bytes");
        // 关闭启动恢复：本基类不验证恢复（见 tasks 3.1），且不得回写本机残留会话状态
        registry.add("app.agent.startup-recovery-enabled", () -> "false");
        // 出网白名单：集成用例的假 MCP / 假 OAuth 服务绑定回环地址（内网自建场景，design D8），
        // 边界拦截语义本身由常跑单测（AgentscopeHarnessAgentFactoryTest / TrustedEgressClientTest）覆盖
        registry.add("app.egress.allow-private-network", () -> "true");
    }

    /** 登记待清理会话（用例内物理删除，不留残留行）。 */
    protected void trackSession(String sessionId) {
        createdSessionIds.add(sessionId);
    }

    @AfterEach
    void cleanUpLeaseAndSessions() {
        // 本用例租约键 + 全部 it-* 实例索引 + 服务层实例索引（CoordinationLeaseService 自带标识）
        for (String sessionId : createdSessionIds) {
            redissonClient.getKeys().delete(CoordLeaseKeys.turn(sessionId));
        }
        redissonClient.getKeys().deleteByPattern("runtime:turn-owners:it-*");
        redissonClient.getSet(CoordLeaseKeys.turnOwners(leaseService.instanceId()),
                StringCodec.INSTANCE).delete();
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM agent_session WHERE session_id = ?", sessionId);
        }
        createdSessionIds.clear();
    }

    protected TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    /** turn key 当前持有者（无租约为 null）。 */
    protected String turnLeaseOwner(String sessionId) {
        return redissonClient.<String>getBucket(CoordLeaseKeys.turn(sessionId), StringCodec.INSTANCE).get();
    }

    /** turn key 剩余 TTL（毫秒；key 不存在返回 -2）。 */
    protected long turnLeaseTtlMillis(String sessionId) {
        return redissonClient.getBucket(CoordLeaseKeys.turn(sessionId), StringCodec.INSTANCE)
                .remainTimeToLive();
    }

    /** owner 二级索引是否含该会话。 */
    protected boolean ownerIndexContains(String owner, String sessionId) {
        return redissonClient.getSet(CoordLeaseKeys.turnOwners(owner), StringCodec.INSTANCE).contains(sessionId);
    }
}
