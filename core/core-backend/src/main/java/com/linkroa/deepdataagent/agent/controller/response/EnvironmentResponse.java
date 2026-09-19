package com.linkroa.deepdataagent.agent.controller.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 运行环境响应 DTO（标准对象头 + config + metadata 全量回显）。
 * <p>标准对象头：{@code id}（env_ 前缀）/ {@code type:"environment"}；归档仅以
 * {@code archived_at} 时间戳表达，<b>不出现 archived 布尔字段</b>。
 * 对外字段名统一 snake_case（Java 组件名保持驼峰，仅以 {@code @JsonProperty} 改写序列化键）。</p>
 *
 * @param id          运行环境业务ID（env_ 前缀）
 * @param type        资源类型词（恒 {@code "environment"}）
 * @param name        名称
 * @param description 描述（缺省空串）
 * @param config      环境配置（type / packages / setup_script）
 * @param metadata    自定义元数据键值对象
 * @param archivedAt  归档时间（null=未归档；序列化键 {@code archived_at}）
 * @param createdAt   创建时间（序列化键 {@code created_at}）
 * @param updatedAt   更新时间（序列化键 {@code updated_at}）
 */
public record EnvironmentResponse(
        String id,
        String type,
        String name,
        String description,
        EnvironmentConfigResponse config,
        Map<String, Object> metadata,
        @JsonProperty("archived_at") OffsetDateTime archivedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
}