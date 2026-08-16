package com.linkroa.deepdataagent.agent.domain.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Agent 版本领域模型（对应 agent_version 表，每次发布生成一行快照）
 *
 * @param id                数据库主键
 * @param versionId         版本业务唯一ID
 * @param agentId           Agent业务ID
 * @param versionNumber     发布号（同一 Agent 内递增，MAX+1，无乐观锁）
 * @param name              版本名称（发布标签）
 * @param description       版本描述
 * @param system            系统提示词（运行装配来源）
 * @param modelProfileId   引用模型配置 profile_id
 * @param inferenceParams   推理参数（JSONB）
 * @param skillIds          挂载的技能（[{skillId, version}]，版本锁定，仅存引用）
 * @param knowledgeBaseIds  预留知识库引用
 * @param dataSourceIds     数据源引用（关联 datasource 域 connection_id）
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 * @param createdBy         创建人
 * @param updatedBy         更新人
 */
public record AgentVersion(
        Long id,
        String versionId,
        String agentId,
        int versionNumber,
        String name,
        String description,
        String system,
        String modelProfileId,
        String inferenceParams,
        String skillIds,
        String knowledgeBaseIds,
        String dataSourceIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验
     */
    public AgentVersion {
        if (StringUtils.isBlank(versionId)) {
            throw new IllegalArgumentException("版本ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("发布版本号必须大于0");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("版本名称不能为空");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("版本名称长度不能超过64个字符");
        }
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("模型配置引用不能为空");
        }
        if (StringUtils.isNotEmpty(description) && description.length() > 500) {
            throw new IllegalArgumentException("版本描述不能超过500个字符");
        }
        if (system != null && system.length() > 20000) {
            throw new IllegalArgumentException("系统提示词长度不能超过20000个字符");
        }
    }

    /**
     * 创建新的 Agent 版本快照
     */
    public static AgentVersion create(
            String versionId,
            String agentId,
            int versionNumber,
            String name,
            String description,
            String system,
            String modelProfileId,
            String inferenceParams,
            String skillIds,
            String knowledgeBaseIds,
            String dataSourceIds
    ) {
        return new AgentVersion(
                null, versionId, agentId, versionNumber, name, description,
                system != null ? system : "", modelProfileId,
                inferenceParams, skillIds, knowledgeBaseIds, dataSourceIds,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static AgentVersion restore(
            Long id,
            String versionId,
            String agentId,
            int versionNumber,
            String name,
            String description,
            String system,
            String modelProfileId,
            String inferenceParams,
            String skillIds,
            String knowledgeBaseIds,
            String dataSourceIds,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new AgentVersion(
                id, versionId, agentId, versionNumber, name, description,
                system, modelProfileId, inferenceParams, skillIds,
                knowledgeBaseIds, dataSourceIds, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * 解析挂载的技能引用列表（[{skillId, version}]）
     */
    public List<SkillRef> parseSkillRefs() {
        if (StringUtils.isBlank(skillIds)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(skillIds, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("技能引用JSON解析失败", e);
        }
    }

    /**
     * 技能引用（技能ID + 版本锁定号）
     */
    public record SkillRef(String skillId, Integer version) {
    }
}