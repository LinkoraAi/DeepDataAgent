package com.linkroa.deepdataagent.auth.application.command;

/**
 * 登录用户命令。
 *
 * @param email       登录邮箱
 * @param rawPassword 原始密码（仅内存比对，不落日志）
 */
public record LoginUserCommand(String email, String rawPassword) {
}