package com.linkroa.deepdataagent.vault.application.command;

/**
 * 创建密钥命令（明文传入，应用层加密落库）
 *
 * @param name  名称
 * @param value 密钥值（明文）
 */
public record CreateSecretCommand(
        String name,
        String value
) {
}