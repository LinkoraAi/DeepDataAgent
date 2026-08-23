package com.linkroa.deepdataagent.vault.application.query;

/**
 * 密钥分页查询
 */
public record ListSecretQuery(
        int page,
        int size
) {
}