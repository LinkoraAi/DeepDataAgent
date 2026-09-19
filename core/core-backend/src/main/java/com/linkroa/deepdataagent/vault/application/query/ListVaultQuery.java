package com.linkroa.deepdataagent.vault.application.query;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;

/**
 * 保管库游标列表 / 搜索查询（Cursor 约定；列表端点与搜索端点共用同一查询对象，
 * 差异仅在搜索首页额外返回 {@code total}）。
 *
 * @param ownerId      归属用户 ID（必填，owner 隔离）
 * @param name         显示名称模糊过滤（不区分大小写；可空 = 不过滤）
 * @param metadataJson metadata 包含过滤（JSON 对象文本，可空 = 不过滤；搜索端点为精确匹配多条件 AND）
 * @param archived     归档态过滤（null=不限[含归档]；false=仅未归档；true=仅已归档）
 * @param cursor       游标分页参数（必填）
 */
public record ListVaultQuery(
        Long ownerId,
        String name,
        String metadataJson,
        Boolean archived,
        CursorPageParams cursor
) {

    public ListVaultQuery {
        if (ownerId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
    }
}