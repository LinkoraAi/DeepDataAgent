package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * Agent 定义/版本 ⇄ 持久化实体转换器（基础字段一一对应，JSONB 字符串透传）。
 * <p>归档以 {@code archived_at} 时间戳单列表达（无 archived 布尔列）；系统提示词列名
 * {@code system_prompt} 与领域字段 {@code systemPrompt} 同名映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentPersistenceConvert {

    AgentPersistenceConvert INSTANCE = Mappers.getMapper(AgentPersistenceConvert.class);

    AgentDefinitionEntity toEntity(AgentDefinition definition);

    AgentDefinition toDomain(AgentDefinitionEntity entity);

    AgentVersionEntity toEntity(AgentVersion version);

    AgentVersion toDomain(AgentVersionEntity entity);
}