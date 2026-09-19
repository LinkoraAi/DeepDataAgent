package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.application.service.EnvironmentApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.EnvironmentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.response.EnvironmentResponse;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link EnvironmentController} 单测（7.7 self_hosted 边界协议面 + 8.3 归档/标准对象头）：
 * 自托管环境创建成功与 config 回显（type 小写规范值 + setup_script 透传、packages 归一空集）、
 * self_hosted 携带包声明在命令装配即拒绝（领域不变量前移、不触达应用服务）、
 * config 缺省收敛 cloud、详情与游标列表回显、归档端点为独立动作。
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentControllerTest {

    @Mock private EnvironmentApplicationService applicationService;

    @InjectMocks private EnvironmentController controller;

    @BeforeEach
    void setUpAuth() {
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    /** 自托管环境领域夹具（仅登记数据契约，无包声明） */
    private Environment selfHostedEnvironment() {
        return Environment.restore(null, "env_sh1", "自托管节点", null,
                new EnvironmentConfig(EnvironmentType.SELF_HOSTED, null, "apt-get update"),
                "{\"k\":\"v\"}", 1L, null, null, null, null, null);
    }

    private Environment cloudEnvironment() {
        return Environment.restore(null, "env_c1", "云端环境", null,
                EnvironmentConfig.cloudDefault(), "{}", 1L, null, null, null, null, null);
    }

    @Test
    void should_createSelfHostedWithScriptEcho_when_create_given_selfHostedConfig() {
        // given（self_hosted 携带 setup_script：命令装配合法，创建成功全量回显）
        when(applicationService.create(argThat(command ->
                EnvironmentType.SELF_HOSTED == command.config().type()
                        && "apt-get update".equals(command.config().setupScript())
                        && command.config().packages().isEmpty())))
                .thenReturn(selfHostedEnvironment());
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("自托管节点", null,
                new EnvironmentConfigRequest("self_hosted", null, "apt-get update"), Map.of("k", "v"));

        // when
        ApiResponse<EnvironmentResponse> response = controller.create(request);

        // then（标准对象头 id/type + config.type 小写规范值 + setup_script 下划线键 + metadata 对象化）
        assertTrue(response.success());
        assertEquals("env_sh1", response.data().id());
        assertEquals("environment", response.data().type());
        assertEquals("self_hosted", response.data().config().type());
        assertEquals("apt-get update", response.data().config().setup_script());
        assertEquals(Map.of("k", "v"), response.data().metadata());
        assertNull(response.data().archivedAt());
    }

    @Test
    void should_rejectPackagesOnSelfHosted_when_create_given_selfHostedWithPackages() {
        // given（self_hosted 不携带包声明：领域不变量在命令装配处即拒绝）
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("非法自托管", null,
                new EnvironmentConfigRequest("self_hosted", Map.of("pip", List.of("curl")), null),
                null);

        // when // then（400 语义异常透出，不触达应用服务）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.create(request));
        verifyNoInteractions(applicationService);
    }

    @Test
    void should_defaultCloudType_when_create_given_configOmitted() {
        // given（config 整体缺省：收敛为 {"type":"cloud"}）
        when(applicationService.create(argThat(command ->
                EnvironmentType.CLOUD == command.config().type())))
                .thenReturn(cloudEnvironment());

        // when
        ApiResponse<EnvironmentResponse> response =
                controller.create(new CreateEnvironmentRequest("云端环境", null, null, null));

        // then（packages 为判别对象，六类键全量回显）
        assertEquals("cloud", response.data().config().type());
        assertEquals("packages", response.data().config().packages().type());
        assertEquals(List.of(), response.data().config().packages().apt());
        assertEquals(List.of(), response.data().config().packages().go());
    }

    @Test
    void should_returnSelfHostedEcho_when_detail_given_environmentId() {
        // given（自托管环境可正常查询：登记数据契约不受执行平面拒绝影响）
        when(applicationService.get("env_sh1")).thenReturn(selfHostedEnvironment());

        // when
        ApiResponse<EnvironmentResponse> response = controller.detail("env_sh1");

        // then
        assertEquals("self_hosted", response.data().config().type());
        assertEquals("apt-get update", response.data().config().setup_script());
    }

    @Test
    void should_mapCursorPageEnvelope_when_list_given_queryParams() {
        // given（游标列表：领域 → 响应逐条映射 + Cursor 信封 stable 装配）
        when(applicationService.list(any(ListEnvironmentQuery.class)))
                .thenReturn(CursorPage.of(List.of(selfHostedEnvironment()), true, Environment::environmentId));

        // when
        ApiResponse<CursorPage<EnvironmentResponse>> response =
                controller.list(null, null, null, "20", null, null);

        // then
        assertEquals(1, response.data().data().size());
        assertEquals("env_sh1", response.data().firstId());
        assertEquals("env_sh1", response.data().lastId());
        assertTrue(response.data().hasMore());
        assertEquals("self_hosted", response.data().data().get(0).config().type());
    }

    @Test
    void should_delegateDelete_when_delete_given_environmentId() {
        // when
        ApiResponse<Void> response = controller.delete("env_1");

        // then（删除委派与引用守卫语义由应用服务承担）
        assertTrue(response.success());
        verify(applicationService).delete("env_1");
    }

    @Test
    void should_returnArchivedAt_when_archive_given_environmentId() {
        // given（归档为独立动作：写入 archived_at 时间戳后回显）
        Environment archived = selfHostedEnvironment().withArchivedAt(java.time.OffsetDateTime.now());
        when(applicationService.archive("env_sh1")).thenReturn(archived);

        // when
        ApiResponse<EnvironmentResponse> response = controller.archive("env_sh1");

        // then
        assertTrue(response.success());
        assertTrue(response.data().archivedAt() != null);
        verify(applicationService).archive("env_sh1");
    }
}