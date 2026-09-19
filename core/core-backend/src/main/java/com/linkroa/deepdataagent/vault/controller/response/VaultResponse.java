package com.linkroa.deepdataagent.vault.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 保管库响应 DTO（对齐公开契约的 Vault 对象形状，不含任何凭证密文 / 明文）。
 *
 * <p>{@code credentials} 仅在<b>创建 Vault 的响应</b>中返回且值固定为空数组；列表与详情
 * MUST NOT 包含该字段（{@code NON_NULL} 省略承载：值为 null 时整键省略）。
 * {@code metadata} 恒为对象形状（JSON 文本 → key/value 对象），与搜索端点的入参形态可往返。</p>
 *
 * @param id          保管库业务 ID（前缀 {@code vault_}）
 * @param type        资源类型标识（固定 {@code vault}）
 * @param displayName 显示名称（≤255 字符）
 * @param metadata    元数据键值对象（无 metadata 时为空对象）
 * @param archivedAt  归档时间（null=未归档）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param credentials 空凭证数组（<b>仅创建响应</b>，其余场景省略）
 */
public record VaultResponse(
        String id,
        String type,
        @JsonProperty("display_name") String displayName,
        Map<String, Object> metadata,
        @JsonProperty("archived_at") OffsetDateTime archivedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Object> credentials
) {

    /** 资源类型标识（契约固定值）。 */
    public static final String TYPE = "vault";

    /** 创建响应中的凭证数组（契约要求值恒为空数组）。 */
    public static final List<Object> EMPTY_CREDENTIALS = List.of();
}