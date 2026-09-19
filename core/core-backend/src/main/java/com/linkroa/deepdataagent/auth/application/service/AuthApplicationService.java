package com.linkroa.deepdataagent.auth.application.service;

import com.linkroa.deepdataagent.auth.application.command.LoginUserCommand;
import com.linkroa.deepdataagent.auth.application.command.RegisterUserCommand;
import com.linkroa.deepdataagent.auth.application.port.RevokedTokenStore;
import com.linkroa.deepdataagent.auth.application.validation.AuthValidator;
import com.linkroa.deepdataagent.auth.domain.model.User;
import com.linkroa.deepdataagent.auth.domain.repository.UserRepository;
import com.linkroa.deepdataagent.auth.infrastructure.security.JwtTokenProvider;
import com.linkroa.deepdataagent.auth.infrastructure.util.PasswordHashUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 认证应用服务：邮箱注册 / 登录 / 登出。
 * <p>明文密码仅在内存中散列 / 比对，不写入任何对象与日志；密码散列也不出现在响应中。
 * 邮箱统一小写归一化（见 {@link User} 紧凑构造器），登录 / 注册按归一化值落库与比对。</p>
 */
@Service
public class AuthApplicationService {

    @Resource
    private UserRepository userRepository;
    @Resource
    private JwtTokenProvider jwtTokenProvider;
    @Resource
    private RevokedTokenStore revokedTokenStore;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 邮箱注册：校验密码强度 → 检查邮箱未被注册 → bcrypt 散列 → 落库。
     */
    public User register(RegisterUserCommand command) {
        String normalizedEmail = normalizeEmail(command.email());
        AuthValidator.validatePassword(command.rawPassword());
        String passwordHash = PasswordHashUtil.hash(command.rawPassword());
        User created = transactionTemplate.execute(status -> {
            userRepository.findByEmailForUpdate(normalizedEmail).ifPresent(existing -> {
                throw new ResourceConflictException("邮箱已被注册");
            });
            return userRepository.save(User.register(null, normalizedEmail, passwordHash));
        });
        return created;
    }

    /**
     * 邮箱登录：校验邮箱与密码，签发 7 天 HS256 JWT（sub = 数字 user_id）。
     * <p>邮箱不存在时同样执行一次 bcrypt 兜底比对，抹平「账号存在 / 不存在」的响应时延差（防枚举）。</p>
     */
    public LoginResult login(LoginUserCommand command) {
        User user = userRepository.findByEmail(normalizeEmail(command.email())).orElse(null);
        if (user == null) {
            // 无条件消耗一次 bcrypt，使时延与真实账号比对一致
            PasswordHashUtil.matches(command.rawPassword(), PasswordHashUtil.dummyHash());
            throw new UnauthorizedException("邮箱或密码错误");
        }
        if (!PasswordHashUtil.matches(command.rawPassword(), user.passwordHash())) {
            throw new UnauthorizedException("邮箱或密码错误");
        }
        String token = jwtTokenProvider.generateToken(user.userId());
        return new LoginResult(token, jwtTokenProvider.expiresInSeconds(), user.userId(), user.email());
    }

    /**
     * 登出：将当前 token 的 {@code jti} 加入撤销黑名单（TTL = 原 token 剩余有效期），
     * 撤销期间该 token 无法再通过认证过滤器校验。
     */
    public void logout(String authorizationHeader) {
        String token = JwtTokenProvider.resolveBearerToken(authorizationHeader);
        if (token == null) {
            throw new UnauthorizedException("无效凭证");
        }
        try {
            Claims claims = jwtTokenProvider.parseClaims(token);
            String jti = claims.getId();
            long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000L;
            if (jti != null && remainingSeconds > 0) {
                revokedTokenStore.revoke(jti, remainingSeconds);
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("无效或已过期的凭证");
        }
    }

    /**
     * 邮箱归一化（trim + 小写），与 {@link User} 紧凑构造器口径一致。
     * <p>公开供协议层复用：登录限流键须经同一口径归一，防止大小写 / 空格变体绕桶。</p>
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}