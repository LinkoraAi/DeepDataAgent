package com.linkroa.deepdataagent.shared.exception;

import com.linkroa.deepdataagent.shared.result.ErrorEnvelope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalExceptionHandler} 统一错误信封映射单测（6.1 公共约定）。
 * <p>验证两件事：① 各异常 → 语义化 HTTP 状态 + 错误信封类别映射；
 * ② MUST NOT 出现「HTTP 200 包装业务错误」形态（除 SSE void 处理器外，
 * 所有返回信封的处理器状态码均为语义化 4xx/5xx）。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void should_returnInvalidRequestEnvelope_when_handleDeepDataAgentException_given_businessError() {
        // given
        DeepDataAgentException e = new DeepDataAgentException("业务规则不允许该操作");

        // when
        ErrorEnvelope envelope = handler.handleDeepDataAgentException(e);

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("业务规则不允许该操作", envelope.error().message());
        assertEquals("error", envelope.type());
        assertNotNull(envelope.requestId());
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("handleDeepDataAgentException"));
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleIllegalArgumentException_given_domainInvariant() {
        // given
        IllegalArgumentException e = new IllegalArgumentException("limit 必须在 1-100 之间");

        // when
        ErrorEnvelope envelope = handler.handleIllegalArgumentException(e);

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("limit 必须在 1-100 之间", envelope.error().message());
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleHttpMessageNotReadable_given_unparsableBody() {
        // given
        HttpMessageNotReadableException e = new HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null);

        // when
        ErrorEnvelope envelope = handler.handleHttpMessageNotReadableException(e);

        // then（对齐 spec 场景 THEN：Request body must be valid JSON.）
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("Request body must be valid JSON.", envelope.error().message());
    }

    @Test
    void should_returnNotFoundEnvelope_when_handleResourceNotFoundException_given_missingResource() {
        // given
        ResourceNotFoundException e = new ResourceNotFoundException("会话不存在");

        // when
        ErrorEnvelope envelope = handler.handleResourceNotFoundException(e);

        // then
        assertEquals("not_found_error", envelope.error().type());
        assertEquals(HttpStatus.NOT_FOUND, statusOf("handleResourceNotFoundException"));
    }

    @Test
    void should_returnConflictEnvelope_when_handleResourceConflict_given_occMismatch() {
        // given
        ResourceConflictException e = new ResourceConflictException("版本不匹配，请刷新后重试");

        // when
        ErrorEnvelope envelope = handler.handleResourceConflictException(e);

        // then（409 一律 conflict_error）
        assertEquals("conflict_error", envelope.error().type());
        assertEquals(HttpStatus.CONFLICT, statusOf("handleResourceConflictException"));
    }

    @Test
    void should_returnConflictEnvelope_when_handleDuplicateKeyException_given_concurrentInsert() {
        // given
        DuplicateKeyException e = new DuplicateKeyException("duplicate key");

        // when
        ErrorEnvelope envelope = handler.handleDuplicateKeyException(e);

        // then
        assertEquals("conflict_error", envelope.error().type());
        assertEquals(HttpStatus.CONFLICT, statusOf("handleDuplicateKeyException"));
    }

    @Test
    void should_returnAuthenticationAndPermissionAndRateLimitEnvelopes_when_handleGiven_authExceptions() {
        // given & when
        ErrorEnvelope unauthorized = handler.handleUnauthorizedException(new UnauthorizedException("未认证"));
        ErrorEnvelope forbidden = handler.handleForbiddenException(new ForbiddenException("禁止访问"));
        ErrorEnvelope rateLimited = handler.handleTooManyRequestsException(new TooManyRequestsException("过于频繁"));

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
        ErrorEnvelope envelope = handler.handleException(e);

        // then（兜底 500 api_error + 通用消息，不泄露内部细节）
        assertEquals("api_error", envelope.error().type());
        assertEquals("系统内部错误，请联系管理员", envelope.error().message());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, statusOf("handleException"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_returnInvalidRequestEnvelope_when_handleConstraintViolation_given_firstViolationMessage() {
        // given
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("名称不能为空");
        ConstraintViolationException e = new ConstraintViolationException(Set.of(violation));

        // when
        ErrorEnvelope envelope = handler.handleConstraintViolationException(e);

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertEquals("名称不能为空", envelope.error().message());
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleMissingServletRequestParameter_given_absentParam() {
        // given
        MissingServletRequestParameterException e = new MissingServletRequestParameterException("page", "Integer");

        // when
        ErrorEnvelope envelope = handler.handleMissingServletRequestParameterException(e);

        // then
        assertEquals("invalid_request_error", envelope.error().type());
        assertTrue(envelope.error().message().contains("page"));
    }

    @Test
    void should_returnInvalidRequestEnvelope_when_handleMaxUploadSizeExceeded_given_fileOver50Mb() {
        // given（multipart 单文件超 50MB 由容器抛出超限异常）
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(50L * 1024 * 1024);

        // when
        ErrorEnvelope envelope = handler.handleMaxUploadSizeExceededException(e);

        // then（spec「单文件超 50MB 被拒」→ 400 校验错误，不落兜底 500）
        assertEquals("invalid_request_error", envelope.error().type());
        assertTrue(envelope.error().message().contains("50MB"));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("handleMaxUploadSizeExceededException"));
    }

    @Test
    void should_returnApiErrorWithoutDetails_when_handleException_given_fileContentIntegrityFailure() {
        // given（文件记录存在但磁盘内容缺失 / SHA 不一致：一致性事故语义）
        FileContentIntegrityException e = new FileContentIntegrityException("文件内容存储缺失");

        // when（无专属处理器，落通用兜底）
        ErrorEnvelope envelope = handler.handleException(e);

        // then（500 api_error 信封，不泄露内部文件路径等实现细节）
        assertEquals("api_error", envelope.error().type());
        assertEquals("系统内部错误，请联系管理员", envelope.error().message());
    }

    @Test
    void should_neverWrapEnvelopeErrorsInHttp200_when_inspectHandlers_given_adviceContract() {
        // given（反射扫描所有返回错误信封的处理器方法）
        int inspected = 0;
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("handle") || method.getReturnType() != ErrorEnvelope.class) {
                continue;
            }
            // when
            ResponseStatus status = method.getAnnotation(ResponseStatus.class);
            inspected++;

            // then（SSE void 处理器除外，信封处理器 MUST 携带语义化非 200 状态）
            assertNotNull(status, method.getName() + " 缺少 @ResponseStatus");
            assertTrue(resolvedStatus(status) != HttpStatus.OK,
                    method.getName() + " MUST NOT 以 HTTP 200 包装错误");
        }
        assertTrue(inspected >= 12, "处理器覆盖数异常: " + inspected);
    }

    /** 读取处理器方法上的 @ResponseStatus 状态码（value 优先，code 仅显式设置时生效）。 */
    private HttpStatus statusOf(String methodName) {
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                ResponseStatus status = method.getAnnotation(ResponseStatus.class);
                assertNotNull(status, methodName + " 缺少 @ResponseStatus");
                return resolvedStatus(status);
            }
        }
        throw new AssertionError("未找到处理器方法: " + methodName);
    }

    /**
     * 复刻 Spring 对 {@code @ResponseStatus} 的解析语义：
     * {@code value()} 为显式声明入口，仅当 {@code code()} 被显式设置（非默认 500）时才以 code 为准。
     */
    private static HttpStatus resolvedStatus(ResponseStatus status) {
        return status.value() != HttpStatus.INTERNAL_SERVER_ERROR ? status.value() : status.code();
    }
}
