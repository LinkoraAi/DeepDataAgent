package com.linkroa.deepdataagent.shared.config;

import com.linkroa.deepdataagent.shared.idempotency.IdempotencyRecord;
import com.linkroa.deepdataagent.shared.idempotency.IdempotencyRecordStore;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IdempotencyFilter} 单测：受保护端点判定、无键 / 未认证放行、
 * 同键同体重放首次结果、同键异体 409、首次成功登记 / 失败不登记、
 * 请求体对下游可重读。
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyFilterTest {

    private static final String BODY = "{\"name\":\"agent\"}";

    @Mock
    private IdempotencyRecordStore idempotencyRecordStore;

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter();
        ReflectionTestUtils.setField(filter, "idempotencyRecordStore", idempotencyRecordStore);
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockHttpServletRequest jsonPostRequest(String path, String idempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
            request.addHeader(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return request;
    }

    @Test
    void should_passThrough_when_doFilter_given_noIdempotencyKey() throws Exception {
        // given
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/agents", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chained.set(true);

        // when
        filter.doFilter(request, response, chain);

        // then（无键按普通创建处理，不触达幂等存储）
        assertTrue(chained.get());
        verifyNoInteractions(idempotencyRecordStore);
    }

    @Test
    void should_passThrough_when_doFilter_given_unprotectedPath() throws Exception {
        // given（非 /agents 端点：POST /environments 不受幂等保护）
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/environments", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chained.set(true);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertTrue(chained.get());
        verifyNoInteractions(idempotencyRecordStore);
    }

    @Test
    void should_passThrough_when_doFilter_given_noAuthenticatedUser() throws Exception {
        // given（未认证：身份由认证过滤器负责，本过滤器放行）
        AuthContext.clear();
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/agents", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chained.set(true);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertTrue(chained.get());
        verifyNoInteractions(idempotencyRecordStore);
    }

    @Test
    void should_replayFirstResult_when_doFilter_given_sameKeyAndSameBody() throws Exception {
        // given（同键同体：原样回放首次结果，不再进入业务链路）
        when(idempotencyRecordStore.find(1L, IdempotencyFilter.SCOPE_POST_AGENTS, "key-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        "key-1", 1L, "post_agents", sha256(BODY), 201, "{\"data\":{\"id\":\"agent_1\"}}")));
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/agents", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chained.set(true);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertEquals(201, response.getStatus());
        assertEquals("{\"data\":{\"id\":\"agent_1\"}}", response.getContentAsString());
        assertEquals(Boolean.FALSE, chained.get());
    }

    @Test
    void should_returnConflict_when_doFilter_given_sameKeyDifferentBody() throws Exception {
        // given（同键异体：409 conflict_error，不进入业务链路）
        when(idempotencyRecordStore.find(1L, IdempotencyFilter.SCOPE_POST_AGENTS, "key-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        "key-1", 1L, "post_agents", sha256("{\"name\":\"other\"}"), 201, "{}")));
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/agents", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        // when
        filter.doFilter(request, response, chain);

        // then
        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("conflict_error"));
    }

    @Test
    void should_persistRecordAndDeliverBody_when_doFilter_given_firstSubmitSucceeds() throws Exception {
        // given（首次提交成功：登记记录，且请求体对下游可重读）
        when(idempotencyRecordStore.find(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/agents", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            downstreamBody.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(200);
            httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            httpResponse.getWriter().write("{\"data\":{\"id\":\"agent_1\"}}");
        };

        // when
        filter.doFilter(request, response, chain);

        // then
        assertEquals(BODY, downstreamBody.get());
        assertEquals("{\"data\":{\"id\":\"agent_1\"}}", response.getContentAsString());
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordStore).save(captor.capture());
        assertEquals("key-1", captor.getValue().idempotencyKey());
        assertEquals(1L, captor.getValue().ownerId());
        assertEquals(IdempotencyFilter.SCOPE_POST_AGENTS, captor.getValue().scope());
        assertEquals(sha256(BODY), captor.getValue().requestHash());
        assertEquals(200, captor.getValue().responseStatus());
        assertEquals("{\"data\":{\"id\":\"agent_1\"}}", captor.getValue().responseBody());
    }

    @Test
    void should_notPersist_when_doFilter_given_firstSubmitFails() throws Exception {
        // given（首次提交失败：不登记，允许客户端重试）
        when(idempotencyRecordStore.find(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest request = jsonPostRequest("/api/v1/cloud/agents", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(400);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertEquals(400, response.getStatus());
        verify(idempotencyRecordStore, never()).save(any());
    }
}