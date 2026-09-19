package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.EnvironmentApi;
import com.linkroa.deepdataagent.agent.api.dto.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentPackages;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultEnvironmentApi} 服务契约进程内实现单测（环境类型解析 + owner 归属比对）。
 * <p>不存在 / 越权统一返回 {@code null}（404 语义由消费方映射，不泄露存在性）；
 * 类型以规范化小写值（cloud / self_hosted）返回，供跨 BC 校验比较。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultEnvironmentApiTest {

    @Mock private EnvironmentRepository environmentRepository;

    private EnvironmentApi environmentApi;

    @BeforeEach
    void setUp() {
        DefaultEnvironmentApi api = new DefaultEnvironmentApi();
        ReflectionTestUtils.setField(api, "environmentRepository", environmentRepository);
        environmentApi = api;
    }

    private static Environment environment(EnvironmentType type, String setupScript, Long ownerId) {
        EnvironmentConfig config = new EnvironmentConfig(type, EnvironmentPackages.empty(), setupScript);
        return Environment.create("env_1", "默认环境", null, config, null, ownerId);
    }

    @Test
    void should_returnCanonicalType_when_resolveType_given_ownedEnvironment() {
        // given
        when(environmentRepository.findByEnvironmentId("env_1"))
                .thenReturn(Optional.of(environment(EnvironmentType.CLOUD, null, 1L)));

        // when
        String type = environmentApi.resolveType(1L, "env_1");

        // then（config 类型输出规范化小写值）
        assertEquals("cloud", type);
    }

    @Test
    void should_returnNull_when_resolveType_given_otherOwnersEnvironment() {
        // given（越权：环境归属其他用户）
        when(environmentRepository.findByEnvironmentId("env_1"))
                .thenReturn(Optional.of(environment(EnvironmentType.SELF_HOSTED, null, 2L)));

        // when
        String type = environmentApi.resolveType(1L, "env_1");

        // then（越权与不存在统一 null 语义）
        assertNull(type);
    }

    @Test
    void should_returnNull_when_resolveType_given_missingEnvironment() {
        // given
        when(environmentRepository.findByEnvironmentId("env_gone")).thenReturn(Optional.empty());

        // when
        String type = environmentApi.resolveType(1L, "env_gone");

        // then
        assertNull(type);
    }

    @Test
    void should_returnNull_when_resolveType_given_nullOwnerOrBlankId() {
        // when & then（缺参短路，不触碰仓储）
        assertNull(environmentApi.resolveType(null, "env_1"));
        assertNull(environmentApi.resolveType(1L, null));
        assertNull(environmentApi.resolveType(1L, " "));
    }

    @Test
    void should_returnNull_when_resolveType_given_archivedEnvironment() {
        // given（已归档环境不可被新 Session 引用）
        Environment archived = environment(EnvironmentType.CLOUD, null, 1L)
                .withArchivedAt(OffsetDateTime.now());
        when(environmentRepository.findByEnvironmentId("env_1")).thenReturn(Optional.of(archived));

        // when & then
        assertNull(environmentApi.resolveType(1L, "env_1"));
        assertNull(environmentApi.resolveReference(1L, "env_1"));
    }

    @Test
    void should_returnFormattedReference_when_resolveReference_given_ownedEnvironment() {
        // given
        when(environmentRepository.findByEnvironmentId("env_1"))
                .thenReturn(Optional.of(environment(EnvironmentType.SELF_HOSTED, "apt-get update", 1L)));

        // when
        EnvironmentReferenceDTO reference = environmentApi.resolveReference(1L, "env_1");

        // then（类型输出规范化小写值，脚本原样透出，不再摊平沙箱明细）
        assertEquals("env_1", reference.environmentId());
        assertEquals("默认环境", reference.name());
        assertEquals("self_hosted", reference.type());
        assertEquals("apt-get update", reference.setupScript());
    }

    @Test
    void should_returnNullReference_when_resolveReference_given_otherOwnersOrMissingEnvironment() {
        // given（越权：环境归属其他用户）
        when(environmentRepository.findByEnvironmentId("env_1"))
                .thenReturn(Optional.of(environment(EnvironmentType.CLOUD, null, 2L)));

        // when & then（越权与不存在统一 null 语义，404 由消费方映射）
        assertNull(environmentApi.resolveReference(1L, "env_1"));

        // given（环境不存在）
        when(environmentRepository.findByEnvironmentId("env_gone")).thenReturn(Optional.empty());

        // when & then
        assertNull(environmentApi.resolveReference(1L, "env_gone"));
    }

    @Test
    void should_returnNull_when_resolveReference_given_nullOwnerOrBlankId() {
        // when & then（缺参短路，不触碰仓储）
        assertNull(environmentApi.resolveReference(null, "env_1"));
        assertNull(environmentApi.resolveReference(1L, " "));
    }
}
