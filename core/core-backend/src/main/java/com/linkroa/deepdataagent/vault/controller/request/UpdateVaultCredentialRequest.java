package com.linkroa.deepdataagent.vault.controller.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 更新凭证请求（merge-patch，契约唯一权威来源为「更新凭证」端点）。
 *
 * <p>以 <b>DELEGATING</b> 方式承载原始 JSON 对象体：普通 record 反序列化无法区分
 * 「键缺省」与「键显式为 null」，而 merge-patch 语义要求二者可分辨
 * （缺省=不改、显式 null=清除），故整体接收 {@code Map} 并由
 * {@link com.linkroa.deepdataagent.vault.application.convert.VaultCommandConvert#toUpdateCredentialCommand}
 * 以 {@code containsKey} 裁决三态。</p>
 *
 * <p>请求体形状：{@code {auth: {...}, metadata: {...}}}，两者均可缺省。
 * {@code auth} 提供时 {@code auth.type} 必填且必须与凭证既有类型一致；身份字段
 * （{@code auth.type}、{@code mcp_server_url}、{@code refresh.client_id}、
 * {@code refresh.token_endpoint}）出现即拒（400）。</p>
 *
 * @param fields 原始补丁体键值对（保留显式 null；空白补丁 = 空 Map）
 */
public record UpdateVaultCredentialRequest(Map<String, Object> fields) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public UpdateVaultCredentialRequest {
        // LinkedHashMap 快照保留键序与显式 null 值（Map.copyOf 会拒绝 null 值，禁用）
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}