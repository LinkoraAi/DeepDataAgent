package com.linkroa.deepdataagent.auth.controller.rest;

import com.linkroa.deepdataagent.auth.application.service.AuthApplicationService;
import com.linkroa.deepdataagent.auth.application.service.LoginResult;
import com.linkroa.deepdataagent.auth.controller.request.LoginRequest;
import com.linkroa.deepdataagent.auth.infrastructure.security.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthController} 登录限流键构造单测：锁定「客户端 IP 只信代理覆盖式 X-Real-IP、
 * 伪造 XFF 不参与限流键；email 限流键经 trim + 小写归一与账号定位口径一致」两处防绕过语义。
 * <p>登录 / 注册为公开端点，不依赖 AuthContext，故无需设置用户态。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthApplicationService applicationService;
    @Mock
    private AuthRateLimiter authRateLimiter;

    @InjectMocks
    private AuthController controller;

    @Test
    void should_takeXRealIp_when_login_given_forgedXffAndProxyOverwrittenRealIp() {
        // given（nginx 以覆盖式 $remote_addr 写 X-Real-IP；XFF 首元素为客户端伪造值）
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("10.0.0.9");
        servletRequest.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.9");
        servletRequest.addHeader("X-Real-IP", "203.0.113.7");
        stubRateLimitAllowedAndLoginOk();

        // when
        controller.login(new LoginRequest("user@example.com", "Passw0rd!"), servletRequest);

        // then（IP 限流键取 X-Real-IP，伪造的 XFF 首元素被忽略）
        verify(authRateLimiter).tryAcquire("login:ip:203.0.113.7", 10);
    }

    @Test
    void should_fallbackRemoteAddr_when_login_given_blankXRealIp() {
        // given（直连后端绕过网关：X-Real-IP 缺失，回落连接层地址）
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("198.51.100.2");
        stubRateLimitAllowedAndLoginOk();

        // when
        controller.login(new LoginRequest("user@example.com", "Passw0rd!"), servletRequest);

        // then
        verify(authRateLimiter).tryAcquire("login:ip:198.51.100.2", 10);
    }

    @Test
    void should_useNormalizedEmailKey_when_login_given_emailWithCaseAndSpaceVariants() {
        // given（大小写 / 首尾空格变体须与账号定位同口径归一，防绕开 email 限流桶）
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("203.0.113.7");
        stubRateLimitAllowedAndLoginOk();

        // when
        controller.login(new LoginRequest("  User@Example.COM ", "Passw0rd!"), servletRequest);

        // then（email 限流键为归一化值）
        verify(authRateLimiter).tryAcquire("login:email:user@example.com", 10);
    }

    /** 放行限流并桩定登录成功返回，使控制器走完限流键构造流程。 */
    private void stubRateLimitAllowedAndLoginOk() {
        when(authRateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(applicationService.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LoginResult("jwt-token", 604800L, 7L, "user@example.com"));
    }
}
