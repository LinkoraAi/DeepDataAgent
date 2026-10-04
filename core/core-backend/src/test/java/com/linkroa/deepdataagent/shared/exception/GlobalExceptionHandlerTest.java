package com.linkroa.deepdataagent.shared.exception;

import com.linkroa.deepdataagent.shared.result.ErrorEnvelope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GlobalExceptionHandler} 统一错误信封映射单测（6.1 公共约定）。
 * <p>验证三件事：① 各异常 → 语义化 HTTP 状态 + 错误信封类别映射；
 * ② 信封以显式 {@code application/json} 写出（D3：SSE 端点 {@code produces=text/event-stream}
 * 的 preset Content-Type 不得令信封写出失败落入 500）；③ 处理器返回形态契约
 * （信封处理器返回 {@link ResponseEntity} 且不再依赖 {@code @ResponseStatus}；
 * 仅 SSE 断连 / 超时两个处理器保持 {@code void}）。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void should_returnInvalidRequestEnvelope_when_handleDeepDataAgentException_given_businessError() {
        // given
        DeepDataAgentException e = new DeepDataAgentException("业务规则不允许该操作");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleDeepDataAgentException(e);

        // then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        ErrorEnvelope envelope = response.getBody();
        assertNotNull(envelope);
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("业务规则不允许该操作", envelope.error().message());
        assertEquals("error", envelope.type());
        assertNotNull(envelope.requestId());
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleIllegalArgumentException_given_domainInvariant() {
        // given
        IllegalArgumentException e = new IllegalArgumentException("limit 必须在 1-100 之间");

        // when
        ErrorEnvelope envelope = body(handler.handleIllegalArgumentException(e));

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("limit 必须在 1-100 之间", envelope.error().message());
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleHttpMessageNotReadable_given_unparsableBody() {
        // given
        HttpMessageNotReadableException e = new HttpMessageNotReadableException(
                "JSON parse error", (org.springframework.http.HttpInputMessage) null);

        // when
        ErrorEnvelope envelope = body(handler.handleHttpMessageNotReadableException(e));

        // then（对齐 spec 场景 THEN：Request body must be valid JSON.）
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("Request body must be valid JSON.", envelope.error().message());
    }

    @Test
    void should_returnNotFoundEnvelope_when_handleResourceNotFoundException_given_missingResource() {
        // given
        ResourceNotFoundException e = new ResourceNotFoundException("会话不存在");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleResourceNotFoundException(e);

        // then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("not_found_error", body(response).error().type());
    }

    @Test
    void should_returnNotFoundEnvelope_when_handleNoResourceFoundException_given_unknownRoute() {
        // given（Spring 6.1+ 未匹配路由抛 NoResourceFoundException：D4 前会落兜底 500）
        NoResourceFoundException e = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "cloud/nope-not-a-route", "");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleNoResourceFoundException(e);

        // then（未知路由归 404 not_found_error）
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("not_found_error", body(response).error().type());
    }

    @Test
    void should_returnMethodNotAllowedEnvelope_when_handleMethodNotSupported_given_wrongMethod() {
        // given（对已知路径用未映射方法：D4 前会落兜底 500）
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("POST");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleHttpRequestMethodNotSupportedException(e);

        // then（405，错误类别归无效请求同族）
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("invalid_request_error", body(response).error().type());
        assertTrue(body(response).error().message().contains("POST"));
    }

    @Test
    void should_returnConflictEnvelope_when_handleResourceConflict_given_occMismatch() {
        // given
        ResourceConflictException e = new ResourceConflictException("版本不匹配，请刷新后重试");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleResourceConflictException(e);

        // then（409 一律 conflict_error）
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("conflict_error", body(response).error().type());
    }

    @Test
    void should_returnConflictEnvelope_when_handleDuplicateKeyException_given_concurrentInsert() {
        // given
        DuplicateKeyException e = new DuplicateKeyException("duplicate key");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleDuplicateKeyException(e);

        // then
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("conflict_error", body(response).error().type());
    }

    @Test
    void should_returnAuthenticationAndPermissionAndRateLimitEnvelopes_when_handleGiven_authExceptions() {
        // given & when
        ErrorEnvelope unauthorized = body(handler.handleUnauthorizedException(new UnauthorizedException("未认证")));
        ErrorEnvelope forbidden = body(handler.handleForbiddenException(new ForbiddenException("禁止访问")));
        ErrorEnvelope rateLimited = body(handler.handleTooManyRequestsException(new TooManyRequestsException("过于频繁")));

        // then
        assertEquals("authentication_error", unauthorized.error().type());
        assertEquals("permission_error", forbidden.error().type());
        assertEquals("rate_limit_error", rateLimited.error().type());
    }

    @Test
    void should_returnApiErrorEnvelope_when_handleException_given_unexpectedFailure() {
        // given
        Exception e = new RuntimeException("boom");

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleException(e);

        // then（兜底 500 api_error + 通用消息，不泄露内部细节）
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("api_error", body(response).error().type());
        assertEquals("系统内部错误，请联系管理员", body(response).error().message());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_returnInvalidRequestEnvelope_when_handleConstraintViolation_given_firstViolationMessage() {
        // given
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("名称不能为空");
        ConstraintViolationException e = new ConstraintViolationException(Set.of(violation));

        // when
        ErrorEnvelope envelope = body(handler.handleConstraintViolationException(e));

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("名称不能为空", envelope.error().message());
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleMissingServletRequestParameter_given_absentParam() {
        // given
        MissingServletRequestParameterException e =
                new MissingServletRequestParameterException("page", "Integer");

        // when
        ErrorEnvelope envelope = body(handler.handleMissingServletRequestParameterException(e));

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertTrue(envelope.error().message().contains("page"));
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleMaxUploadSizeExceeded_given_fileOver50Mb() {
        // given（multipart 单文件超 50MB 由容器抛出超限异常）
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(50L * 1024 * 1024);

        // when
        ResponseEntity<ErrorEnvelope> response = handler.handleMaxUploadSizeExceededException(e);

        // then（spec「单文件超 50MB 被拒」→ 400 校验错误，不落兜底 500）
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_request_error", body(response).error().type());
        assertTrue(body(response).error().message().contains("50MB"));
    }

    @Test
    void should_returnApiErrorWithoutDetails_when_handleException_given_fileContentIntegrityFailure() {
        // given（文件记录存在但磁盘内容缺失 / SHA 不一致：一致性事故语义）
        FileContentIntegrityException e = new FileContentIntegrityException("文件内容存储缺失");

        // when（无专属处理器，落通用兜底）
        ErrorEnvelope envelope = body(handler.handleException(e));

        // then（500 api_error 信封，不泄露内部文件路径等实现细节）
        assertEquals("api_error", envelope.error().type());
        assertEquals("系统内部错误，请联系管理员", envelope.error().message());
    }

    /**
     * D3 核心回归：SSE 端点（{@code produces=text/event-stream}）在同步段抛业务校验异常时，
     * 错误信封 MUST 以 {@code application/json} 写出并返回 4xx，而非因 preset Content-Type
     * 协商失败落兜底 500。
     */
    @Test
    void should_returnJsonBadRequest_when_streamMappingThrows_given_eventStreamProduces() throws Exception {
        // given：仅声明 produces=text/event-stream 的端点，方法内抛 IllegalArgumentException
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SsePresetController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // when & then：400 + JSON 信封（修复前为 500 / 写出失败）
        mockMvc.perform(get("/test/sse-preset").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void should_exposeJsonEnvelopeContract_when_inspectAdvice_given_responseHandlers() {
        // given（反射扫描全部 handle* 处理器）
        long envelopeHandlers = 0;
        long voidHandlers = 0;
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("handle")) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == ResponseEntity.class) {
                // then①：信封处理器 MUST NOT 再用 @ResponseStatus（状态码内聚于 ResponseEntity）
                assertNull(method.getAnnotation(ResponseStatus.class),
                        method.getName() + " 不应再依赖 @ResponseStatus");
                envelopeHandlers++;
            } else if (returnType == void.class) {
                voidHandlers++;
            } else {
                throw new AssertionError("处理器须返回 ResponseEntity 或 void: " + method.getName());
            }
        }
        // then②：信封处理器覆盖数与 void 处理器数（仅 SSE 断连 / 超时）
        assertTrue(envelopeHandlers >= 22, "信封处理器覆盖数异常: " + envelopeHandlers);
        assertEquals(2, voidHandlers, "仅 SSE 断连 / 超时两个 void 处理器");
    }

    /** 提取成功信封体并断言非空。 */
    private static ErrorEnvelope body(ResponseEntity<ErrorEnvelope> response) {
        ErrorEnvelope envelope = response.getBody();
        assertNotNull(envelope, "错误响应必须携带信封体");
        return envelope;
    }

    /** 测试用 SSE 端点：声明 {@code produces=text/event-stream} 并在同步段抛业务校验异常。 */
    @RestController
    static class SsePresetController {

        /**
         * 模拟 SSE 流端点同步段抛参错（如非法 {@code event_deltas[]}）。
         *
         * @return 永不返回（抛异常）
         */
        @GetMapping(value = "/test/sse-preset", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Object stream() {
            throw new IllegalArgumentException("Last-Event-ID 指向归档会话事件");
        }
    }
}