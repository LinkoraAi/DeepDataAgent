package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.DeepDataAgentApplication;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据源持久层集成测试基类。
 * <p>底层数据库已由 SQLite 切换为 PostgreSQL，测试通过 {@code spring.datasource.*} 注入
 * 本地 PG 连接（环境变量 {@code TEST_PG_URL} / {@code TEST_PG_USERNAME} / {@code TEST_PG_PASSWORD}），
 * 表结构由 Flyway 迁移（classpath:db/migration）在应用启动时自动初始化，测试不负责建表逻辑。
 * 若未配置环境变量则快速失败并给出明确提示；集成测试被排除在常规构建（surefire）之外，需显式执行。</p>
 *
 * <p><b>Redis 亦为必需</b>：move-coordination-leases-to-redis PR-C4 起协调租约唯一存储为 Redis，
 * {@code RedissonConfig} 常驻装配（启动即建连接），故全量上下文用例必须同时提供
 * {@code TEST_REDIS_URL}（形如 {@code redis://:password@127.0.0.1:6379/9}）。</p>
 */
@Tag("integration")
@SpringBootTest(classes = DeepDataAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class DatasourceRepositoryTestSupport {

    /** 本地 PostgreSQL 连接串环境变量 */
    private static final String ENV_PG_URL = "TEST_PG_URL";

    /** 本地 PostgreSQL 用户名环境变量 */
    private static final String ENV_PG_USERNAME = "TEST_PG_USERNAME";

    /** 本地 PostgreSQL 密码环境变量 */
    private static final String ENV_PG_PASSWORD = "TEST_PG_PASSWORD";

    /** 本地 Redis 连接串环境变量（协调租约 Redisson 客户端启动即连） */
    private static final String ENV_REDIS_URL = "TEST_REDIS_URL";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv(ENV_PG_URL);
        if (StringUtils.isBlank(url)) {
            throw new IllegalStateException(
                    "集成测试需要可用的 PostgreSQL：请设置环境变量 TEST_PG_URL 后运行"
                            + "（可选 TEST_PG_USERNAME / TEST_PG_PASSWORD，默认 postgres）。");
        }
        String redisUrl = System.getenv(ENV_REDIS_URL);
        if (StringUtils.isBlank(redisUrl)) {
            throw new IllegalStateException(
                    "集成测试需要可用的 Redis（协调租约唯一存储，Redisson 启动即建连接）："
                            + "请设置环境变量 TEST_REDIS_URL 后运行"
                            + "（形如 redis://:password@127.0.0.1:6379/9）。");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault(ENV_PG_USERNAME, "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault(ENV_PG_PASSWORD, "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 协调租约 Redis 连接串（Redisson「url 优先」同源取值，design D1）
        registry.add("spring.data.redis.url", () -> redisUrl);
        // application.yaml 中 model.encryption.key / vault.encryption.key / auth.jwt.secret 无默认值（强制生产显式配置），集成测试补默认值避免启动失败
        registry.add("model.encryption.key", () -> "integration-test-model-secret");
        registry.add("vault.encryption.key", () -> "integration-test-vault-secret-32bytes-long");
        registry.add("auth.jwt.secret", () -> "integration-test-jwt-secret-please-change-32bytes");
    }
}
