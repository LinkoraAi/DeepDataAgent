package com.linkroa.deepdataagent.shared.exception;

import com.linkroa.deepdataagent.shared.result.ErrorEnvelope;
import com.linkroa.deepdataagent.shared.result.ErrorType;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器（统一错误信封，对齐 shared/api-conventions spec）。
 * <p>全部错误响应使用 {@link ErrorEnvelope} 形状
 * （{@code {"error":{"type","message"},"request_id":"...","type":"error"}}），
 * HTTP 状态码 MUST 与语义匹配（400/401/403/404/409/429/500），
 * <b>不再以 HTTP 200 包装业务错误</b>（BREAKING：旧形态 {@code 200 + ApiResponse{success:false}}
 * 已整体收敛）。类别映射：409 默认 {@code conflict_error}，<b>唯一特例为会话忙 / 会话资源冲突
 * （{@link SessionBusyException} 等契约明文路径）的 {@code invalid_request_error}</b>；
 * 参数 / 校验 / 领域不变量类归 {@code invalid_request_error}；未预期异常兜底 {@code api_error}。</p>
 * <p>处理的异常类型包括：</p>
 * <ul>
 *   <li>DeepDataAgentException: 业务逻辑异常，400 invalid_request_error</li>
 *   <li>MethodArgumentNotValidException/BindException/ConstraintViolationException:
 *       参数校验失败，400 invalid_request_error</li>
 *   <li>MissingServletRequestParameterException: 缺少请求参数，400 invalid_request_error</li>
 *   <li>HttpMessageNotReadableException: 请求体不可解析（含超限截断），400 invalid_request_error</li>
 *   <li>IllegalArgumentException/IllegalStateException: 领域不变量 / 状态校验，400 invalid_request_error</li>
 *   <li>ResourceNotFoundException: 资源不存在，404 not_found_error</li>
 *   <li>UnauthorizedException: 认证失败，401 authentication_error</li>
 *   <li>ForbiddenException: 禁止访问，403 permission_error</li>
 *   <li>ResourceConflictException / DuplicateKeyException: 冲突，409 conflict_error</li>
 *   <li>SessionBusyException: 会话忙（契约明文路径），409 invalid_request_error</li>
 *   <li>TooManyRequestsException: 限流，429 rate_limit_error</li>
 *   <li>RequestTooLargeException: 请求载荷超限，413 request_too_large_error</li>
 *   <li>MaxUploadSizeExceededException: multipart 上传超限（50MB），400 invalid_request_error</li>
 *   <li>Exception: 其他未知异常，500 api_error</li>
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
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(DeepDataAgentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleDeepDataAgentException(DeepDataAgentException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getMessage());
    }

    /**
     * 处理@RequestBody参数校验失败异常
     * <p>例如：@Valid注解校验请求体参数失败</p>
     *
     * @param e 参数校验异常
     * @return 400 invalid_request_error 错误信封（携带第一个校验错误信息）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, message);
    }

    /**
     * 处理表单/查询参数绑定失败异常
     *
     * @param e 绑定异常
     * @return 400 invalid_request_error 错误信封（携带第一个校验错误信息）
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleBindException(BindException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数绑定失败: {}", message);
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, message);
    }

    /**
     * 处理JSR-303 ConstraintViolation异常
     *
     * @param e 校验异常
     * @return 400 invalid_request_error 错误信封（携带第一个约束错误信息）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().iterator().next().getMessage();
        log.warn("约束校验失败: {}", message);
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, message);
    }

    /**
     * 处理缺少请求参数异常
     *
     * @param e 缺少参数异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String message = "缺少请求参数: " + e.getParameterName();
        log.warn(message);
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, message);
    }

    /**
     * 处理请求体不可解析异常
     * <p>JSON 语法错误、类型不匹配，以及请求体超过 JSON 上限被截断致解析失败等
     * 场景统一返回 400（对齐 spec「请求限额」场景 THEN：Request body must be valid JSON.）。</p>
     *
     * @param e 请求体不可解析异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体不可解析: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, "Request body must be valid JSON.");
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getMessage());
    }

    /**
     * 处理非法状态异常
     * <p>例如：数据源状态不正确（启用/禁用/删除状态校验失败）等状态异常</p>
     *
     * @param e 非法状态异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleIllegalStateException(IllegalStateException e) {
        log.warn("状态异常: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getMessage());
    }

    /**
     * 处理资源不存在异常（HTTP 404）
     * <p>Agent / 模型配置 / 技能 / 版本等资源不存在时抛出</p>
     *
     * @param e 资源不存在异常
     * @return 404 not_found_error 错误信封
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorEnvelope handleResourceNotFoundException(ResourceNotFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.NOT_FOUND_ERROR, e.getMessage());
    }

    /**
     * 处理未认证异常（HTTP 401）
     * <p>登录失败、缺失 / 非法 / 过期 JWT 等认证失败场景抛出</p>
     *
     * @param e 未认证异常
     * @return 401 authentication_error 错误信封
     */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorEnvelope handleUnauthorizedException(UnauthorizedException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.AUTHENTICATION_ERROR, e.getMessage());
    }

    /**
     * 处理禁止访问异常（HTTP 403）
     * <p>资源存在但操作被策略禁止的场景，如不可下载文件（downloadable=false）
     * 请求 {@code /files/{id}/content} 直接下载端点</p>
     *
     * @param e 禁止访问异常
     * @return 403 permission_error 错误信封
     */
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorEnvelope handleForbiddenException(ForbiddenException e) {
        log.warn("禁止访问: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.PERMISSION_ERROR, e.getMessage());
    }

    /**
     * 处理资源冲突异常（HTTP 409）
     * <p>名称重复、仍被引用不可删除、OCC 版本不匹配、活跃执行期并发写入等冲突场景抛出；
     * 本系统规范 409 一律 {@code conflict_error}。</p>
     *
     * @param e 资源冲突异常
     * @return 409 conflict_error 错误信封
     */
    @ExceptionHandler(ResourceConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorEnvelope handleResourceConflictException(ResourceConflictException e) {
        log.warn("资源冲突: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.CONFLICT_ERROR, e.getMessage());
    }

    /**
     * 处理会话忙异常（HTTP 409，错误 type 为 {@code invalid_request_error}）
     * <p>公开契约明文路径：会话存在活跃 turn 时新 user.message 被拒、创建期引用的文件
     * 存在但未就绪——409 的错误 type 为 {@code invalid_request_error}（design D7 特例），
     * <b>不改</b>其他资源通用 409=conflict_error 的口径。</p>
     *
     * @param e 会话忙异常
     * @return 409 invalid_request_error 错误信封
     */
    @ExceptionHandler(SessionBusyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorEnvelope handleSessionBusyException(SessionBusyException e) {
        log.warn("会话忙: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getMessage());
    }

    /**
     * 处理请求过于频繁异常（HTTP 429）
     * <p>认证接口（登录 / 注册）触发防爆破限流时抛出</p>
     *
     * @param e 限流异常
     * @return 429 rate_limit_error 错误信封
     */
    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorEnvelope handleTooManyRequestsException(TooManyRequestsException e) {
        log.warn("请求过于频繁: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.RATE_LIMIT_ERROR, e.getMessage());
    }

    /**
     * 处理数据库唯一键冲突异常（HTTP 409）
     * <p>并发写入撞唯一索引 / 唯一约束时由 Spring 包装为 {@link DuplicateKeyException}
     * 抛出（如 Agent 并发重名、技能并发发布版本号撞 {@code uk_skill_version}），
     * 防止落兜底 500，统一转译为资源冲突语义提示客户端重试。</p>
     *
     * @param e 唯一键冲突异常
     * @return 409 conflict_error 错误信封
     */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorEnvelope handleDuplicateKeyException(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMostSpecificCause().getMessage());
        return ErrorEnvelope.of(ErrorType.CONFLICT_ERROR, "资源已存在或已被并发占用，请刷新后重试");
    }

    /**
     * 处理对象存储技术异常（HTTP 500）。
     * <p>shared 对象存储（local / S3 兼容）未被业务装配层翻译的技术失败统一在此兜底：
     * 对外只返回通用提示（不泄露 endpoint / 凭证 / 内部 key 细节），完整信息记日志。
     * 正常的「文件记录在、内容对象缺失」一致性事故已在 file 业务层翻译为 500
     * {@link FileContentIntegrityException}，不会走到本处理器。</p>
     *
     * @param e 对象存储技术异常
     * @return 500 api_error 错误信封
     */
    @ExceptionHandler(ObjectStorageException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorEnvelope handleObjectStorageException(ObjectStorageException e) {
        if (e.kind() == ObjectStorageErrorKind.UNAVAILABLE) {
            log.error("对象存储不可用: {}", e.getMessage(), e);
        } else {
            log.error("对象存储操作失败: kind={}, message={}", e.kind(), e.getMessage(), e);
        }
        return ErrorEnvelope.of(ErrorType.API_ERROR, "对象存储服务暂时不可用，请稍后重试");
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
     * Content-Type 为 text/event-stream，不能返回错误信封，只能返回 void。</p>
     * <p>注意：此处理器必须在通用 Exception 处理器之前，否则会返回错误信封导致二次异常。</p>
     *
     * @param e 异步请求超时异常
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeoutException(AsyncRequestTimeoutException e) {
        log.warn("SSE 连接超时: {}", e.getMessage());
        // 不返回任何内容，SSE 连接已超时，无法响应
    }

    /**
     * 处理 API 版本无效异常（未知版本 / 无法解析）
     * <p>API 版本化（Spring 7）校验失败时抛出，如请求 {@code /api/v9/...} 但
     * 接口仅声明版本 1。此类异常为 {@code ResponseStatusException} 子类，
     * 需在兜底 Exception 处理器之前显式处理，返回 400 而非 500。</p>
     *
     * @param e 版本无效异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(InvalidApiVersionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleInvalidApiVersionException(InvalidApiVersionException e) {
        log.warn("API 版本无效: {}", e.getReason());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getReason());
    }

    /**
     * 处理缺失 API 版本异常
     *
     * @param e 缺失版本异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(MissingApiVersionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleMissingApiVersionException(MissingApiVersionException e) {
        log.warn("API 版本缺失: {}", e.getReason());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getReason());
    }

    /**
     * 处理检索附图入参非法异常（真实 HTTP 400）
     * <p>检索端点入口附图校验（数量/字节/类型白名单/base64/魔数）任一附件非法时整请求拒绝，
     * 错误信息含第几张附图与原因定位。与 {@link DeepDataAgentException} 的「HTTP 200 +
     * 响应体 code=400」业务错误包装形态刻意区分：附图非法属客户端入参错误，按标准状态码
     * 400 发布（形态对齐 {@link InvalidApiVersionException} 真实 4xx 先例）。
     * 本 handler 按最具体类型匹配优先于父类 {@link DeepDataAgentException} 的 200 包装 handler。</p>
     *
     * @param e 检索附图入参非法异常
     * @return 包含错误定位信息的ApiResponse
     */
    @ExceptionHandler(InvalidQueryImageException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleInvalidQueryImageException(InvalidQueryImageException e) {
        log.warn("检索附图入参非法: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, e.getMessage());
    }

    /**
     * 处理请求载荷超限异常（HTTP 413）
     * <p>请求体 / multipart 包体超过服务端业务限额（如技能包 zip 超过 50MB）时抛出，
     * 对齐 spec「超限包被拒」场景：zip 体积超限返回 413 {@code request_too_large_error}。</p>
     *
     * @param e 请求载荷超限异常
     * @return 413 request_too_large_error 错误信封
     */
    @ExceptionHandler(RequestTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ErrorEnvelope handleRequestTooLargeException(RequestTooLargeException e) {
        log.warn("请求载荷超限: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.REQUEST_TOO_LARGE_ERROR, e.getMessage());
    }

    /**
     * 处理 multipart 上传超限异常（HTTP 400）
     * <p>单文件超过 {@code spring.servlet.multipart.max-file-size}（50MB，对齐 File 领域上限）
     * 或请求总量超过 {@code max-request-size} 时由容器抛出；对齐 spec 场景
     * 「单文件超 50MB 被拒 → 400 校验错误」，防止落兜底 500。</p>
     *
     * @param e 上传超限异常
     * @return 400 invalid_request_error 错误信封
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传大小超限: {}", e.getMessage());
        return ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, "上传文件超过 50MB 上限");
    }

    /**
     * 处理其他未知异常
     * <p>兜底策略：500 api_error 信封 + 通用错误消息，记录详细日志用于排查</p>
     *
     * @param e 未知异常
     * @return 500 api_error 错误信封
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorEnvelope handleException(Exception e) {
        log.error("系统内部异常", e);
        return ErrorEnvelope.of(ErrorType.API_ERROR, "系统内部错误，请联系管理员");
    }
}
