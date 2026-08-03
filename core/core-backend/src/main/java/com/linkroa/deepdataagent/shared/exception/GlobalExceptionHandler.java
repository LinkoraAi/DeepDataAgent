package com.linkroa.deepdataagent.shared.exception;

import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * 全局异常处理器
 * <p>统一捕获并处理Controller层抛出的异常，转换为标准化的ApiResponse返回给前端，
 * 避免Spring默认的500错误页面，确保前端能够获取具体的错误信息。</p>
 * <p>处理的异常类型包括：</p>
 * <ul>
 *   <li>DeepDataAgentException: 业务逻辑异常，返回400错误码</li>
 *   <li>MethodArgumentNotValidException/BindException: 参数校验失败，返回400错误码</li>
 *   <li>ConstraintViolationException: JSR-303校验异常，返回400错误码</li>
 *   <li>MissingServletRequestParameterException: 缺少请求参数，返回400错误码</li>
 *   <li>IllegalArgumentException: 非法参数异常，返回400错误码</li>
 *   <li>Exception: 其他未知异常，返回500错误码</li>
 * </ul>
 *
 * @author system
 * @since 2026-05-26
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务逻辑异常
     * <p>例如：数据源名称重复、数据源不存在等业务规则校验失败</p>
     *
     * @param e 业务逻辑异常
     * @return 包含错误码和错误消息的ApiResponse
     */
    @ExceptionHandler(DeepDataAgentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleDeepDataAgentException(DeepDataAgentException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error("400", e.getMessage());
    }

    /**
     * 处理@RequestBody参数校验失败异常
     * <p>例如：@Valid注解校验请求体参数失败</p>
     *
     * @param e 参数校验异常
     * @return 包含第一个校验错误信息的ApiResponse
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return ApiResponse.error("400", message);
    }

    /**
     * 处理表单/查询参数绑定失败异常
     *
     * @param e 绑定异常
     * @return 包含第一个校验错误信息的ApiResponse
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数绑定失败: {}", message);
        return ApiResponse.error("400", message);
    }

    /**
     * 处理JSR-303 ConstraintViolation异常
     *
     * @param e 校验异常
     * @return 包含第一个校验错误信息的ApiResponse
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().iterator().next().getMessage();
        log.warn("约束校验失败: {}", message);
        return ApiResponse.error("400", message);
    }

    /**
     * 处理缺少请求参数异常
     *
     * @param e 缺少参数异常
     * @return 包含错误信息的ApiResponse
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String message = "缺少请求参数: " + e.getParameterName();
        log.warn(message);
        return ApiResponse.error("400", message);
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常
     * @return 包含错误信息的ApiResponse
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ApiResponse.error("400", e.getMessage());
    }

    /**
     * 处理非法状态异常
     * <p>例如：数据源状态不正确（启用/禁用/删除状态校验失败）等状态异常</p>
     *
     * @param e 非法状态异常
     * @return 包含错误信息的ApiResponse
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleIllegalStateException(IllegalStateException e) {
        log.warn("状态异常: {}", e.getMessage());
        return ApiResponse.error("400", e.getMessage());
    }

    /**
     * 处理异步请求不可用异常
     * <p>当客户端断开 SSE 连接后，后端尝试 flush 或 completeWithError 时会抛出此异常。
     * 属于正常行为（如用户切换会话中断了正在进行的分析），无需记录为 ERROR 级别。</p>
     *
     * @param e 异步请求不可用异常
     * @return 空响应（客户端已断开，无需返回数据）
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.warn("客户端断开连接，SSE 流已取消: {}", e.getMessage());
    }

    /**
     * 处理异步请求超时异常
     * <p>SSE 连接超时（如 30 秒无数据传输）时触发。此异常发生在 SSE 端点，
     * Content-Type 为 text/event-stream，不能返回 ApiResponse，只能返回 void。</p>
     * <p>注意：此处理器必须在通用 Exception 处理器之前，否则会返回 ApiResponse 导致二次异常。</p>
     *
     * @param e 异步请求超时异常
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeoutException(AsyncRequestTimeoutException e) {
        log.warn("SSE 连接超时: {}", e.getMessage());
        // 不返回任何内容，SSE 连接已超时，无法响应
    }

    /**
     * 处理其他未知异常
     * <p>兜底策略：返回500错误码和通用错误消息，记录详细日志用于排查</p>
     *
     * @param e 未知异常
     * @return 包含错误信息的ApiResponse
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统内部异常", e);
        return ApiResponse.error("500", "系统内部错误，请联系管理员");
    }
}
