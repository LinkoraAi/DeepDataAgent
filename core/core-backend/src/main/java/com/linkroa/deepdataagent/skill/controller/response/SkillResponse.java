package com.linkroa.deepdataagent.skill.controller.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 技能壳响应（列表 / 详情摘要）。
 * <p>标准对象头：{@code id}（skill_ 前缀）/ {@code type:"skill"}；{@code display_title} 创建后
 * 不可修改；{@code latest_version} 为最新版本 epoch 微秒字符串（全部版本删除后为 null）；
 * {@code source} 为平台中性词汇 catalog/custom。对外字段名统一 snake_case（Java 组件名保持
 * 驼峰，仅以 {@code @JsonProperty} 改写序列化键）。</p>
 *
 * @param id           技能业务 ID（skill_ 前缀）
 * @param type         对象类型（恒为 skill）
 * @param displayTitle 展示名（≤255，创建后不可改；序列化键 {@code display_title}）
 * @param source       技能来源（catalog / custom）
 * @param latestVersion 最新版本键（可空；序列化键 {@code latest_version}）
 * @param metadata     业务自定义元数据（默认空对象）
 * @param createdAt    创建时间（序列化键 {@code created_at}）
 * @param updatedAt    更新时间（序列化键 {@code updated_at}）
 */
public record SkillResponse(
        String id,
        String type,
        @JsonProperty("display_title") String displayTitle,
        String source,
        @JsonProperty("latest_version") String latestVersion,
        Map<String, Object> metadata,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
}