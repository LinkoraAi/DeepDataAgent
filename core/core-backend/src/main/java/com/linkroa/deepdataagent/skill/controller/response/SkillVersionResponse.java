package com.linkroa.deepdataagent.skill.controller.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 技能内容版本响应（不可变快照元数据，不暴露内容全文与内部校验值）。
 * <p>标准对象头：{@code id}（skillver_ 前缀）/ {@code type:"skill_version"}；
 * {@code version} 为创建时刻 epoch 微秒字符串；内容经版本级 zip 端点下载。
 * 对外字段名统一 snake_case（Java 组件名保持驼峰，仅以 {@code @JsonProperty} 改写序列化键）。</p>
 *
 * @param id          版本业务 ID（skillver_ 前缀）
 * @param type        对象类型（恒为 skill_version）
 * @param skillId     所属技能业务 ID（序列化键 {@code skill_id}）
 * @param version     版本键（epoch 微秒字符串）
 * @param name        frontmatter name（跨版本一致）
 * @param description frontmatter description
 * @param directory   包顶级目录名（恒等于 name）
 * @param createdAt   版本创建时间（序列化键 {@code created_at}）
 */
public record SkillVersionResponse(
        String id,
        String type,
        @JsonProperty("skill_id") String skillId,
        String version,
        String name,
        String description,
        String directory,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}