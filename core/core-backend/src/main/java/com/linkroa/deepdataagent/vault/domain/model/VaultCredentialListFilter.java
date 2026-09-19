package com.linkroa.deepdataagent.vault.domain.model;

import java.time.OffsetDateTime;

/**
 * 凭证列表游标查询条件（shared/api-conventions Cursor 约定）。
 * <p>排序为 {@code created_at DESC, id DESC}（创建时间降序、数据库行号稳定次键）；
 * keyset 游标位置（{@code cursorCreatedAt} + {@code cursorRowId}）由应用层将
 * after_id/before_id 解析为行位点后装配，两者必须成对出现。</p>
 *
 * @param urlSearch       绑定的 MCP 服务器 URL 过滤（模糊匹配；可空 = 不过滤）
 * @param archived        归档态过滤（null=不限；false=仅未归档；true=仅已归档）
 * @param cursorCreatedAt 游标行创建时间（可空 = 首页）
 * @param cursorRowId     游标行数据库主键（次排序键定位；与游标时间成对）
 * @param reverse         true=before_id 方向（取更新侧、升序读取后由应用层翻转回降序）
 */
public record VaultCredentialListFilter(
        String urlSearch,
        Boolean archived,
        OffsetDateTime cursorCreatedAt,
        Long cursorRowId,
        boolean reverse
) {

    public VaultCredentialListFilter {
        if ((cursorCreatedAt == null) != (cursorRowId == null)) {
            throw new IllegalArgumentException("游标位置必须成对提供（创建时间与行号）");
        }
    }
}