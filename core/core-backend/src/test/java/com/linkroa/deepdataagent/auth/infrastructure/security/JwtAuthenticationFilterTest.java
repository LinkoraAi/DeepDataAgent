package com.linkroa.deepdataagent.auth.infrastructure.security;

import com.linkroa.deepdataagent.auth.application.port.RevokedTokenStore;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link JwtAuthenticationFilter} 放行名单单测。
 *
 * <p>覆盖：受保护端点缺少 Bearer 头一律 401 且不进入后续链路——防止放行名单被改宽后无人察觉。</p>
 */
class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(mock(JwtTokenProvider.class), mock(RevokedTokenStore.class));

    @Test
    void should_rejectWith401_when_doFilterInternal_given_protectedPathWithoutBearerHeader() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cloud/vaults");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        // when
        filter.doFilter(request, response, (req, res) -> chained.set(true));

        // then
        assertFalse(chained.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void should_rejectWith401_when_doFilterInternal_given_oauthStartPathWithoutBearerHeader() throws Exception {
        // given（发起授权仍须认证：owner 绑定来自请求身份，不得匿名发起）
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/cloud/vaults/oauth/start");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        // when
        filter.doFilter(request, response, (req, res) -> chained.set(true));

        // then
        assertFalse(chained.get());
        assertEquals(401, response.getStatus());
    }
}