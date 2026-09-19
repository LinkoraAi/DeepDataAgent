package com.linkroa.deepdataagent.auth.application.service;

import com.linkroa.deepdataagent.auth.domain.model.User;

/**
 * 登录结果（token + 有效期 + 用户概要，面向协议层已展平的纯 record）。
 *
 * @param token            签发的 HS256 JWT
 * @param expiresInSeconds 有效期（秒）
 * @param userId           数字 user_id
 * @param email            登录邮箱
 */
public record LoginResult(String token, long expiresInSeconds, Long userId, String email) {
}