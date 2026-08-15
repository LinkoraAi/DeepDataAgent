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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv(ENV_PG_URL);
        if (StringUtils.isBlank(url)) {
            throw new IllegalStateException(
                    "集成测试需要可用的 PostgreSQL：请设置环境变量 TEST_PG_URL 后运行"
                            + "（可选 TEST_PG_USERNAME / TEST_PG_PASSWORD，默认 postgres）。");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault(ENV_PG_USERNAME, "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault(ENV_PG_PASSWORD, "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
