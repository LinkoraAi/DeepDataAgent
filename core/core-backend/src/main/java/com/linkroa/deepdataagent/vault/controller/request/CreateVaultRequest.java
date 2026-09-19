package com.linkroa.deepdataagent.vault.controller.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建保管库请求（协议层整体接收字段映射，见 design D3.1 的「显式出现」口径）。
 *
 * <p>不使用固定字段 record：普通 record 会<b>静默丢弃</b>未知键，使契约
 * 「创建请求 MUST NOT 接受 {@code credentials} 字段」（携带即 400）无法被察觉。
 * 白名单与拒绝规则在 {@code VaultCommandConvert} 中裁决。</p>
 */
public record CreateVaultRequest(Map<String, Object> fields) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CreateVaultRequest {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}