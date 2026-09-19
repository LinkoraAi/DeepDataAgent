package com.linkroa.deepdataagent.auth.application.command;

/**
 * 注册用户命令。
 *
 * @param email          登录邮箱
 * @param rawPassword    原始密码（仅在内存中用于散列，不落任何对象 / 日志）
 */
public record RegisterUserCommand(String email, String rawPassword) {
}