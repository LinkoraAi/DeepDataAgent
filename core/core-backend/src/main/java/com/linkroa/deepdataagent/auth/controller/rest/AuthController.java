package com.linkroa.deepdataagent.auth.controller.rest;

import com.linkroa.deepdataagent.auth.application.command.LoginUserCommand;
import com.linkroa.deepdataagent.auth.application.command.RegisterUserCommand;
import com.linkroa.deepdataagent.auth.application.convert.AuthCommandConvert;
import com.linkroa.deepdataagent.auth.application.service.AuthApplicationService;
import com.linkroa.deepdataagent.auth.controller.convert.AuthResponseConvert;
import com.linkroa.deepdataagent.auth.controller.request.LoginRequest;
import com.linkroa.deepdataagent.auth.controller.request.RegisterRequest;
import com.linkroa.deepdataagent.auth.controller.response.LoginResponse;
import com.linkroa.deepdataagent.auth.controller.response.UserResponse;
import com.linkroa.deepdataagent.auth.infrastructure.security.AuthRateLimiter;
import com.linkroa.deepdataagent.shared.exception.TooManyRequestsException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 REST 控制器（前缀 {@code /api/auth}）。
 * <p>登录 / 注册为公开端点，按 IP 与邮箱做固定窗口限流，防批量爆破与灌号；登出走撤销黑名单。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 登录限流：同一 IP / 邮箱每分钟上限。 */
    private static final int LOGIN_LIMIT_PER_MINUTE = 10;
    /** 注册限流：同一 IP 每分钟上限。 */
    private static final int REGISTER_LIMIT_PER_MINUTE = 5;

    @Resource
    private AuthApplicationService applicationService;
    @Resource
    private AuthRateLimiter authRateLimiter;

    @PostMapping("/register")
    public ApiResponse<UserResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest
    ) {
        checkRateLimit("register:ip", clientIp(servletRequest), REGISTER_LIMIT_PER_MINUTE);
        RegisterUserCommand command = AuthCommandConvert.INSTANCE.toRegisterCommand(request);
        return ApiResponse.success(AuthResponseConvert.INSTANCE.toUserResponse(applicationService.register(command)));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        checkRateLimit("login:ip", clientIp(servletRequest), LOGIN_LIMIT_PER_MINUTE);
        // 限流键与账号定位口径一致（trim + 小写归一），防止大小写 / 空格变体绕桶
        checkRateLimit("login:email", AuthApplicationService.normalizeEmail(request.email()), LOGIN_LIMIT_PER_MINUTE);
        LoginUserCommand command = AuthCommandConvert.INSTANCE.toLoginCommand(request);
        return ApiResponse.success(AuthResponseConvert.INSTANCE.toLoginResponse(applicationService.login(command)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        applicationService.logout(authorization);
        return ApiResponse.success(null);
    }

    private void checkRateLimit(String dimension, String key, int limit) {
        if (!authRateLimiter.tryAcquire(dimension + ":" + key, limit)) {
            throw new TooManyRequestsException("操作过于频繁，请稍后再试");
        }
    }

    /**
     * 客户端 IP：优先取 {@code X-Real-IP}（nginx 以覆盖式 {@code $remote_addr} 设置，不可伪造），
     * 缺失 / 空白时回落连接层 {@code getRemoteAddr()}。
     * <p>不读取 {@code X-Forwarded-For}：nginx 采用追加式 {@code $proxy_add_x_forwarded_for}，
     * 首元素由客户端自带；且后端端口可被直连绕过网关，XFF 整体不可信。</p>
     */
    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}