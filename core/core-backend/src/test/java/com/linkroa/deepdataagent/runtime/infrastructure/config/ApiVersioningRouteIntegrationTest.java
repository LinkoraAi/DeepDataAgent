package com.linkroa.deepdataagent.runtime.infrastructure.config;

import com.linkroa.deepdataagent.DeepDataAgentApplication;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本化路径装配的路由级冒烟测试（{@code add-datasource-rest-and-version-kb-rag} tasks 1.3 / 1.4）。
 *
 * <p>目的：验证知识库 / RAG 控制器包加入 {@link ApiVersioningConfig#VERSIONED_CONTROLLER_PACKAGES}
 * 后，其端点在 {@code /api/{version}} 前缀下确实被 Spring MVC 注册（而裸路径不再注册），
 * 同时回归云资源与认证路由不受影响。</p>
 *
 * <p><b>为何直接断言 HandlerMapping</b>：{@code JwtAuthenticationFilter} 以
 * {@code Ordered.HIGHEST_PRECEDENCE + 10} 在路由之前拦截，未携带 token 时对任意路径（含不存在
 * 的裸路径）一律返回 401，无法用请求-响应区分"路由是否存在"。故本测试直接检查
 * {@link RequestMappingHandlerMapping} 注册的路径模式集合——注册了带
 * {@code /api/{version}} 前缀的模式即证明前缀已挂载。</p>
 *
 * <p>需要环境变量 {@code TEST_REDIS_URL} 与 {@code TEST_PG_URL}（可复用于既有集成套件），
 * 缺失即快速失败；默认测试档以 surefire {@code excludedGroups=integration} 排除本类。</p>
 *
 * @author DeepDataAgent
 */
@Tag("integration")
@SpringBootTest(classes = DeepDataAgentApplication.class)
class ApiVersioningRouteIntegrationTest {

    /** 环境变量：Redis 连接串 */
    private static final String ENV_REDIS_URL = "TEST_REDIS_URL";

    /** 环境变量：PostgreSQL 连接串 */
    private static final String ENV_PG_URL = "TEST_PG_URL";

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    /**
     * 注入集成测试所需的外部依赖连接串（与既有全量上下文集成基类口径一致）。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String redisUrl = System.getenv(ENV_REDIS_URL);
        if (StringUtils.isBlank(redisUrl)) {
            throw new IllegalStateException(
                    "路由装配冒烟测试需要可用的 Redis：请设置环境变量 TEST_REDIS_URL 后运行。");
        }
        String pgUrl = System.getenv(ENV_PG_URL);
        if (StringUtils.isBlank(pgUrl)) {
            throw new IllegalStateException(
                    "路由装配冒烟测试需要可用的 PostgreSQL：请设置环境变量 TEST_PG_URL 后运行"
                            + "（可选 TEST_PG_USERNAME / TEST_PG_PASSWORD，默认 postgres）。");
        }
        registry.add("spring.data.redis.url", () -> redisUrl);
        registry.add("spring.datasource.url", () -> pgUrl);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_PG_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_PG_PASSWORD", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.validate-on-migrate", () -> "false");
        registry.add("model.encryption.key", () -> "integration-test-model-secret");
        registry.add("vault.encryption.key", () -> "integration-test-vault-secret-32bytes-long");
        registry.add("auth.jwt.secret", () -> "integration-test-jwt-secret-please-change-32bytes");
        registry.add("app.agent.startup-recovery-enabled", () -> "false");
        registry.add("app.egress.allow-private-network", () -> "true");
    }

    /**
     * 收集上下文内全部已注册的请求路径模式。
     *
     * @return 路径模式字符串集合
     */
    private Set<String> registeredPatterns() {
        return requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(java.util.Objects::nonNull)
                .flatMap(condition -> condition.getPatternValues().stream())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("知识库管理端点注册在 /api/{version} 前缀下")
    void should_registerVersionedPattern_when_knowledgeBaseControllerInVersionedPackages() {
        // given 全量上下文已启动

        // when
        Set<String> patterns = registeredPatterns();

        // then
        assertTrue(patterns.contains("/api/{version}/knowledge-base/knowledge-bases"),
                "知识库管理端点必须挂载 /api/{version} 前缀");
    }

    @Test
    @DisplayName("RAG 检索端点注册在 /api/{version} 前缀下")
    void should_registerVersionedPattern_when_ragControllerInVersionedPackages() {
        // given

        // when
        Set<String> patterns = registeredPatterns();

        // then
        assertTrue(patterns.contains("/api/{version}/rag/retrievals"),
                "RAG 检索端点必须挂载 /api/{version} 前缀");
    }

    @Test
    @DisplayName("裸知识库路径不再注册")
    void should_notRegisterBarePattern_when_knowledgeBaseMountChanged() {
        // given

        // when
        Set<String> patterns = registeredPatterns();

        // then
        assertFalse(patterns.contains("/knowledge-base/knowledge-bases"),
                "裸知识库路径不得再被注册");
    }

    @Test
    @DisplayName("数据源端点注册在 /api/{version} 前缀下（单一控制器承载）")
    void should_registerVersionedPatterns_when_datasourceControllerInVersionedPackages() {
        // given

        // when
        Set<String> patterns = registeredPatterns();

        // then 抽查三类分区端点均由 DatasourceController 挂载
        assertTrue(patterns.contains("/api/{version}/datasource/types"), "类型清单端点必须挂载前缀");
        assertTrue(patterns.contains("/api/{version}/datasource/connections"), "连接端点必须挂载前缀");
        assertTrue(patterns.contains("/api/{version}/datasource/connections/{id}/tables"), "表元数据端点必须挂载前缀");
        assertTrue(patterns.contains("/api/{version}/datasource/api-schemas/parse"), "解析端点必须挂载前缀");
        assertFalse(patterns.contains("/datasource/types"), "裸数据源路径不得被注册");
    }

    @Test
    @DisplayName("云资源版本化路由不回归")
    void should_registerVersionedPattern_when_cloudAgentsUnchanged() {
        // given

        // when
        Set<String> patterns = registeredPatterns();

        // then
        assertTrue(patterns.contains("/api/{version}/cloud/agents"),
                "既有云资源端点必须仍挂载 /api/{version} 前缀");
    }

    @Test
    @DisplayName("认证路由保持非版本化形态")
    void should_registerNonVersionedPattern_when_authControllerUnchanged() {
        // given

        // when
        Set<String> patterns = registeredPatterns();

        // then
        assertTrue(patterns.contains("/api/auth/login"), "认证端点的非版本化路径必须保留");
        assertFalse(patterns.contains("/api/{version}/auth/login"), "认证端点不得被挂载版本前缀");
    }
}