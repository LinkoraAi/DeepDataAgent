package com.linkroa.deepdataagent.vault.application.query;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;

/**
 * 凭证游标列表查询（Cursor 约定）。
 *
 * @param vaultId   所属保管库业务 ID（必填，owner 隔离由应用层校验）
 * @param urlSearch 绑定的 MCP 服务器 URL 模糊过滤（可空 = 不过滤）
 * @param archived  归档态过滤（null=不限[含归档]；false=仅未归档；true=仅已归档）
 * @param cursor    游标分页参数（必填）
 */
public record ListVaultCredentialQuery(
        String vaultId,
        String urlSearch,
        Boolean archived,
        CursorPageParams cursor
) {

    public ListVaultCredentialQuery {
        if (vaultId == null || vaultId.isBlank()) {
            throw new IllegalArgumentException("保管库ID不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
    }
}