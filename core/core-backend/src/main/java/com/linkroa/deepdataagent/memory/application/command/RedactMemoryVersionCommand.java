package com.linkroa.deepdataagent.memory.application.command;

/**
 * 版本级 redact 命令：清除指定记忆版本的内容与校验值（幂等，已脱敏直接返回现状）。
 *
 * @param storeId   记忆库业务 ID
 * @param versionId 版本业务 ID（memver_ 前缀）
 */
public record RedactMemoryVersionCommand(
        String storeId,
        String versionId
) {
}
