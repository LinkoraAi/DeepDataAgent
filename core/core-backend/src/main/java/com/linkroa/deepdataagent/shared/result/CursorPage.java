package com.linkroa.deepdataagent.shared.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.function.Function;

/**
 * Cursor 游标分页统一响应形状（shared/api-conventions，游标分页规范）。
 * <p>JSON 形状：{@code {data[], first_id, last_id, has_more[, next_page]}}——
 * 列表按创建时间降序、不提供 total 计数（游标分页不保证精确总数）；
 * {@code first_id / last_id} 为当页首尾资源业务 ID（翻页定位游标，空页为 null），
 * {@code next_page} 仅部分资源存在、存在时 MUST 为可直接拼接的下页参数
 * （缺省时序列化省略该键）。既有 page/size + total 端点分批迁移到本约定（BREAKING，6.2-6.6）。</p>
 *
 * @param data     当页数据（按创建时间降序）
 * @param firstId  当页首条资源 ID（空页 null）
 * @param lastId   当页末条资源 ID（空页 null）
 * @param hasMore  是否还有下一页
 * @param nextPage 可直接拼接的下页参数（可空 = 序列化省略）
 * @param <T>      数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(
        List<T> data,
        @JsonProperty("first_id") String firstId,
        @JsonProperty("last_id") String lastId,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_page") String nextPage
) {

    public CursorPage {
        data = data == null ? List.of() : List.copyOf(data);
    }

    /**
     * 由当页数据装配游标分页响应（first/last 经 ID 提取器取当页首尾资源 ID）。
     *
     * @param data         当页数据（创建时间降序）
     * @param hasMore      是否还有下一页
     * @param idExtractor  资源业务 ID 提取器
     * @param <T>          数据类型
     * @return 游标分页响应（无 next_page 形态）
     */
    public static <T> CursorPage<T> of(List<T> data, boolean hasMore, Function<T, String> idExtractor) {
        List<T> items = data == null ? List.of() : data;
        String first = items.isEmpty() ? null : idExtractor.apply(items.get(0));
        String last = items.isEmpty() ? null : idExtractor.apply(items.get(items.size() - 1));
        return new CursorPage<>(items, first, last, hasMore, null);
    }

    /**
     * 内存切片装配游标页（游标分页的两处内存切片收敛至此）：取 {@code rows} 头部
     * {@code params.limit()} 条为当页，余量（探针行或游标后剩余）即 {@code has_more}。
     * <p>两种读取形态共用本方法：① DB 侧 {@code limit+1} 探针读取后传全部行；
     * ② 随宿主整列存储、无独立时间戳的资源按游标下标截取余量后传入。
     * {@code reverse=true} 表示读取方向与展示方向相反（如 before 方向升序读取后翻回降序、
     * 或向前翻页时以尾部为头部读取），此时当页取头部后再翻回展示方向。</p>
     *
     * @param rows        游标之后的候选行（展示方向或读取方向由 {@code reverse} 表达）
     * @param params      游标分页参数（取 {@code limit}）
     * @param reverse     读取方向与展示方向是否相反
     * @param idExtractor 资源业务 ID 提取器
     * @param <T>         数据类型
     * @return 游标分页响应（无 next_page 形态）
     */
    public static <T> CursorPage<T> slice(List<T> rows, CursorPageParams params, boolean reverse,
                                          Function<T, String> idExtractor) {
        List<T> candidates = rows == null ? List.of() : rows;
        List<T> data = candidates.subList(0, Math.min(candidates.size(), params.limit()));
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return of(data, candidates.size() > params.limit(), idExtractor);
    }

    /**
     * 元素映射（领域模型 → 协议层 DTO 用）：保持游标字段（first/last/has_more/next_page）不变。
     *
     * @param mapper 元素转换函数
     * @param <U>    目标数据类型
     * @return 映射后的游标分页响应
     */
    public <U> CursorPage<U> map(Function<T, U> mapper) {
        return new CursorPage<>(data.stream().map(mapper).toList(), firstId, lastId, hasMore, nextPage);
    }

    /**
     * 补齐公开契约的不透明下页游标形态：{@code has_more} 为真时以当页 {@code last_id} 作为
     * {@code next_page}（客户端原样回传即可续页），无余量时保持 {@code null}（序列化省略该键）。
     * <p>仅契约明文要求回传 {@code next_page} 的端点（如版本历史列表）需要调用；其余端点保留
     * 「{@code next_page} 缺省省略」的既有形态。</p>
     *
     * @return 已补齐 {@code next_page} 的游标分页响应（无余量时返回自身）
     */
    public CursorPage<T> withNextCursorFromLastId() {
        return hasMore && lastId != null ? new CursorPage<>(data, firstId, lastId, true, lastId) : this;
    }
}
