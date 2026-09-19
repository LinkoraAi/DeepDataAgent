package com.linkroa.deepdataagent.shared.security;

import com.linkroa.deepdataagent.shared.exception.UnauthorizedException;

/**
 * 当前认证用户上下文（ThreadLocal），跨上下文共享。
 * <p>由 JWT 校验过滤器在请求进入时写入数字 user_id 并在 finally 中清理；
 * 各限界上下文的应用服务据此做 owner 隔离（不依赖任何具体 BC 的基础设施）。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    private AuthContext() {
    }

    /**
     * 写入当前请求线程的 user_id（由 JWT 过滤器在请求入口调用，须与该线程的清理成对）。
     *
     * @param userId 认证用户数字 ID
     */
    public static void setUserId(Long userId) {
        CURRENT_USER.set(userId);
    }

    /**
     * 读取当前请求线程的 user_id；未认证时为 null，供可匿名路径使用。
     *
     * @return 认证用户数字 ID，未认证返回 null
     */
    public static Long getUserId() {
        return CURRENT_USER.get();
    }

    /**
     * 取当前 user_id，未认证时抛未认证异常（供受保护接口显式获取）。
     */
    public static Long requireUserId() {
        Long userId = CURRENT_USER.get();
        if (userId == null) {
            throw new UnauthorizedException("未认证或凭证已失效");
        }
        return userId;
    }

    /**
     * 清理当前请求线程的 user_id，避免线程池复用导致身份残留（必须置于 finally）。
     */
    public static void clear() {
        CURRENT_USER.remove();
    }
}