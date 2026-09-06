package com.linkroa.deepdataagent.shared.exception;

import com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException;
import com.linkroa.deepdataagent.storage.domain.exception.FileKeyConflictException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlobalExceptionHandler} 文件对象存储相关处理器的单元测试：
 * 上传超限（413）、文件键冲突（409）、桶不存在（404）与桶操作冲突（409）的转译与响应状态码。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void should_return409WithCode_when_handleFileKeyConflictException_givenConflict() {
        FileKeyConflictException exception = new FileKeyConflictException("文件对象已存在: module/biz/a.txt");

        ApiResponse<Void> response = handler.handleFileKeyConflictException(exception);

        assertFalse(response.success());
        assertEquals("409", response.code());
        assertTrue(response.message().contains("文件对象已存在"));
    }

    @Test
    void should_annotateConflictStatus_when_handleFileKeyConflictException() {
        ResponseStatus annotation = resolveResponseStatus("handleFileKeyConflictException", FileKeyConflictException.class);

        assertNotNull(annotation);
        assertEquals(HttpStatus.CONFLICT, annotation.value());
    }

    @Test
    void should_return404WithCode_when_handleBucketNotFoundException_givenMissingBucket() {
        BucketNotFoundException exception = new BucketNotFoundException("桶不存在: test-bucket");

        ApiResponse<Void> response = handler.handleBucketNotFoundException(exception);

        assertFalse(response.success());
        assertEquals("404", response.code());
        assertTrue(response.message().contains("桶不存在"));
    }

    @Test
    void should_annotateNotFoundStatus_when_handleBucketNotFoundException() {
        ResponseStatus annotation = resolveResponseStatus("handleBucketNotFoundException", BucketNotFoundException.class);

        assertNotNull(annotation);
        assertEquals(HttpStatus.NOT_FOUND, annotation.value());
    }

    @Test
    void should_return409WithCode_when_handleBucketConflictException_givenConflict() {
        BucketConflictException exception = new BucketConflictException("桶已存在: test-bucket");

        ApiResponse<Void> response = handler.handleBucketConflictException(exception);

        assertFalse(response.success());
        assertEquals("409", response.code());
        assertTrue(response.message().contains("桶已存在"));
    }

    @Test
    void should_annotateConflictStatus_when_handleBucketConflictException() {
        ResponseStatus annotation = resolveResponseStatus("handleBucketConflictException", BucketConflictException.class);

        assertNotNull(annotation);
        assertEquals(HttpStatus.CONFLICT, annotation.value());
    }

    @Test
    void should_return413WithCode_when_handleMaxUploadSizeExceededException_givenOversizedUpload() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(100L);

        ApiResponse<Void> response = handler.handleMaxUploadSizeExceededException(exception);

        assertFalse(response.success());
        assertEquals("413", response.code());
        assertTrue(response.message().contains("系统大小限制"));
    }

    @Test
    void should_annotatePayloadTooLargeStatus_when_handleMaxUploadSizeExceededException() {
        ResponseStatus annotation = resolveResponseStatus("handleMaxUploadSizeExceededException",
                MaxUploadSizeExceededException.class);

        assertNotNull(annotation);
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, annotation.value());
    }

    /**
     * 反射解析处理器方法上的 {@link ResponseStatus} 注解。
     *
     * @param methodName    处理器方法名
     * @param parameterType 处理器方法入参类型
     * @return 注解（不存在时返回 null）
     */
    private ResponseStatus resolveResponseStatus(String methodName, Class<?> parameterType) {
        Method method;
        try {
            method = GlobalExceptionHandler.class.getMethod(methodName, parameterType);
        } catch (NoSuchMethodException e) {
            return null;
        }
        return method.getAnnotation(ResponseStatus.class);
    }
}