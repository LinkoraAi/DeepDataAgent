package com.linkroa.deepdataagent.vault.application.command;

/**
 * 添加凭证命令（明文传入，应用层 AES-GCM 加密为 ciphertext 落库）。
 *
 * @param vaultId      所属保管库业务ID
 * @param authType     凭证鉴权类型字符串（static_bearer / mcp_oauth，应用层解析为领域枚举）
 * @param mcpServerUrl 绑定的 MCP 服务器 URL（必填，运行时按此匹配注入鉴权）
 * @param token        凭证明文（必填，仅应用层内存持有与加密）
 */
public record AddVaultCredentialCommand(
        String vaultId,
        String authType,
        String mcpServerUrl,
        String token
) {
}
