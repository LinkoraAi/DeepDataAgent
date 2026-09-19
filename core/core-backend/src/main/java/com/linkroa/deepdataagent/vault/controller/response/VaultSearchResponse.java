package com.linkroa.deepdataagent.vault.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 保管库搜索响应 DTO（design D13）：在游标分页形状上增加 {@code total}。
 *
 * <p>{@code total} 为满足筛选条件的总数，<b>仅首页返回</b>（翻页请求为 null → 整键省略，
 * 见 tasks 5.8）；其余字段语义与 {@code CursorPage} 完全一致（{@code next_page} 缺省省略）。</p>
 *
 * @param data     当页数据（创建时间降序）
 * @param firstId  当页首条资源 ID（空页 null）
 * @param lastId   当页末条资源 ID（空页 null）
 * @param hasMore  是否还有下一页
 * @param nextPage 可直接拼接的下页参数（可空 = 序列化省略）
 * @param total    满足筛选条件的总数（<b>仅首页</b>，翻页时为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultSearchResponse(
        List<VaultResponse> data,
        @JsonProperty("first_id") String firstId,
        @JsonProperty("last_id") String lastId,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_page") String nextPage,
        Long total
) {

    public VaultSearchResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }
}