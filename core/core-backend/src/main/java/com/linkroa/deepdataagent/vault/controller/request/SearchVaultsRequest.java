package com.linkroa.deepdataagent.vault.controller.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保管库搜索请求（仅接受 JSON Body 参数，见 design D13）。
 *
 * <p>请求体形如 {@code {"metadata": {"team": "data"}, "name": "分析", "limit": 20,
 * "page": "vault_x", "include_archived": true}}；同请求的 URL Query 参数 MUST 被忽略
 * （控制器不声明任何 {@code @RequestParam} 绑定，筛选只以请求体为准）。</p>
 *
 * <p>整体接收字段映射的原因同 {@link CreateVaultRequest}：普通 record 会静默丢弃未知键，
 * 使「白名单外字段一律 400」无法被察觉。字段解析与 metadata 边界校验在
 * {@code VaultCommandConvert} 中裁决。</p>
 */
public record SearchVaultsRequest(Map<String, Object> fields) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SearchVaultsRequest {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}