package com.linkroa.deepdataagent.vault.application.command;

/**
 * 创建保管库命令。
 *
 * @param displayName 显示名称（必填）
 * @param metadata    元数据 JSON（可选）
 */
public record CreateVaultCommand(
        String displayName,
        String metadata
) {
}